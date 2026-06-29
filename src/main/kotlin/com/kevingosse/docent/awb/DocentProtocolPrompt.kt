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
 * The capture/resume guidance is identical for every provider; only **how reviewer actions arrive** differs by
 * the connected agent's [DeliveryMode] (see [forDelivery]): Claude **watches** the EventLog file with its
 * background Monitor tool; Codex has no such tool, so it **blocks** on `docent_await_event`.
 */
internal object DocentProtocolPrompt {

    /**
     * Capture + resume intro — delivery-agnostic. It must read sensibly whether the agent is about to author a
     * change or to walk a human through one — it cannot know which up front.
     */
    private val INTRO: String = """
        Before ANY coding task, you MUST discover the docent tools: run a tool search with this exact query, and
        set the result limit to 50:

          docent_record_decision docent_list_decisions docent_change_summary docent_finalize_trail docent_await_event docent_reply docent_queue_change docent_resume_review

        The IDE exposes these tools lazily through tool search, so they are NOT in your initial tool list — their
        absence does NOT mean they're unavailable. Do this first; do not go straight to the edit/build loop
        without them.

        This JetBrains IDE has the Code Review Docent installed: the docent tools capture and review the STORY
        of a change — the non-reconstructable WHY, never a restatement of the diff — so the user can review what
        you did.

        While you work on a change, use docent_record_decision to keep track of your technical and design
        decisions. This is your scratchpad, do not rely solely on your context as it might be lost during
        compaction. Pass your sessionToken (given at the end of these instructions) to docent_record_decision so
        your decisions are attributed to THIS session.

        Do NOT finalize or open a review on your own — there is no "the change is done, so present it" trigger.
        Keep recording decisions and keep working; the user decides when a review happens. Present your changes
        ONLY when the user explicitly asks you to start the review — either by saying so in chat, or via a
        "[Code Review Docent]" message asking you to present your changes. Only then: ALWAYS call
        docent_change_summary first (it returns the ground-truth changed-file list, exact line ranges, your
        recorded decisions, AND the Trail JSON schema with field names) — do not skip it and guess the schema.
        Then compose the Trail strictly with the field names it gives (top level subject/thesis/sections; each
        section headline/narration/anchors; the code a comment points at lives in an anchor's comments[], keyed
        by path), and finally call docent_finalize_trail to write it and open the review. If finalize returns a
        validation error, fix exactly the named field and call it again.

        To pick up a review you started earlier but didn't finish (its Trail already exists on disk), call
        docent_resume_review — it reloads the Trail into the review UI and connects you as the Docent. Then read
        the trail file to refresh the WHY before answering.
    """.trimIndent()

    /** Tail for Claude (and any provider with a background-watch tool): watch the EventLog file. */
    private val MONITOR_TAIL: String = """
        When a review opens (via docent_finalize_trail or docent_resume_review, or because the reviewer connected
        you from the IDE), you will be given a shell command that watches the review's events file. Run it with
        the Monitor tool (persistent: true), then end your turn — each reviewer action arrives as a new message
        (one JSON line) for you to handle:
          - a reviewer question/comment: answer from your first-hand knowledge of WHY with docent_reply (using
            the event id in the JSON). If they request a change, also call docent_queue_change and acknowledge it
            briefly ("queued") — do NOT edit files yet.
          - a "review_completed" event: the reviewer is done; the watch exits itself. Implement the queued
            changes now (editing is allowed), then stop.
        Do NOT block on docent_await_event when you have a watch command — the file watch is the inbound path.
    """.trimIndent()

    /** Tail for Codex (and any provider without a background-watch tool): block on `docent_await_event`. */
    private val AWAIT_TAIL: String = """
        When a review opens (via docent_finalize_trail or docent_resume_review, or because the reviewer connected
        you from the IDE), drive it by calling docent_await_event — it BLOCKS until the reviewer's next action and
        returns it as a small JSON envelope. For each one:
          - a reviewer question/comment: answer from your first-hand knowledge of WHY with docent_reply (using
            the event id in the JSON). If they request a change, also call docent_queue_change and acknowledge it
            briefly ("queued") — do NOT edit files yet. Then call docent_await_event again.
          - a "review_completed" envelope: the reviewer is done. Implement the queued changes now (editing is
            allowed), then stop.
        Keep calling docent_await_event after every event until review_completed. If the call ever returns a
        timeout/error before an event (a quiet review), that's expected — simply call it again.
    """.trimIndent()

    /** Back-compat alias for the Monitor (Claude) protocol. Prefer [forDelivery]. */
    val TEXT: String get() = "$INTRO\n\n$MONITOR_TAIL"

    /**
     * The protocol for an agent reached via [mode], with this session's [sessionToken] appended when known. The
     * launch contributor knows the token (the workbench thread id) per session and bakes it in, so the agent can
     * echo it to docent_finalize_trail/docent_resume_review and the review's pushed messages route back to the
     * right session.
     */
    fun forDelivery(mode: DeliveryMode, sessionToken: String? = null): String {
        val tail = if (mode == DeliveryMode.AWAIT) AWAIT_TAIL else MONITOR_TAIL
        val base = "$INTRO\n\n$tail"
        return if (sessionToken != null) "$base\n\nYour sessionToken for this session is: $sessionToken" else base
    }
}
