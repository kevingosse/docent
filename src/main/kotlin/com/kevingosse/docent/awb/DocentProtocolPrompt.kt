package com.kevingosse.docent.awb

import com.kevingosse.docent.DeliveryMode

/**
 * The "Docent protocol" injected into a workbench-launched agent's system/base instructions at launch
 * (see [DocentLaunchContributor]). This is how the agent learns that the Code Review Docent exists and
 * when to use its tools **without the human prompting it** — transparently, scoped to agents this IDE
 * launches (not a per-repo CLAUDE.md/AGENTS.md, not a global always-on skill).
 *
 * The agent reaches the `docent_*` tools through the IDE's in-process MCP server; Claude defers MCP tools
 * behind its own client-side tool search, so the text is explicit that the tools may not be listed up front
 * and must be searched for by name. Keep the tool names here in sync with [com.kevingosse.docent.mcp.DocentMcpToolset]
 * — these names ARE the contract the agent acts on.
 *
 * The create/resume guidance is identical for every provider; only **how reviewer actions arrive** differs by
 * the connected agent's [DeliveryMode] (see [forDelivery]): Claude **watches** the EventLog file with its
 * background Monitor tool; Codex has no such tool, so it **blocks** on `docent_await_event`.
 */
internal object DocentProtocolPrompt {

    /** The tool-search query that surfaces every docent tool. Shared with the IDE's "start a review" push. */
    const val TOOL_QUERY: String =
        "docent_change_summary docent_create_review docent_await_event docent_reply docent_queue_change docent_resume_review"

    /**
     * Create + resume intro — delivery-agnostic. The agent may have made the change itself, or may be reviewing
     * someone else's change; the text covers both.
     */
    private val INTRO: String = """
        This JetBrains IDE has the Code Review Docent installed. It walks a human reviewer through a code change,
        with the WHY behind it — never a restatement of the diff.

        Do nothing with it until the user asks you to start a code review — in chat ("let's start a code
        review", "review this branch"…) or via a "[Code Review Docent]" message. Then:
          1. Discover the docent tools: run a tool search with this exact query, and set the result limit to 50:
               $TOOL_QUERY
             The IDE exposes these tools lazily through tool search, so they are NOT in your initial tool list —
             their absence does NOT mean they're unavailable.
          2. Call docent_change_summary. It returns the changed files, exact line ranges, the commits, the Trail
             JSON schema, and the rules for the two seats: source="session" if YOU made this change in this
             session, source="inferred" if you are reviewing a change you did not make (then the reasons are
             your guesses from the diff and commits, and the Trail must say so).
          3. Compose the whole Trail strictly with the field names it gives, and call docent_create_review to
             write it and open the review. If it returns a validation error, fix exactly the named field and
             call it again.
        Never open a review on your own — finishing a change is not a trigger.

        While a review runs, answer on an inferred review from the trail, the code and the commits, and say
        plainly when a reason is your guess. On a session review, answer from your first-hand knowledge.

        To pick up a review you started earlier but didn't finish (its Trail already exists on disk), call
        docent_resume_review — it reloads the Trail into the review UI and connects you as the Docent. Then read
        the trail file to refresh the WHY before answering.
    """.trimIndent()

    /** Tail for Claude (and any provider with a background-watch tool): watch the EventLog file. */
    private val MONITOR_TAIL: String = """
        When a review opens (via docent_create_review or docent_resume_review, or because the reviewer connected
        you from the IDE), you will be given a shell command that watches the review's events file. Run it with
        the Monitor tool (persistent: true), then end your turn — each reviewer action arrives as a new message
        (one JSON line) for you to handle:
          - a reviewer question/comment: answer with docent_reply (using
            the event id in the JSON). If they request a change, also call docent_queue_change and acknowledge it
            briefly ("queued") — do NOT edit files yet.
          - a "review_completed" event: the reviewer is done; the watch exits itself. Implement the queued
            changes now (editing is allowed). Then tell the user the changes are ready for a follow-up review —
            the user starts it; do NOT create a review on your own.
          - a "review_discarded" event: the reviewer abandoned the review; the watch exits itself. Do NOT
            implement anything — the queue is dropped. Acknowledge in one line and wait for the user.
        Do NOT block on docent_await_event when you have a watch command — the file watch is the inbound path.
    """.trimIndent()

    /** Tail for Codex (and any provider without a background-watch tool): block on `docent_await_event`. */
    private val AWAIT_TAIL: String = """
        When a review opens (via docent_create_review or docent_resume_review, or because the reviewer connected
        you from the IDE), drive it by calling docent_await_event — it BLOCKS until the reviewer's next action and
        returns it as a small JSON envelope. For each one:
          - a reviewer question/comment: answer with docent_reply (using
            the event id in the JSON). If they request a change, also call docent_queue_change and acknowledge it
            briefly ("queued") — do NOT edit files yet. Then call docent_await_event again.
          - a "review_completed" envelope: the reviewer is done. Implement the queued changes now (editing is
            allowed). Then tell the user the changes are ready for a follow-up review — the user starts it; do
            NOT create a review on your own.
          - a "review_discarded" envelope: the reviewer abandoned the review. Do NOT implement anything — the
            queue is dropped. Stop calling docent_await_event, acknowledge in one line and wait for the user.
        Keep calling docent_await_event after every event until review_completed or review_discarded. If the
        call ever returns a timeout/error before an event (a quiet review), that's expected — simply call it again.
    """.trimIndent()

    /** Back-compat alias for the Monitor (Claude) protocol. Prefer [forDelivery]. */
    val TEXT: String get() = "$INTRO\n\n$MONITOR_TAIL"

    /**
     * The protocol for an agent reached via [mode], with this session's [sessionToken] appended when known. The
     * launch contributor knows the token (the workbench thread id) per session and bakes it in, so the agent can
     * echo it to docent_create_review/docent_resume_review and the review's pushed messages route back to the
     * right session.
     */
    fun forDelivery(mode: DeliveryMode, sessionToken: String? = null): String {
        val tail = if (mode == DeliveryMode.AWAIT) AWAIT_TAIL else MONITOR_TAIL
        val base = "$INTRO\n\n$tail"
        return if (sessionToken != null) "$base\n\nYour sessionToken for this session is: $sessionToken" else base
    }
}
