package com.kevingosse.docent.awb

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.kevingosse.docent.DocentReviewService

/**
 * Installs the Docent's workbench seams onto the per-project [DocentReviewService] at project open, so the UI
 * always has them whenever the Agent Workbench is present:
 *  - [WorkbenchSessionDirectory] — lets the "Connect agent…" picker list live workbench sessions;
 *  - [DocentEventNotifier] — lets the review PUSH events into the driving agent's existing thread;
 *  - the agent project path the notifier passes back to the workbench.
 *
 * [DocentLaunchContributor] also (re)installs these on every launch — installing here too covers a session the
 * user picks that didn't launch this IDE run. All per-project state, so re-setting is safe.
 *
 * We deliberately do NOT enable the workbench's `agent.workbench.mcp.use.direct.http` registry key. It defaults
 * to false, which means a workbench-launched agent reaches the IDE's MCP server through the user's `.mcp.json`
 * (the ij-proxy **stdio** path) — empirically the working path here, and the one NOT subject to Claude Code's
 * ~60s HTTP first-byte timeout that makes `docent_await_event` burn tokens (stdio is exempt). None of the
 * Docent's features need direct-HTTP; the push transport ([DocentEventNotifier]) and the session directory are
 * independent of the MCP transport.
 */
class DocentWorkbenchSetup : ProjectActivity {
    override suspend fun execute(project: Project) {
        runCatching {
            val service = DocentReviewService.getInstance(project)
            service.sessionDirectory = WorkbenchSessionDirectory(project)
            service.sessionLauncher = WorkbenchAgentLauncher(project)
            service.eventNotifier = DocentEventNotifier(project)
            service.agentProjectPath = project.basePath
        }
    }
}
