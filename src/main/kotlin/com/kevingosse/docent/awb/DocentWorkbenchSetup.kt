package com.kevingosse.docent.awb

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.kevingosse.docent.DocentReviewService
import java.util.concurrent.atomic.AtomicBoolean

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
 * The workbench APIs these seams stand on are `@Internal`, some reached by reflection — a workbench update can
 * break them without any compile-time signal. So failures here are LOUD (T2 in docs/ASSESSMENT.md): if seam
 * installation throws we notify instead of swallowing, and once per IDE run [DocentSeamCheck] verifies every
 * reflective touchpoint and reports what a new workbench build broke.
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
        try {
            val service = DocentReviewService.getInstance(project)
            service.sessionDirectory = WorkbenchSessionDirectory(project)
            service.sessionLauncher = WorkbenchAgentLauncher(project)
            service.eventNotifier = DocentEventNotifier(project)
            service.agentProjectPath = project.basePath
            // The nav panel may have built its no-trail surface before these seams existed (post-restart race),
            // showing "Agent Workbench isn't available". Nudge it to rebuild now that the session list works.
            service.onConnectionChanged?.invoke()
        } catch (t: Throwable) {
            LOG.warn("Docent: workbench seams failed to install", t)
            notify(
                project,
                "Docent can't integrate with the Agent Workbench",
                "Installing the workbench integration failed (${t.javaClass.simpleName}: ${t.message}). " +
                    "“Connect agent…” and live reviews won't work — an Agent Workbench update " +
                    "likely changed its API. Check idea.log and update the Docent plugin.",
                NotificationType.ERROR,
            )
            return
        }
        verifySeamsOnce(project)
    }

    /** Run [DocentSeamCheck] once per IDE run and report loudly what this workbench build broke. */
    private fun verifySeamsOnce(project: Project) {
        if (!seamsChecked.compareAndSet(false, true)) return
        val broken = runCatching { DocentSeamCheck.failures() }
            .getOrElse { listOf("the self-check itself failed: ${it.javaClass.simpleName}: ${it.message}") }
        if (broken.isEmpty()) {
            LOG.info("Docent: workbench reflective seams verified OK")
            return
        }
        LOG.warn("Docent: workbench reflective seams broken: $broken")
        notify(
            project,
            "Docent may not reach Agent Workbench sessions",
            "This Agent Workbench build changed internal APIs the Docent relies on: " +
                broken.joinToString("; ") + ". Session listing or event delivery may fail until the " +
                "Docent plugin is updated for it.",
            NotificationType.WARNING,
        )
    }

    private fun notify(project: Project, title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(title, content, type)
            .notify(project)
    }

    private companion object {
        private val LOG = logger<DocentWorkbenchSetup>()
        private const val NOTIFICATION_GROUP = "Code Review Docent"
        private val seamsChecked = AtomicBoolean(false)
    }
}
