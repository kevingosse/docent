package com.kevingosse.docent.awb

import com.intellij.platform.ai.agent.core.session.AgentSessionProvider
import com.intellij.agent.workbench.prompt.core.AgentPromptInitialMessageRequest
import com.intellij.agent.workbench.prompt.core.AgentPromptLaunchRequest
import com.intellij.agent.workbench.prompt.core.AgentPromptLaunchers
import com.intellij.agent.workbench.sessions.state.AgentSessionsStateStore
import com.intellij.platform.ai.agent.core.session.AgentSessionLaunchMode
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VirtualFile
import com.kevingosse.docent.DeliveryMode
import com.kevingosse.docent.DocentReviewService
import com.kevingosse.docent.EventLog
import com.kevingosse.docent.EventNotifier
import com.kevingosse.docent.ReviewEvent

/**
 * Pushes a reviewer event straight into an agent's existing workbench thread — used to wake a session that
 * isn't in a turn: the `REVIEW_RESUMED` event from "Connect agent…" and the `START_REVIEW` event from the
 * on-demand review trigger. Installed onto the service by [DocentLaunchContributor] / [DocentWorkbenchSetup];
 * the [DocentReviewService.agentThreadId] captured at link time is the thread we target.
 *
 * Two delivery channels, tried in order ([notifyAgent]):
 *  1. **Live open chat-tab terminal** ([sendViaLiveTerminal]) — type the prompt into the session's terminal, the
 *     same path the workbench's own file-drop / initial-message dispatch use. Only works when the tab's terminal
 *     is already built (the tab has been activated at least once this IDE run) — we deliberately do NOT activate a
 *     cold tab and type into a still-booting terminal, which races and can type-without-submitting; those sessions
 *     are reported unreachable ([WorkbenchSessionDirectory] flags them) so the UI asks the user to activate first.
 *     This is the only channel that works when the persisted store is empty (the .slnx listing bug).
 *  2. **Prompt-launcher bridge** ([AgentPromptLaunchers] with `targetThreadId`) — the supported "send to an
 *     existing session" API, but it resolves the target from the persisted store, so it only reaches idle/closed
 *     sessions and fails `TARGET_THREAD_NOT_FOUND` for .slnx solutions whose store has no threads.
 *
 * It's `@Internal`/unstable workbench API → lives in the optional, gated `awb/` module, and channel 1 reaches the
 * editor's terminal by reflection. Every workbench touch is wrapped defensively; any failure returns false (the
 * review is still armed — the agent can be told to resume manually).
 */
internal class DocentEventNotifier(private val project: Project) : EventNotifier {

    override fun notifyAgent(event: ReviewEvent): Boolean {
        return try {
            val service = DocentReviewService.getInstance(project)
            val threadId = service.agentThreadId?.takeIf { it.isNotBlank() } ?: run {
                LOG.info("Docent: no captured agent thread id; falling back to the poll path")
                return false
            }
            val prompt = buildPrompt(event)

            // Primary channel: type the prompt into the session's LIVE open chat-tab terminal. Only if it's already
            // built — we don't activate a cold tab and type into a booting terminal (brittle; can type without
            // submitting). Cold tabs are reported unreachable so the UI tells the user to activate them first.
            if (sendViaLiveTerminal(threadId, prompt)) return true

            // Fallback for an idle/closed session in the persisted store: the supported prompt-launcher push.
            pushViaLauncher(service, threadId, prompt)
        } catch (t: Throwable) {
            LOG.warn("Docent: failed to push a review event to the agent; falling back to the poll path", t)
            false
        }
    }

    /**
     * Fallback for a session with no reachable open-tab terminal: the supported prompt-launcher push. The
     * workbench resolves the target by finding the thread UNDER the request's project path (case/separator-
     * sensitive), and a thread may live under the dedicated chat-frame project, so pass the path the store
     * records for it. Returns true if the launch was accepted.
     */
    private fun pushViaLauncher(service: DocentReviewService, threadId: String, prompt: String): Boolean {
        val projectPath = ownerProjectPath(threadId)
            ?: service.agentProjectPath?.takeIf { it.isNotBlank() }
            ?: project.basePath
            ?: return false
        val bridge = AgentPromptLaunchers.find() ?: run {
            LOG.info("Docent: no prompt-launcher bridge; falling back to the poll path")
            return false
        }
        val result = bridge.launch(
            AgentPromptLaunchRequest(
                provider = providerOf(service.agentProvider),
                projectPath = projectPath,
                launchMode = AgentSessionLaunchMode.STANDARD,
                initialMessageRequest = AgentPromptInitialMessageRequest(prompt = prompt, projectPath = projectPath),
                targetThreadId = threadId,
            ),
        )
        if (!result.launched) {
            LOG.info(
                "Docent: push to thread $threadId (project=$projectPath) not delivered (${result.error}); " +
                    "falling back to the poll path. Known store paths: ${storeProjectPaths()}",
            )
        }
        return result.launched
    }

    /**
     * Deliver [prompt] by typing it into the chat tab's ALREADY-LIVE terminal — the same mechanism the workbench's
     * own file-drop / initial-message dispatch use (`AgentChatFileEditor.tab.sendText`). Returns false (caller
     * falls back to the launcher) if the tab isn't open or its terminal isn't built yet; we don't activate + type
     * into a booting terminal, which races and can type-without-submitting. Everything here is `@Internal`, so the
     * terminal tab is reached by reflection.
     */
    private fun sendViaLiveTerminal(threadId: String, prompt: String): Boolean {
        return try {
            val (proj, vf) = findOpenChatTab(threadId) ?: return false
            val tab = terminalTabOf(proj, vf) ?: run {
                LOG.info("Docent: chat tab for $threadId has no live terminal (not activated this run); trying the launcher")
                return false
            }
            // sendText(text, shouldExecute, useBracketedPasteMode) — execute it; bracketed-paste keeps multi-line
            // intact. setAccessible bypasses the language check: the method is public but its declaring class
            // (ToolWindowAgentChatTerminalTab) is `internal`, so a plain invoke throws IllegalAccessException.
            val sendText = tab.javaClass.methods.firstOrNull { it.name == "sendText" && it.parameterCount == 3 } ?: return false
            sendText.isAccessible = true
            sendText.invoke(tab, prompt, true, true)
            LOG.info("Docent: delivered to open chat tab for thread $threadId via terminal sendText")
            true
        } catch (t: Throwable) {
            LOG.warn("Docent: terminal sendText to the open chat tab failed for thread $threadId; trying the launcher", t)
            false
        }
    }

    /** The [AGENT_CHAT_FILE_EDITOR_FQN]'s lazily-created terminal `tab` (reflected), or null if it isn't built yet
     *  (the tab was never activated this IDE run). */
    private fun terminalTabOf(proj: Project, vf: VirtualFile): Any? = runCatching {
        val editor = FileEditorManager.getInstance(proj).getEditors(vf)
            .firstOrNull { it.javaClass.name == AGENT_CHAT_FILE_EDITOR_FQN } ?: return null
        editor.javaClass.getDeclaredField("tab").apply { isAccessible = true }.get(editor)
    }.getOrNull()

    /** The open AgentChatVirtualFile (and its project) whose thread/session id is [threadId], across all open
     *  projects (the workbench can host chats in a dedicated frame). Read reflectively — the type is `@Internal`. */
    private fun findOpenChatTab(threadId: String): Pair<Project, VirtualFile>? {
        for (p in ProjectManager.getInstance().openProjects) {
            if (p.isDisposed) continue
            val files = runCatching { FileEditorManager.getInstance(p).openFiles.asList() }.getOrDefault(emptyList())
            for (vf in files) {
                if (vf.javaClass.name != AGENT_CHAT_VFILE_FQN) continue
                val id = invokeString(vf, "getThreadId")?.takeIf { it.isNotBlank() }
                    ?: invokeString(vf, "getSessionId")?.takeIf { it.isNotBlank() }
                if (id == threadId) return p to vf
            }
        }
        return null
    }

    private fun invokeString(target: Any, method: String): String? =
        runCatching { target.javaClass.getMethod(method).invoke(target) as? String }.getOrNull()

    /**
     * The project path the workbench's session store records for [threadId] — the exact string its prompt
     * launcher matches against (`AgentSessionLaunchService.findPromptTargetThread`), scanning both projects and
     * their worktrees. Returns null when the thread isn't persisted (so the caller falls back to our path).
     */
    private fun ownerProjectPath(threadId: String): String? = runCatching {
        val state = service<AgentSessionsStateStore>().snapshot()
        for (p in state.projects) {
            if (p.threads.any { it.id == threadId && !it.archived }) return@runCatching p.path
            for (w in p.worktrees) if (w.threads.any { it.id == threadId && !it.archived }) return@runCatching w.path
        }
        null
    }.getOrNull()

    /** Project + worktree paths the store knows, for diagnosing a TARGET_THREAD_NOT_FOUND push. */
    private fun storeProjectPaths(): List<String> = runCatching {
        service<AgentSessionsStateStore>().snapshot().projects.flatMap { p -> listOf(p.path) + p.worktrees.map { it.path } }
    }.getOrDefault(emptyList())

    /** Render an event as a prompt the agent receives as a new user turn in its existing session. */
    private fun buildPrompt(event: ReviewEvent): String = buildString {
        append("[Code Review Docent] ")
        when (event.kind) {
            DocentReviewService.REVIEW_COMPLETED -> {
                appendLine("The reviewer has completed the review. Implement the queued changes now (editing is")
                appendLine("allowed); there is nothing more to wait for after this. Queued changes:")
                appendLine(event.text.ifBlank { "(none)" })
            }

            DocentReviewService.START_REVIEW -> {
                appendLine("The reviewer is ready — present your changes for review now.")
                appendLine(
                    "If the docent tools aren't in your tool list, discover them first with a tool search " +
                        "(limit 50): docent_record_decision docent_list_decisions docent_change_summary " +
                        "docent_finalize_trail docent_reply docent_queue_change docent_resume_review.",
                )
                appendLine(
                    "Then: call docent_change_summary first (it returns the ground-truth changed files, exact " +
                        "line ranges, your recorded decisions, AND the Trail JSON schema). Compose the Trail " +
                        "strictly from your recorded decisions using those field names, and call " +
                        "docent_finalize_trail to write it and open the review — pass your sessionToken so the " +
                        "review routes back to THIS session. If finalize returns a validation error, fix exactly " +
                        "the named field and call it again.",
                )
            }

            DocentReviewService.REVIEW_RESUMED -> {
                append("The reviewer opened a Docent review")
                if (event.text.isNotBlank()) append(" for \"${event.text}\"")
                appendLine(" and connected you as the Docent.")
                if (event.file.isNotBlank()) appendLine("Trail file: ${event.file}")
                appendLine(
                    "Read the trail file now to refresh the WHY (the narration and inline comments) — your earlier " +
                        "context may be gone.",
                )
                // Monitor (Claude) → hand it the watch command; AWAIT (Codex) → tell it to block on the tool.
                // Gate on the delivery MODE, not merely on a log existing: the log file is begun for AWAIT too,
                // it's just unused there, so keying off its presence would wrongly send Codex to the Monitor tool.
                val service = DocentReviewService.getInstance(project)
                val logPath = service.eventLogPath
                if (service.deliveryMode == DeliveryMode.MONITOR && logPath != null) {
                    appendLine()
                    appendLine("Then watch this review's events with the Monitor tool (persistent: true), running EXACTLY this command:")
                    appendLine("  " + EventLog.watchCommand(logPath))
                    appendLine(
                        "Each line it prints is one reviewer action (JSON): answer questions with docent_reply " +
                            "(using the event id), record requested changes with docent_queue_change (read-only — " +
                            "do NOT edit files until review_completed, at which point the watch exits and you " +
                            "implement them). Start the watch, then end your turn.",
                    )
                } else {
                    appendLine(
                        "Then drive the review with docent_await_event (it blocks until the reviewer acts): answer " +
                            "questions with docent_reply, record requested changes with docent_queue_change " +
                            "(read-only — do NOT edit files until review_completed).",
                    )
                }
            }

            else -> {
                val where = buildString {
                    if (event.sectionIndex >= 0) {
                        append("section ${event.sectionIndex + 1}")
                        if (event.sectionHeadline.isNotBlank()) append(" (\"${event.sectionHeadline}\")")
                    }
                    if (event.file.isNotBlank()) {
                        if (isNotEmpty()) append(", ")
                        append(event.file)
                        if (event.line > 0) append(":${event.line}")
                    }
                }
                append("The reviewer ")
                append(if (event.kind == DocentReviewService.KIND_COMMENT) "left a comment" else "said something")
                if (where.isNotBlank()) append(" on $where")
                appendLine(":")
                if (event.context.isNotBlank()) appendLine("> ${event.context}")
                appendLine("\"${event.text}\"")
                appendLine()
                appendLine(
                    "Answer from your first-hand knowledge of WHY (not a restatement of the diff) by calling " +
                        "docent_reply(eventId=\"${event.id}\", text=...). If they are requesting a change, also call " +
                        "docent_queue_change(...) and acknowledge it briefly (\"queued\") — do NOT edit files yet. " +
                        "Then end your turn; you'll be messaged again when the reviewer next acts.",
                )
            }
        }
    }.trimEnd()

    private companion object {
        private val LOG = logger<DocentEventNotifier>()

        /** `@Internal` workbench types reached by reflection (see [sendViaOpenTabTerminal]); matched on FQN. */
        private const val AGENT_CHAT_VFILE_FQN = "com.intellij.agent.workbench.chat.AgentChatVirtualFile"
        private const val AGENT_CHAT_FILE_EDITOR_FQN = "com.intellij.agent.workbench.chat.AgentChatFileEditor"

        /** Map a stored provider value (`AgentSessionProvider.value`) back to the provider for the launch request;
         *  default to Claude when unknown/null (the historical single-provider case). `AgentSessionProvider` is a
         *  value class (no `entries`/`values()`), so match against the constants we support. */
        fun providerOf(value: String?): AgentSessionProvider =
            if (value == AgentSessionProvider.CODEX.value) AgentSessionProvider.CODEX else AgentSessionProvider.CLAUDE
    }
}
