package com.kevingosse.docent.awb

import com.intellij.agent.workbench.common.session.AgentSessionLaunchMode
import com.intellij.agent.workbench.common.session.AgentSessionProvider
import com.intellij.agent.workbench.prompt.core.AgentPromptInitialMessageRequest
import com.intellij.agent.workbench.prompt.core.AgentPromptLaunchRequest
import com.intellij.agent.workbench.prompt.core.AgentPromptLaunchers
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.kevingosse.docent.AgentSessionLauncher

/**
 * Starts a brand-new workbench **Claude** session seeded with an initial prompt (the UI's "Start a new agent
 * session" option in `ui/DocentNavPanel`). We launch with no `targetThreadId`, so the workbench creates a fresh
 * session; the initial prompt tells it to call `docent_resume_review`, and the [DocentLaunchContributor] injects
 * its sessionToken — so the agent arms the review and pins itself as the push target without the UI ever needing
 * the new session's id. This is the reliable path for a not-yet-started session (which has no id to target).
 *
 * Uses the same `AgentPromptLaunchers.find().launch(...)` bridge as [DocentEventNotifier]. `@Internal`/unstable
 * workbench API → lives in the optional, gated `awb/` module; wrapped defensively.
 */
internal class WorkbenchAgentLauncher(private val project: Project) : AgentSessionLauncher {

    override fun startSession(initialPrompt: String): Boolean {
        return try {
            val base = project.basePath ?: return false
            val bridge = AgentPromptLaunchers.find() ?: run {
                LOG.info("Docent: no prompt-launcher bridge; can't start a new session")
                return false
            }
            val result = bridge.launch(
                AgentPromptLaunchRequest(
                    provider = AgentSessionProvider.CLAUDE,
                    projectPath = base,
                    launchMode = AgentSessionLaunchMode.STANDARD,
                    initialMessageRequest = AgentPromptInitialMessageRequest(prompt = initialPrompt, projectPath = base),
                    targetThreadId = null, // null → start a NEW session rather than prompt an existing one
                ),
            )
            if (!result.launched) LOG.info("Docent: new-session launch not accepted (${result.error})")
            result.launched
        } catch (t: Throwable) {
            LOG.warn("Docent: failed to start a new agent session", t)
            false
        }
    }

    private companion object {
        private val LOG = logger<WorkbenchAgentLauncher>()
    }
}
