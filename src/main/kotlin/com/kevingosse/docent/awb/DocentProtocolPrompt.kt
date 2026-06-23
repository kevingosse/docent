package com.kevingosse.docent.awb

/**
 * The "Docent protocol" injected into a workbench-launched agent's system/base instructions at launch
 * (see [DocentLaunchContributor]). This is how the agent learns that the Code Review Docent exists and
 * when to use its tools **without the human prompting it** — transparently, scoped to agents this IDE
 * launches (not a per-repo CLAUDE.md, not a global always-on skill).
 *
 * The agent reaches the `docent_*` tools through the IDE's in-process MCP server; Claude defers MCP tools
 * behind its own client-side tool search, so the text is explicit that the tools may not be listed up front
 * and must be searched for by name. Keep the tool names here in sync with [com.kevingosse.docent.mcp.DocentMcpToolset]
 * — these names ARE the contract the agent acts on.
 */
internal object DocentProtocolPrompt {

    /**
     * Kept deliberately short and purpose-explaining. It must read sensibly whether the agent is about to
     * author a change or to walk a human through one — it cannot know which up front.
     */
    val TEXT: String = """
        This JetBrains IDE has the Code Review Docent installed. It exposes a set of "docent_*" tools through
        the IDE's MCP server for capturing and reviewing the STORY of a change — the non-reconstructable WHY,
        never a restatement of the diff. The goal is to explain the code changes to the user, and provide them
        all the information they need to review them. These tools may not be listed up front (the IDE defers
        MCP tools); search for them by name when you need them.

        While you work on a change, use docent_record_decision to keep track of your technical and design
        decisions. This is your scratchpad, do not rely solely on your context as it might be lost during
        compaction.
        When the task is complete, you MUST use docent to present your changes to the user for review. For
        that, first call docent_change_summary for the ground-truth file list and your recorded decisions, then
        compose a Trail from those decisions (its guidance describes the JSON schema and the section-vs-
        inline-comment granularity rule), and finally call docent_finalize_trail to write it and open the
        review.

        When reviewing, the IDE will send you a new message each time the reviewer acts:
          - a reviewer question/comment: answer from your first-hand knowledge of WHY with docent_reply (using
            the event id in the message). If they request a change, also call docent_queue_change and
            acknowledge it briefly ("queued") — do NOT edit files yet.
          - a "review completed" message: the reviewer is done; implement the queued changes now (editing is
            allowed), then stop.
    """.trimIndent()

    /**
     * [TEXT] with this session's [sessionToken] appended. The launch contributor knows the token (the
     * workbench thread id) per session and bakes it in, so the agent can echo it to docent_finalize_trail and the review's pushed messages route back to the right session.
     */
    fun withSessionToken(sessionToken: String): String =
        TEXT + "\n\nYour sessionToken for this session is: $sessionToken"
}
