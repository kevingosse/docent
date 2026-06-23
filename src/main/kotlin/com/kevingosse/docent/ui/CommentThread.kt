package com.kevingosse.docent.ui

/**
 * Mutable, in-session state for one inline comment thread — seeded from the Trail (a Docent
 * comment) or created by the reviewer. Replies and resolution live only for the session for now;
 * routing reviewer feedback back to the coding agent is a later concern (DESIGN.md §9, an MCP).
 *
 * [author] is "docent" or "you". [line] is 1-based in the anchor file.
 */
class CommentThread(
    val author: String,
    var body: String,
    val line: Int,
) {
    val replies = mutableListOf<Reply>()
    var collapsed = false

    /** True while an empty reviewer card is waiting for its first text. */
    var composing = false
}

data class Reply(val author: String, val body: String)

/**
 * How a comment card hands the reviewer's remark to the live Docent. The host ([DocentPanel]) supplies an
 * implementation that posts a `comment` event through the broker; [onReply] is invoked (on the EDT) with the
 * agent's answer, which the card appends to the thread. A no-op when no agent is driving the review.
 */
fun interface CommentPoster {
    fun post(thread: CommentThread, reviewerText: String, onReply: (String) -> Unit)
}
