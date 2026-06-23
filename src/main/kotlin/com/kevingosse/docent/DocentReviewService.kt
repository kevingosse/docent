package com.kevingosse.docent

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-project broker for the live review (docs/DESIGN.md §6 — "the agent-driven review loop").
 *
 * It is the seam between two worlds:
 *  - the **UI** (EDT): the "discuss this section" strip and the inline comment cards post the human's
 *    remarks here and register where the agent's reply should land;
 *  - the **authoring agent** (an MCP coroutine): the same agent that called `docent_finalize_trail`
 *    drains those remarks via [awaitEvent], answers with [deliverReply], and records requested changes
 *    with [queueChange].
 *
 * The agent never sees the UI and the UI never spawns an agent — they meet here. When no MCP agent is
 * driving (review opened from the Tools menu, or the `runRider` sandbox), [reviewActive] is false and the
 * UI falls back to a self-spawned agent instead (see `ui/DocentConversationBackend`).
 *
 * **Push, not poll (the token fix).** A workbench-launched Claude agent CANNOT efficiently long-poll
 * [awaitEvent]: that call rides the IDE's streamable-HTTP MCP transport, which Claude Code caps at a
 * hardcoded ~60s first-byte timeout that `MCP_TOOL_TIMEOUT` can't raise — so a silent wait trips every
 * minute and each re-call burns a model turn. Instead we **push**: when the human acts, [postEvent] /
 * [completeReview] ask the registered [EventNotifier] to send a prompt straight into the agent's existing
 * workbench thread (it wakes, replies via `docent_reply`, and ends its turn — zero idle turns). The
 * notifier is installed by the optional AWB module (`awb/DocentEventNotifier`); when it's absent or a push
 * fails, we fall back to enqueuing for [awaitEvent] (the legacy poll path, also the catch-up safety net).
 */
@Service(Service.Level.PROJECT)
class DocentReviewService {

    /** Path to the Trail JSON for the current review; null → no trail is loaded (the review shows an
     *  empty state). There is no default — a trail is loaded only via the MCP handoff or finalize. */
    @Volatile
    var trailPath: String? = null

    /** True once a review was opened via the MCP handoff ([com.kevingosse.docent.mcp.DocentMcpToolset]):
     *  the authoring agent is expected to be driving the loop, so the UI routes remarks to it. */
    @Volatile
    var reviewActive: Boolean = false

    /** True once the agent has actually started polling [awaitEvent] — the loop is live. */
    @Volatile
    var loopEngaged: Boolean = false

    /** Called (off-EDT) whenever the requested-change queue changes, so the UI can refresh its count. */
    @Volatile
    var onChangesUpdated: (() -> Unit)? = null

    /** The workbench thread id of the agent driving the review (the Claude `--session-id`), captured at
     *  launch by the AWB module. The [EventNotifier] targets this thread when it pushes an event. */
    @Volatile
    var agentThreadId: String? = null

    /** The project path the workbench used for that thread, passed back verbatim on push so the workbench
     *  resolves the same thread (it normalizes this internally). Falls back to the project base path. */
    @Volatile
    var agentProjectPath: String? = null

    /** Pushes a human action straight into the driving agent's workbench thread, so it doesn't have to
     *  long-poll. Installed by the optional AWB module; null when the workbench isn't present. */
    @Volatile
    var eventNotifier: EventNotifier? = null

    // Human → agent: a single-consumer queue (the agent's await loop) the UI offers events onto.
    private val events = LinkedBlockingQueue<ReviewEvent>()
    // Agent → UI: where each event's reply lands, keyed by event id. The sink marshals to the EDT itself.
    private val replySinks = ConcurrentHashMap<String, (String) -> Unit>()
    private val idGen = AtomicLong(0)
    private val changes = CopyOnWriteArrayList<QueuedChange>()

    /**
     * UI → agent. Enqueue a human remark and register [onReply] as where the agent's answer should land
     * (the sink is called off-EDT — it must marshal). Returns the event id the agent echoes in `docent_reply`.
     */
    fun postEvent(
        kind: String,
        sectionIndex: Int,
        sectionHeadline: String,
        file: String,
        line: Int,
        context: String,
        text: String,
        onReply: (String) -> Unit,
    ): String {
        val id = "evt-${idGen.incrementAndGet()}"
        replySinks[id] = onReply
        val event = ReviewEvent(id, kind, sectionIndex, sectionHeadline, file, line, context, text)
        // Push into the agent's thread; only enqueue (for awaitEvent) if there's no notifier or it fails.
        if (!tryPush(event)) events.offer(event)
        return id
    }

    /** Try to push [event] straight to the driving agent. Returns true if the notifier accepted it. */
    private fun tryPush(event: ReviewEvent): Boolean =
        runCatching { eventNotifier?.notifyAgent(event) ?: false }.getOrDefault(false)

    /** Stop routing replies for an event (its UI element was disposed). */
    fun cancelSink(id: String) {
        replySinks.remove(id)
    }

    /**
     * Agent loop: suspend until the human posts an event (or completes the review), then return it the instant
     * it lands. No server-side timeout — we block indefinitely so a quiet review never produces churn. If the
     * MCP *client* times out the idle call, that surfaces to the agent as an error; the tool description for
     * `docent_await_event` tells the agent that's expected and to simply call again (it's not the end of the
     * review). [runInterruptible] makes the blocking take cancellable, so a cancelled tool call frees the
     * [Dispatchers.IO] thread instead of leaking it.
     */
    suspend fun awaitEvent(): ReviewEvent {
        loopEngaged = true
        return runInterruptible(Dispatchers.IO) { events.take() }
    }

    /** Agent → UI: deliver [text] to the element that originated [eventId]. False if nothing is waiting. */
    fun deliverReply(eventId: String, text: String): Boolean {
        val sink = replySinks[eventId] ?: return false
        sink(text)
        return true
    }

    /** Record a change the human asked for; applied only when the review is completed. Returns the new count. */
    fun queueChange(summary: String, file: String, line: Int, sectionIndex: Int): Int {
        changes.add(QueuedChange(summary, file, line, sectionIndex))
        onChangesUpdated?.invoke()
        return changes.size
    }

    fun queuedChanges(): List<QueuedChange> = changes.toList()

    /** Human pressed "Complete review": hand the agent the queue to implement (editing now allowed). */
    fun completeReview() {
        val summary = changes.joinToString("\n") { c ->
            buildString {
                append("- ")
                append(c.summary)
                if (c.file.isNotBlank()) append("  (${c.file}${if (c.line > 0) ":${c.line}" else ""})")
            }
        }
        val event = ReviewEvent(id = "", kind = REVIEW_COMPLETED, text = summary)
        if (!tryPush(event)) events.offer(event)
        reviewActive = false
    }

    /** Tear down any in-flight loop state (new review, or "Reload trail"). */
    fun reset() {
        reviewActive = false
        loopEngaged = false
        events.clear()
        replySinks.clear()
        changes.clear()
        onChangesUpdated?.invoke()
    }

    companion object {
        const val REVIEW_COMPLETED = "review_completed"
        const val KIND_MESSAGE = "message"
        const val KIND_COMMENT = "comment"

        fun getInstance(project: Project): DocentReviewService =
            project.getService(DocentReviewService::class.java)
    }
}

/** One human action during the review, delivered to the authoring agent via `docent_await_event`. */
data class ReviewEvent(
    val id: String,
    val kind: String,
    val sectionIndex: Int = -1,
    val sectionHeadline: String = "",
    val file: String = "",
    val line: Int = 0,
    val context: String = "",
    val text: String = "",
)

/** A change the reviewer asked for; held read-only until the review is completed. */
data class QueuedChange(val summary: String, val file: String, val line: Int, val sectionIndex: Int)

/**
 * Pushes a [ReviewEvent] into the driving agent's session so it doesn't have to long-poll [awaitEvent].
 * Implemented by the optional AWB module (`awb/DocentEventNotifier`), which sends the event as a prompt to
 * the agent's existing workbench thread. Kept as a plain interface here so the core stays platform-clean
 * (no workbench dependency leaks in). Returns true if the event was accepted for delivery.
 */
interface EventNotifier {
    fun notifyAgent(event: ReviewEvent): Boolean
}
