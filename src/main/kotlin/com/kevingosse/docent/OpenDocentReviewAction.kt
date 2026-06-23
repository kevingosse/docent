package com.kevingosse.docent

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.kevingosse.docent.ui.DocentReviewController

/** Opens the Docent navigation tool window (left) and the review tab in the editor (middle) pane. */
class OpenDocentReviewAction : AnAction(), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        DocentReviewController.getInstance(project).openReview()
    }

    companion object {
        private val KEY = Key.create<DocentVirtualFile>("codereview.docent.reviewFile")

        /** One review file per project, so re-invoking focuses the existing tab instead of duplicating it. */
        fun getOrCreateFile(project: Project): DocentVirtualFile =
            project.getUserData(KEY) ?: DocentVirtualFile().also { project.putUserData(KEY, it) }
    }
}
