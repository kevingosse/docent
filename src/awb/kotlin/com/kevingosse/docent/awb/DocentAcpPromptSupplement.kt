package com.kevingosse.docent.awb

import com.intellij.air.acp.runtime.AcpPromptSupplement
import com.intellij.air.acp.runtime.AcpPromptSupplementContext
import com.intellij.air.backend.session.runtime.state.AgentThreadsStateStore
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger

/**
 * Delivers the Docent protocol to an Air **ACP** thread — the ACP-surface counterpart of the
 * `--append-system-prompt` / `developer_instructions` injection [DocentLaunchContributor] does on the Terminal
 * surface.
 *
 * Registered on `com.intellij.air.acpPromptSupplement` (gated `docent-awb.xml`). Air composes every outgoing ACP
 * turn — a new session's first turn included — through this EP and appends the returned text to the delivered
 * prompt, wire-only (the transcript never shows it). There is no session-level system-prompt hook on the ACP path
 * (Air doesn't forward `_meta.systemPrompt` to the adapter), so the protocol rides the thread's FIRST turn in full
 * and each later turn carries a one-line reminder — see [AcpInjection.turnSupplement].
 *
 * Only threads of an agent the Docent can drive are supplemented. The provider normally comes from
 * [DocentAcpMcpServerProvider], which saw the session being created; a thread resumed before that seam ran (or in
 * a previous IDE run) is resolved through Air's thread store instead. Unknown → `null` (turn untouched).
 */
internal class DocentAcpPromptSupplement : AcpPromptSupplement {

    override fun supplement(context: AcpPromptSupplementContext): String? {
        return try {
            val threadId = context.threadId.takeIf { it.isNotBlank() } ?: return null
            val provider = AcpInjection.providerOf(threadId) ?: providerFromStore(threadId) ?: return null
            if (AcpInjection.needsIntroduction(threadId)) {
                LOG.info("Docent: ACP $provider thread $threadId — appending the Docent protocol to its first turn")
            }
            AcpInjection.turnSupplement(threadId, provider)
        } catch (t: Throwable) {
            LOG.warn("Docent: failed to compose the ACP prompt supplement; turn goes out unchanged", t)
            null
        }
    }

    /** The turn is on its way to the transport: from now on this thread only gets the reminder. */
    override fun applied(context: AcpPromptSupplementContext) {
        AcpInjection.markIntroduced(context.threadId)
    }

    /** Air's persisted thread store: the agent of [threadId] across every project + worktree, or null. */
    private fun providerFromStore(threadId: String): String? = runCatching {
        val state = service<AgentThreadsStateStore>().snapshot()
        for (p in state.projects) {
            (p.threads.asSequence() + p.worktrees.asSequence().flatMap { it.threads.asSequence() })
                .firstOrNull { it.id == threadId }
                ?.let { thread ->
                    return@runCatching AcpInjection.providerForAcpAgent(thread.agentId.value)
                        ?.also { AcpInjection.rememberThread(threadId, it) }
                }
        }
        null
    }.getOrNull()

    private companion object {
        private val LOG = logger<DocentAcpPromptSupplement>()
    }
}
