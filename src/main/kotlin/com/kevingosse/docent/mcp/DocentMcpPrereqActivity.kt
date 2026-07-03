package com.kevingosse.docent.mcp

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.kevingosse.docent.DocentReviewService

/**
 * First-run guard, in two steps at project open. (1) Arm [DocentMcpEndpoint] up front — that pre-warms
 * the private server before the first agent launch and makes [McpPrereq] truthful for the UI surfaces.
 * (2) Only if arming FAILED and the public MCP server is also off, warn with a one-click fix (enabling
 * the public server restores the fallback injection path). Without the warning that failure is fully
 * silent: agents launch fine, work fine, and simply never see the `docent_*` tools, so nothing is ever
 * recorded and the review surface stays empty with no hint why.
 *
 * Registered in `docent-mcp.xml`, so it only runs when the MCP Server plugin is present — the
 * plugin-missing case can't be fixed with a click and is handled by the nav panel's notice instead.
 */
internal class DocentMcpPrereqActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val endpointArmed = runCatching { DocentMcpEndpoint.getInstance().endpoint() }.getOrNull() != null
        // The nav panel may have drawn while the status was still STARTING (or, pre-arming, DISABLED) —
        // now that the attempt settled either way, let it re-check and show the real state.
        DocentReviewService.getInstance(project).onConnectionChanged?.invoke()
        if (endpointArmed) return
        if (McpPrereq.status() != McpPrereqStatus.DISABLED) return
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("Code Review Docent")
            .createNotification(
                "Code Review Docent can't reach coding agents",
                "Docent couldn't start its own MCP endpoint, and the IDE's built-in MCP server (the fallback " +
                    "path) is turned off — no decisions will be captured and reviews can't run. " +
                    "Agent sessions launched before the fix need to be relaunched afterwards.",
                NotificationType.WARNING,
            )
        notification.addAction(NotificationAction.createSimpleExpiring("Turn on the MCP server") {
            if (McpPrereq.enableAndStart()) {
                // The nav panel may be showing its "MCP is off" notice — let it re-check.
                DocentReviewService.getInstance(project).onConnectionChanged?.invoke()
            }
        })
        notification.notify(project)
    }
}
