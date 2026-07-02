package com.kevingosse.docent.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.kevingosse.docent.DocentReviewService
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What the "discuss this section" strip ([SectionConversationPanel]) talks to (docs/DESIGN.md §6). The only
 * backend is [McpLoopBackend], which routes the remark to the connected agent (the one that armed the review via
 * `docent_finalize_trail` / `docent_resume_review`, or the session linked in the "Connect agent…" UI); its reply
 * arrives back through [DocentReviewService]. The conversation is only available when an agent is connected — the
 * panel disables its input otherwise — so there is no self-spawned fallback (the ACP self-spawn transport in
 * `acp/` is retained for the future different-model critic, not this chat).
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

        /** The remark has gone unanswered past the liveness timeout — the agent's watch may be dead. The panel
         *  should stop spinning and offer [nudge], which re-pushes the remark into the agent's chat thread
         *  (false → the agent can't be reached at all). A late reply may still arrive via [onReply]. */
        fun onStalled(nudge: () -> Boolean) {}
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
            onReply = { reply ->
                if (!disposed) {
                    turn.onReply(reply)
                    turn.onDone()
                }
            },
            onStalled = { eventId ->
                if (!disposed) turn.onStalled { service.nudge(eventId) }
            },
        )
        pendingIds.add(id)
    }

    override fun dispose() {
        disposed = true
        val service = DocentReviewService.getInstance(project)
        pendingIds.forEach { service.cancelSink(it) }
        pendingIds.clear()
    }
}
