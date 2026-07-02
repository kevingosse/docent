package com.kevingosse.docent.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

/**
 * The review-step navigation as *registered* actions (plugin.xml) rather than ad-hoc toolbar buttons:
 * that gives them global keyboard shortcuts (Alt+. / Alt+, — agency is a pillar, keyboards are agency;
 * docs/UI.md §5), makes them remappable in Keymap settings, and lets the diff toolbar
 * ([SectionDiffProcessor]) reuse the same instances so the shortcut shows in the button tooltip.
 *
 * They walk [DocentReviewController]'s flattened review steps (section summary → each file → next
 * section); enabled only while a trail is loaded.
 */
abstract class DocentStepActionBase : DumbAwareAction() {
    protected fun controller(e: AnActionEvent): DocentReviewController? =
        e.project?.let { DocentReviewController.getInstance(it) }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}

class DocentNextStepAction : DocentStepActionBase() {
    override fun actionPerformed(e: AnActionEvent) { controller(e)?.goNext() }
    override fun update(e: AnActionEvent) {
        val c = controller(e)
        e.presentation.isEnabled = c?.trail != null && c.hasNext()
    }
}

class DocentPrevStepAction : DocentStepActionBase() {
    override fun actionPerformed(e: AnActionEvent) { controller(e)?.goPrev() }
    override fun update(e: AnActionEvent) {
        val c = controller(e)
        e.presentation.isEnabled = c?.trail != null && c.hasPrev()
    }
}

class DocentOverviewAction : DocentStepActionBase() {
    override fun actionPerformed(e: AnActionEvent) { controller(e)?.showOverview() }
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = controller(e)?.trail != null
    }
}
