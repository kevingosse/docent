package com.kevingosse.docent

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.kevingosse.docent.ui.DocentPanel
import com.kevingosse.docent.ui.DocentReviewController
import java.beans.PropertyChangeListener
import javax.swing.JComponent

/**
 * Hosts the Docent review as a main-pane editor tab. The component is the [DocentPanel] — narration +
 * "discuss this section" + the navigable diff for the selected section. Navigation (the section/file list) lives in
 * the separate left tool window ([com.kevingosse.docent.DocentToolWindowFactory]).
 *
 * [getState]/[setState] expose the current section/file selection as a [DocentEditorState], which is what makes
 * the IDE's global **Back/Forward** (the toolbar arrows, Ctrl+Alt+←/→) navigate *within* the review:
 * [DocentReviewController] records the pre-move place on every navigation, and the platform restores it by
 * calling [setState] here. Without a real state, every recorded place was identical and Back/Forward did nothing.
 */
class DocentFileEditor(private val project: Project, private val file: VirtualFile) : UserDataHolderBase(), FileEditor {

    private val panel = DocentPanel(project)
    private val controller get() = DocentReviewController.getInstance(project)

    override fun getComponent(): JComponent = panel
    override fun getPreferredFocusedComponent(): JComponent = panel
    override fun getName(): String = "Docent Review"
    override fun getFile(): VirtualFile = file

    override fun getState(level: FileEditorStateLevel): FileEditorState =
        DocentEditorState(controller.currentSectionIndex, controller.currentFileIndex)

    override fun setState(state: FileEditorState) {
        if (state is DocentEditorState) controller.restoreSelection(state.sectionIndex, state.fileIndex)
    }

    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = true
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun dispose() = panel.dispose()
}

/**
 * The review's navigation state for IDE Back/Forward: which section (−1 = the overview) and which file within it
 * (−1 = the section summary). A plain data class so [com.intellij.openapi.fileEditor.ex.IdeDocumentHistory]'s
 * `isSame` (which compares states with `equals`) treats distinct selections as distinct places and identical
 * ones as one — no merge logic needed.
 */
data class DocentEditorState(val sectionIndex: Int, val fileIndex: Int) : FileEditorState {
    override fun canBeMergedWith(otherState: FileEditorState, level: FileEditorStateLevel): Boolean = false
}
