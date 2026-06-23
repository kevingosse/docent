package com.kevingosse.docent

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.kevingosse.docent.ui.DocentNavPanel

/**
 * Hosts the Docent navigation rail in a left-anchored tool window (registered in plugin.xml). The review
 * itself opens in the editor (middle) pane — see [com.kevingosse.docent.ui.DocentReviewController].
 */
class DocentToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val nav = DocentNavPanel(project)
        val content = ContentFactory.getInstance().createContent(nav, "", false)
        content.setDisposer(nav)
        toolWindow.contentManager.addContent(content)
    }
}
