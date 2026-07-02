package com.kevingosse.docent

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
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
 * **How the human's actions reach the agent (the timeout fix).** A blocking `docent_await_event` is a
 * dead end against Claude Code — its MCP tool calls die at a stack of undocumented client timeouts (60s
 * request, ~5min SSE idle) that no env knob reliably lifts. So the inbound path depends on the connected
 * agent's [deliveryMode]:
 *  - **[DeliveryMode.MONITOR]** (Claude Code): every action is appended to a per-review [EventLog] file the
 *    agent watches with its background-watch tool — events reach it as chat notifications, entirely OUTSIDE
 *    any MCP tool-call budget. The only MCP traffic left is the agent's short replies. This is the path today.
 *  - **[DeliveryMode.AWAIT]** (a future provider with sane MCP timeouts): actions are enqueued and the agent
 *    drains them by blocking on [awaitEvent].
 *
 * Either way the UI registers a reply sink per event ([replySinks]); [deliverReply] routes the agent's answer
 * back regardless of how the event was delivered. The one thing that still **pushes** into the agent's thread
 * is the UI-initiated *resume* (`linkAgentSession` + a `REVIEW_RESUMED` notify) — there's no other way to
 * wake an idle agent the human picked in "Connect agent…"; after that bootstrap the agent watches the file.
 */
@Service(Service.Level.PROJECT)
class DocentReviewService(private val project: Project) {

    /** How reviewer actions reach the connected agent (see the class doc). Set by the launch contributor from
     *  the provider (Claude → MONITOR); defaults to MONITOR, the only wired provider today. */
    @Volatile
    var deliveryMode: DeliveryMode = DeliveryMode.MONITOR

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

    /** Called whenever the agent-connection state ([reviewActive]) flips, so open views can enable/disable
     *  their interactive affordances (the section conversation, the inline-comment "+") live. May fire off-EDT. */
    @Volatile
    var onConnectionChanged: (() -> Unit)? = null

    /** Lists the live agent sessions the UI can link a loaded trail to. Installed by the optional AWB module
     *  ([com.kevingosse.docent.awb.WorkbenchSessionDirectory]); null when the workbench isn't present. */
    @Volatile
    var sessionDirectory: AgentSessionDirectory? = null

    /** Starts a brand-new agent session (with an initial prompt) for the UI's "start a new session" option.
     *  Installed by the optional AWB module; null when the workbench isn't present. */
    @Volatile
    var sessionLauncher: AgentSessionLauncher? = null

    /** The workbench thread id of the agent driving the review (the Claude `--session-id`), captured at
     *  launch by the AWB module. The [EventNotifier] targets this thread when it pushes an event. */
    @Volatile
    var agentThreadId: String? = null

    /** The project path the workbench used for that thread, passed back verbatim on push so the workbench
     *  resolves the same thread (it normalizes this internally). Falls back to the project base path. */
    @Volatile
    var agentProjectPath: String? = null

    /** The connected agent's provider value (`AgentSessionProvider.value`, e.g. "claude" / "codex"), so the
     *  [EventNotifier] pushes to the right provider and the UI can label it. Set by the launch contributor (per
     *  launch) and authoritatively by the UI "Connect agent…" path (which knows the picked session's provider).
     *  Null until a session is launched/linked. Drives [deliveryMode] via [deliveryModeForProvider]. */
    @Volatile
    var agentProvider: String? = null

    /** Pushes the UI-initiated *resume* into the picked agent's workbench thread (the one case that still needs
     *  a push — see the class doc). Installed by the optional AWB module; null when the workbench isn't present. */
    @Volatile
    var eventNotifier: EventNotifier? = null

    /** The current review's append-only event log (MONITOR mode); the agent watches it. Null until a review is
     *  armed via [linkAgentSession]. Replaced (fresh file) on each new review. */
    @Volatile
    var eventLog: EventLog? = null
        private set

    /** The project-root-relative path of [eventLog], for the watch command handed to the agent. Null when none. */
    val eventLogPath: String? get() = eventLog?.relativePath

    // Human → agent: a single-consumer queue (the agent's await loop) the UI offers events onto.
    private val events = LinkedBlockingQueue<ReviewEvent>()
    // Agent → UI: where each event's reply lands, keyed by event id. The sink marshals to the EDT itself.
    private val replySinks = ConcurrentHashMap<String, (String) -> Unit>()
    // Events awaiting a reply, kept so [nudge] can re-deliver one; cleared with its sink.
    private val pendingEvents = ConcurrentHashMap<String, ReviewEvent>()
    // One liveness timer per in-flight event (see [postEvent]'s onStalled); cancelled on reply/cancel.
    private val stallTimers = ConcurrentHashMap<String, ScheduledFuture<*>>()
    private val idGen = AtomicLong(0)
    private val changes = CopyOnWriteArrayList<QueuedChange>()

    /**
     * UI → agent. Enqueue a human remark and register [onReply] as where the agent's answer should land
     * (the sink is called off-EDT — it must marshal). Returns the event id the agent echoes in `docent_reply`.
     *
     * [onStalled] is the liveness check behind the UI's waiting spinners: nothing verifies the agent is still
     * watching after arm time, so a dead watch would otherwise spin forever. If no reply lands within
     * [REPLY_STALL_SECONDS] it is invoked once (off-EDT) with the event id — the UI swaps its spinner for a
     * "not responding" affordance and can [nudge] the agent. The sink stays registered: a late reply still lands.
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
        onStalled: ((eventId: String) -> Unit)? = null,
    ): String {
        val id = "evt-${idGen.incrementAndGet()}"
        replySinks[id] = onReply
        val event = ReviewEvent(id, kind, sectionIndex, sectionHeadline, file, line, context, text)
        pendingEvents[id] = event
        if (onStalled != null) {
            stallTimers[id] = AppExecutorUtil.getAppScheduledExecutorService().schedule({
                stallTimers.remove(id)
                if (replySinks.containsKey(id)) onStalled(id)
            }, REPLY_STALL_SECONDS, TimeUnit.SECONDS)
        }
        dispatch(event)
        return id
    }

    /**
     * Re-deliver a still-unanswered event by pushing it straight into the agent's chat thread (the
     * [EventNotifier] channel — the same push the UI-initiated resume uses). The "Nudge" affordance behind a
     * stalled spinner: the file watch may have died with the agent none the wiser, but a chat push wakes it.
     * False when the event was already answered, or no push channel can reach the agent.
     */
    fun nudge(eventId: String): Boolean {
        val event = pendingEvents[eventId] ?: return false
        return eventNotifier?.notifyAgent(event) == true
    }

    /**
     * Deliver [event] to the connected agent by the active [deliveryMode]: MONITOR appends it to the [eventLog]
     * file the agent watches; AWAIT (or MONITOR with no log armed yet) enqueues it for [awaitEvent]. Note this
     * never pushes — the only push is the UI-initiated resume in [linkAgentSession]'s caller.
     */
    private fun dispatch(event: ReviewEvent) {
        val log = eventLog
        if (deliveryMode == DeliveryMode.MONITOR && log != null) {
            log.append(ReviewEventEnvelope.toJson(event, monitor = true))
        } else {
            events.offer(event)
        }
    }

    /** Stop routing replies for an event (its UI element was disposed). */
    fun cancelSink(id: String) {
        replySinks.remove(id)
        clearStall(id)
    }

    /** An event is no longer awaiting a reply: drop its retained copy and cancel its liveness timer. */
    private fun clearStall(id: String) {
        pendingEvents.remove(id)
        stallTimers.remove(id)?.cancel(false)
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
        clearStall(eventId)
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

    /**
     * Arm the live loop against a specific agent session: [threadId] is the push target (the workbench thread
     * id / Claude `--session-id`), or null when there's no push target (the agent must poll). Used by both the
     * MCP arm path (the arming session self-identifies via its sessionToken) and the UI "Connect agent…" path
     * (the user picked the session, so the thread id is known authoritatively). Marks the review active and
     * notifies the UI so its interactive affordances enable.
     */
    fun linkAgentSession(threadId: String?) {
        agentThreadId = threadId?.takeIf { it.isNotBlank() }
        beginEventLog()
        reviewActive = true
        onConnectionChanged?.invoke()
    }

    /** Start a fresh per-review event log (MONITOR mode), so this review's watch never sees a prior review's
     *  messages. No-op (leaves [eventLog] null → events fall back to the queue) if the project has no base path. */
    private fun beginEventLog() {
        eventLog = project.basePath?.let { EventLog.begin(it, idGen.incrementAndGet().toString()) }
    }

    /** Human pressed "Complete review": hand the agent the queue to implement (editing now allowed). */
    fun completeReview() {
        val summary = changes.joinToString("\n") { c ->
            buildString {
                append("- ")
                append(c.summary)
                if (c.file.isNotBlank()) append("  (${c.file}${if (c.line > 0) ":${c.line}" else ""})")
            }
        }
        dispatch(ReviewEvent(id = "", kind = REVIEW_COMPLETED, text = summary))
        reviewActive = false
        onConnectionChanged?.invoke()
    }

    /** Tear down any in-flight loop state (new review, or "Reload trail"). Leaves [agentProvider]/[deliveryMode]
     *  alone — they're set per launch by the contributor and shouldn't be cleared out from under a fresh arm. */
    fun reset() {
        reviewActive = false
        loopEngaged = false
        eventLog = null
        events.clear()
        replySinks.clear()
        stallTimers.values.forEach { it.cancel(false) }
        stallTimers.clear()
        pendingEvents.clear()
        changes.clear()
        onChangesUpdated?.invoke()
        onConnectionChanged?.invoke()
    }

    companion object {
        /** How long a posted remark may go unanswered before its UI is told the Docent looks gone (T1 in
         *  docs/ASSESSMENT.md). Generous — the agent may legitimately be reading code before replying. */
        const val REPLY_STALL_SECONDS = 150L

        const val REVIEW_COMPLETED = "review_completed"
        const val REVIEW_RESUMED = "review_resumed"
        const val START_REVIEW = "start_review"
        const val KIND_MESSAGE = "message"
        const val KIND_COMMENT = "comment"

        fun getInstance(project: Project): DocentReviewService =
            project.getService(DocentReviewService::class.java)
    }
}

/** How reviewer actions reach the connected agent (see [DocentReviewService]'s class doc). */
enum class DeliveryMode {
    /** Append each action to the [EventLog] file the agent watches with its background-watch tool (Claude Code). */
    MONITOR,

    /** Enqueue each action for the agent to drain by blocking on `docent_await_event` (sane-MCP-timeout provider). */
    AWAIT,
}

/**
 * The [DeliveryMode] a provider's CLI supports, keyed by `AgentSessionProvider.value`. Codex has no
 * background-watch tool (it can't tail the [EventLog] file out-of-band), so it must **block** on
 * `docent_await_event` ([DeliveryMode.AWAIT]); Claude — and the default for anything else — watches the
 * EventLog file with its Monitor tool ([DeliveryMode.MONITOR]). Kept in core (a plain String switch) so the
 * platform-clean UI can map a picked session's provider without importing any workbench type.
 */
fun deliveryModeForProvider(provider: String?): DeliveryMode =
    if (provider.equals("codex", ignoreCase = true)) DeliveryMode.AWAIT else DeliveryMode.MONITOR

/** A live agent session the UI can link a loaded trail to (see [AgentSessionDirectory]). */
data class AgentSessionInfo(
    /** The workbench thread id (== Claude `--session-id`); the push target for review events. */
    val threadId: String,
    val title: String,
    val provider: String,
    /** Epoch millis the session was last active, for ordering/display. */
    val updatedAt: Long,
    /** Whether the Docent can deliver to this session *right now* — its open-tab terminal is live, or it's in
     *  the persisted store (launcher-reachable). False for a chat tab that hasn't been activated this IDE run:
     *  its terminal isn't built and we won't type into a booting one, so the UI asks the user to activate it. */
    val reachable: Boolean = true,
)

/**
 * Lists the live agent sessions a loaded trail can be connected to. Implemented by the optional AWB module
 * (`awb/WorkbenchSessionDirectory`), which reads the workbench's session store. Kept as a plain interface here
 * so the core stays platform-clean (no workbench dependency leaks in).
 */
interface AgentSessionDirectory {
    fun listSessions(): List<AgentSessionInfo>
}

/**
 * Starts a fresh agent session seeded with [initialPrompt], for the UI's "start a new session" option. Used to
 * resume a review when no suitable existing session is connectable (e.g. only a brand-new, not-yet-started tab
 * exists, which has no id to target). The launched agent is told to call `docent_resume_review`, which arms the
 * loop and pins the push target via its sessionToken — so the UI need not know the new session's id. Implemented
 * by the optional AWB module (`awb/WorkbenchAgentLauncher`). Returns true if the launch was accepted.
 *
 * [provider] is the `AgentSessionProvider.value` to launch (e.g. "claude" / "codex"); the launcher maps it back
 * to a workbench provider. The launch contributor then injects the right Docent protocol + delivery mode for it.
 */
interface AgentSessionLauncher {
    fun startSession(initialPrompt: String, provider: String): Boolean
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
 * Pushes a [ReviewEvent] straight into an agent's existing workbench thread. Used for the one case that can't
 * go through the [EventLog]/await channels: waking an *idle* agent the human picked in "Connect agent…" to
 * start a review (a `REVIEW_RESUMED` event — there's no other way to reach a session that isn't in a turn).
 * Ongoing actions then flow through the agent's file watch, not here. Implemented by the optional AWB module
 * (`awb/DocentEventNotifier`); kept as a plain interface so the core stays platform-clean.
 *
 * Returns true if the event was delivered. It only reaches a session that's reachable *right now* — a live
 * open-tab terminal or one in the persisted store; it will NOT wake a cold chat tab (never activated this IDE
 * run), so the UI marks those unreachable ([AgentSessionInfo.reachable]) and asks the user to activate them.
 */
interface EventNotifier {
    fun notifyAgent(event: ReviewEvent): Boolean
}
