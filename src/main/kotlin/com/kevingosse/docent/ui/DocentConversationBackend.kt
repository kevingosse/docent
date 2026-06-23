package com.kevingosse.docent.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.kevingosse.docent.DocentReviewService
import com.kevingosse.docent.acp.DocentAgentSession
import com.kevingosse.docent.trail.Section
import com.kevingosse.docent.trail.Trail
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What the "discuss this section" strip ([SectionConversationPanel]) talks to, so one chat UI serves two very
 * different backends (docs/DESIGN.md §6):
 *  - [McpLoopBackend] — routes the remark to the SAME agent that called `docent_finalize_trail` (the
 *    workbench/MCP path); its reply arrives back through [DocentReviewService].
 *  - [SelfSpawnBackend] — spawns our own `claude-agent-acp` (the no-workbench fallback for the Tools menu
 *    / `runRider` sandbox).
 *
 * All [Turn] callbacks fire off-EDT; the panel re-dispatches to the EDT.
 */
interface DocentConversationBackend : Disposable {
    fun send(text: String, turn: Turn)

    /** One conversational turn's callbacks. */
    interface Turn {
        /** A reply fragment (a full reply for the MCP loop; streamed chunks for the self-spawn agent). */
        fun onReply(text: String)
        fun onStatus(text: String) {}
        fun onDone() {}
        fun onError(message: String) {}
    }
}

/**
 * Routes the human's remark to the authoring agent via the [DocentReviewService] broker. No process is
 * spawned — the agent that opened the review is already polling `docent_await_event`.
 */
class McpLoopBackend(
    private val project: Project,
    private val sectionIndex: Int,
    private val sectionHeadline: String,
) : DocentConversationBackend {

    @Volatile private var disposed = false
    private val pendingIds = CopyOnWriteArrayList<String>()

    override fun send(text: String, turn: DocentConversationBackend.Turn) {
        turn.onStatus("waiting for the Docent…")
        val service = DocentReviewService.getInstance(project)
        val id = service.postEvent(
            kind = DocentReviewService.KIND_MESSAGE,
            sectionIndex = sectionIndex,
            sectionHeadline = sectionHeadline,
            file = "",
            line = 0,
            context = "",
            text = text,
        ) { reply ->
            if (!disposed) {
                turn.onReply(reply)
                turn.onDone()
            }
        }
        pendingIds.add(id)
    }

    override fun dispose() {
        disposed = true
        val service = DocentReviewService.getInstance(project)
        pendingIds.forEach { service.cancelSink(it) }
        pendingIds.clear()
    }
}

/**
 * The no-workbench fallback: owns a persistent [DocentAgentSession] (one spawned `claude-agent-acp`), started
 * lazily on the first message and disposed with this backend.
 */
class SelfSpawnBackend(
    private val project: Project,
    private val trail: Trail,
    private val section: Section,
) : DocentConversationBackend {

    private var session: DocentAgentSession? = null
    @Volatile private var disposed = false

    override fun send(text: String, turn: DocentConversationBackend.Turn) {
        if (disposed) return turn.onError("the Docent session is closed")
        val base = project.basePath
        if (base == null) return turn.onError("open the reviewed solution first")

        val existing = session
        if (existing != null) {
            if (existing.isStarted) ask(existing, text, turn) else turn.onStatus("the Docent is still starting…")
            return
        }
        val s = DocentAgentSession(base, trail, section)
        session = s
        s.onStatus = { msg -> turn.onStatus(msg) }
        Disposer.register(this, s)
        turn.onStatus("starting the Docent…")
        s.start(
            onReady = { turn.onStatus(""); ask(s, text, turn) },
            onError = { msg -> turn.onError(msg) },
        )
    }

    private fun ask(s: DocentAgentSession, text: String, turn: DocentConversationBackend.Turn) {
        s.ask(text, object : DocentAgentSession.Turn {
            override fun onChunk(t: String) = turn.onReply(t)
            override fun onThought(t: String) = turn.onStatus("thinking…")
            override fun onStatus(t: String) = turn.onStatus(t)
            override fun onDone(stopReason: String) = turn.onDone()
            override fun onError(message: String) = turn.onError(message)
        })
    }

    override fun dispose() {
        disposed = true
        // The session is registered as our child Disposable, so Disposer kills the agent process for us.
    }
}
