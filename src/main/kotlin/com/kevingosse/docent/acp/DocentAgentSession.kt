package com.kevingosse.docent.acp

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.kevingosse.docent.trail.Section
import com.kevingosse.docent.trail.Trail
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The live Docent (docs/DESIGN.md §6/§8) — **the coding agent that authored the change**, connected over
 * our hand-rolled [AcpClient] and talked to by the UI *directly*. It is **not** a separate reviewer: it
 * knows the change first-hand, so it answers the human's questions from real knowledge, and it owns the
 * change (so requested changes can flow straight back to it).
 *
 * This is a **persistent, multi-turn conversation** scoped to one section: [start] spawns the agent and opens
 * a session once (kept alive); each [ask] is one conversational turn whose reply streams back via a [Turn].
 * The first turn is primed with the section's context (thesis + author narration + files) and the review-phase
 * rules.
 *
 * Read-only posture *during* the review: we reject edit/delete/move tool calls (the guardrail) and instruct
 * the agent to *queue* requested changes rather than implement them until the review is completed.
 *
 * Threading: all work runs on pooled threads / the ACP reader thread; **callbacks fire off-EDT and the
 * caller marshals to the EDT** (see [com.kevingosse.docent.ui.SectionConversationPanel]).
 */
class DocentAgentSession(
    private val repoPath: String,
    private val trail: Trail,
    private val section: Section,
) : Disposable {

    /** Callbacks for one [ask] turn. All fire off-EDT; the caller re-dispatches to the EDT. */
    interface Turn {
        fun onChunk(text: String)
        fun onThought(text: String) {}
        fun onStatus(text: String) {}
        fun onDone(stopReason: String)
        fun onError(message: String)
    }

    /** Optional sink for session-level status (e.g. the agent process exiting). Set by the caller. */
    @Volatile var onStatus: ((String) -> Unit)? = null

    private var client: AcpClient? = null
    @Volatile private var sessionId: String? = null
    @Volatile private var disposed = false
    @Volatile private var starting = false
    @Volatile private var started = false
    @Volatile private var primed = false
    @Volatile private var currentTurn: Turn? = null

    val isStarted: Boolean get() = started

    /** Spawn the agent and open a session (initialize → session/new). [onReady]/[onError] fire off-EDT. */
    @Synchronized
    fun start(onReady: () -> Unit, onError: (String) -> Unit) {
        if (disposed || starting || started) return
        starting = true
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val c = AcpClient(
                    commandLine = buildCommandLine(),
                    requestHandler = { method, params -> handleServerRequest(method, params) },
                    notificationHandler = { method, params -> handleNotification(method, params) },
                    onExit = { code, err -> if (!disposed && err != null) onStatus?.invoke("agent exited (code=$code)") },
                )
                client = c
                c.start()
                c.request("initialize", initParams()).get(30, TimeUnit.SECONDS)
                val session = c.request("session/new", sessionParams()).get(60, TimeUnit.SECONDS)
                sessionId = session.get("sessionId").asString
                started = true
                if (!disposed) onReady()
            } catch (t: Throwable) {
                if (!disposed) onError(t.message ?: t.toString())
            } finally {
                starting = false
            }
        }
    }

    /** Send one conversational turn; its reply streams via [turn]. Call only after [start]'s onReady. */
    fun ask(userText: String, turn: Turn) {
        if (disposed) { turn.onError("the Docent session is closed"); return }
        val c = client
        val sid = sessionId
        if (c == null || sid == null) { turn.onError("the Docent isn't ready yet"); return }
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                currentTurn = turn
                val text = if (!primed) { primed = true; primedPrompt(userText) } else userText
                val result = c.request("session/prompt", promptParams(sid, text)).get(5, TimeUnit.MINUTES)
                if (!disposed) turn.onDone(str(result, "stopReason") ?: "done")
            } catch (t: Throwable) {
                if (!disposed) turn.onError(t.message ?: t.toString())
            }
        }
    }

    // ----- agent→client handlers -----

    private fun handleNotification(method: String, params: JsonObject?) {
        if (method != "session/update") return
        val update = params?.get("update")?.takeIf { it.isJsonObject }?.asJsonObject ?: return
        val turn = currentTurn
        when (str(update, "sessionUpdate")) {
            "agent_message_chunk" -> textOf(update)?.let { turn?.onChunk(it) }
            "agent_thought_chunk" -> textOf(update)?.let { turn?.onThought(it) }
            "tool_call" -> turn?.onStatus("checking: ${str(update, "title") ?: str(update, "kind") ?: "…"}")
            "plan" -> turn?.onStatus("planning…")
        }
    }

    private fun handleServerRequest(method: String, params: JsonObject?): JsonObject = when (method) {
        "session/request_permission" -> resolvePermission(params)
        // The agent normally uses its own Read tool; this only fires if it delegates fs reads to us.
        "fs/read_text_file" -> JsonObject().apply {
            val path = str(params, "path")
            val text = path?.let { runCatching { Files.readString(Path.of(it)) }.getOrNull() }
            addProperty("content", text ?: "")
        }
        else -> JsonObject()
    }

    /** Read-only review posture: allow reads/search/execute, reject anything that mutates the tree. */
    private fun resolvePermission(params: JsonObject?): JsonObject {
        val options = params?.get("options")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
        val toolKind = str(params?.get("toolCall")?.takeIf { it.isJsonObject }?.asJsonObject, "kind")
        val allow = toolKind == null || toolKind !in REJECT_KINDS
        val wanted = if (allow) ALLOW_OPTION_KINDS else REJECT_OPTION_KINDS

        val chosen = options.firstOrNull { it.isJsonObject && str(it.asJsonObject, "kind") in wanted }
            ?: options.firstOrNull { it.isJsonObject }
        val optionId = str(chosen?.asJsonObject, "optionId")
        currentTurn?.onStatus("permission: ${toolKind ?: "?"} → ${if (allow) "allow" else "reject"}")

        return JsonObject().apply {
            add("outcome", JsonObject().apply {
                addProperty("outcome", "selected")
                if (optionId != null) addProperty("optionId", optionId)
            })
        }
    }

    // ----- request params -----

    private fun initParams() = JsonObject().apply {
        addProperty("protocolVersion", 1)
        add("clientCapabilities", JsonObject().apply {
            add("fs", JsonObject().apply {
                addProperty("readTextFile", true)
                addProperty("writeTextFile", false) // read-only during review
            })
            addProperty("terminal", false)
        })
    }

    private fun sessionParams() = JsonObject().apply {
        addProperty("cwd", repoPath)
        add("mcpServers", JsonArray())
    }

    private fun promptParams(sessionId: String, text: String) = JsonObject().apply {
        addProperty("sessionId", sessionId)
        add("prompt", JsonArray().apply {
            add(JsonObject().apply { addProperty("type", "text"); addProperty("text", text) })
        })
    }

    /** Spawn `node <adapter>` directly (the npm `.ps1`/`.cmd` shims don't exec cleanly from Java). */
    private fun buildCommandLine(): GeneralCommandLine =
        GeneralCommandLine(NODE_EXE)
            .withParameters(ADAPTER_JS)
            .withWorkDirectory(repoPath)
            .withCharset(StandardCharsets.UTF_8)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)

    // ----- the priming prompt: who the Docent is, and the review-phase rules -----

    private fun primedPrompt(userText: String): String = buildString {
        appendLine("You are the coding agent that wrote the change under review. You are now walking a human")
        appendLine("reviewer through it, one section (step) at a time, and they have a question or remark. Answer")
        appendLine("from your first-hand knowledge of WHY you made these choices - not a restatement of the")
        appendLine("diff. Be direct and concise; your reply streams into a side panel, so keep it tight.")
        appendLine()
        appendLine("This is the REVIEW phase. Do NOT modify any files now. If the reviewer asks for a change,")
        appendLine("acknowledge it and say you will apply it when they complete the review - do not edit yet.")
        appendLine("You may read or search the code to ground your answer.")
        appendLine()
        appendLine("== Change under review ==")
        appendLine(trail.subject)
        appendLine()
        appendLine("Overall thesis:")
        appendLine(htmlToText(trail.thesis))
        appendLine()
        appendLine("== Current section ==")
        appendLine(section.headline)
        appendLine()
        appendLine("What you told the reviewer about this section (your narration):")
        appendLine(htmlToText(section.narration))
        appendLine()
        appendLine("Files this section touches:")
        appendLine(section.anchors.joinToString("\n") { "- " + it.path }.ifBlank { "(none listed)" })
        appendLine()
        appendLine("== The reviewer says ==")
        append(userText)
    }

    // ----- helpers -----

    private fun str(obj: JsonObject?, key: String): String? =
        obj?.get(key)?.takeUnless { it.isJsonNull }?.asString

    private fun textOf(update: JsonObject): String? =
        str(update.get("content")?.takeIf { it.isJsonObject }?.asJsonObject, "text")

    /** Author narration is authored as HTML; flatten it to plain text for the prompt. */
    private fun htmlToText(html: String): String =
        html.replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace(Regex(" +"), " ")
            .trim()

    override fun dispose() {
        disposed = true
        currentTurn = null
        client?.let { Disposer.dispose(it) }
        client = null
    }

    companion object {
        // The self-spawn fallback runs `node <adapter>`. Both are machine-specific, so they're read
        // from the environment rather than hard-coded:
        //   DOCENT_NODE         — path to the node executable (defaults to "node" on PATH).
        //   DOCENT_ACP_ADAPTER  — path to @zed-industries/claude-agent-acp's dist/index.js
        //                         (install via `npm i -g @zed-industries/claude-agent-acp`).
        private val NODE_EXE: String =
            System.getenv("DOCENT_NODE")?.takeIf { it.isNotBlank() } ?: "node"
        private val ADAPTER_JS: String =
            System.getenv("DOCENT_ACP_ADAPTER")?.takeIf { it.isNotBlank() } ?: ""

        // Tool *kinds* (ACP toolCall.kind) we refuse in a read-only review.
        private val REJECT_KINDS = setOf("edit", "delete", "move")
        // Permission *option* kinds (ACP PermissionOption.kind) to pick from.
        private val ALLOW_OPTION_KINDS = setOf("allow_once", "allow_always")
        private val REJECT_OPTION_KINDS = setOf("reject_once", "reject_always")
    }
}
