package com.kevingosse.docent.awb

import com.intellij.openapi.project.Project
import com.kevingosse.docent.DeliveryMode
import com.kevingosse.docent.DocentReviewService
import com.kevingosse.docent.EventLog
import com.kevingosse.docent.ReviewEvent

/**
 * Renders a [ReviewEvent] as the prompt text the connected agent receives as a new user turn. Extracted from
 * `DocentEventNotifier` because it touches **no** Agent Workbench type (only the platform-clean
 * [DocentReviewService] / [EventLog] / [ReviewEvent]), so it must exist ONCE and stay identical across both
 * build variants. The per-variant notifier only supplies the delivery mechanics (live terminal / launcher push).
 */
internal object EventPrompt {

    /** Render [event] as a prompt the agent receives as a new user turn in its existing session. */
    fun build(project: Project, event: ReviewEvent): String = buildString {
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
}
