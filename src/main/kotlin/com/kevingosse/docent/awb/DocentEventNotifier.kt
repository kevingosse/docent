package com.kevingosse.docent.awb

import com.intellij.agent.workbench.common.session.AgentSessionProvider
import com.intellij.agent.workbench.prompt.core.AgentPromptInitialMessageRequest
import com.intellij.agent.workbench.prompt.core.AgentPromptLaunchRequest
import com.intellij.agent.workbench.prompt.core.AgentPromptLaunchers
import com.intellij.agent.workbench.common.session.AgentSessionLaunchMode
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.kevingosse.docent.DocentReviewService
import com.kevingosse.docent.EventNotifier
import com.kevingosse.docent.ReviewEvent

/**
 * Pushes a reviewer action straight into the driving agent's existing workbench thread, so the agent never
 * has to long-poll `docent_await_event` (which Claude Code's ~60s HTTP MCP timeout makes token-expensive —
 * see [DocentReviewService]). Installed onto the service by [DocentLaunchContributor] when a Claude session
 * launches; the [DocentReviewService.agentThreadId] it captured there is the thread we target.
 *
 * Uses the workbench prompt-launcher bridge (`AgentPromptLaunchers.find().launch(...)` with `targetThreadId`),
 * the same "send to an existing session" path the global prompt palette uses. The bridge dispatches the
 * send asynchronously on its own scope, so this is safe to call from the EDT and returns promptly.
 *
 * It's `@Internal`/unstable workbench API → lives in the optional, gated `awb/` module. Every workbench-API
 * touch is wrapped defensively; any failure returns false so the broker falls back to the enqueue/poll path.
 */
internal class DocentEventNotifier(private val project: Project) : EventNotifier {

    override fun notifyAgent(event: ReviewEvent): Boolean {
        return try {
            val service = DocentReviewService.getInstance(project)
            val threadId = service.agentThreadId?.takeIf { it.isNotBlank() } ?: run {
                LOG.info("Docent: no captured agent thread id; falling back to the poll path")
                return false
            }
            val projectPath = service.agentProjectPath?.takeIf { it.isNotBlank() }
                ?: project.basePath
                ?: return false

            val bridge = AgentPromptLaunchers.find() ?: run {
                LOG.info("Docent: no prompt-launcher bridge; falling back to the poll path")
                return false
            }

            val prompt = buildPrompt(event)
            val result = bridge.launch(
                AgentPromptLaunchRequest(
                    provider = AgentSessionProvider.CLAUDE,
                    projectPath = projectPath,
                    launchMode = AgentSessionLaunchMode.STANDARD,
                    initialMessageRequest = AgentPromptInitialMessageRequest(prompt = prompt, projectPath = projectPath),
                    targetThreadId = threadId,
                ),
            )
            if (!result.launched) {
                LOG.info("Docent: push to thread $threadId not delivered (${result.error}); falling back to the poll path")
            }
            result.launched
        } catch (t: Throwable) {
            LOG.warn("Docent: failed to push a review event to the agent; falling back to the poll path", t)
            false
        }
    }

    /** Render an event as a prompt the agent receives as a new user turn in its existing session. */
    private fun buildPrompt(event: ReviewEvent): String = buildString {
        append("[Code Review Docent] ")
        when (event.kind) {
            DocentReviewService.REVIEW_COMPLETED -> {
                appendLine("The reviewer has completed the review. Implement the queued changes now (editing is")
                appendLine("allowed); there is nothing more to wait for after this. Queued changes:")
                appendLine(event.text.ifBlank { "(none)" })
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
    }
}
