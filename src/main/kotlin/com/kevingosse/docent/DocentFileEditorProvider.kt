package com.kevingosse.docent

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/** Tells the platform to open [DocentVirtualFile] with our [DocentFileEditor] in the main pane. */
class DocentFileEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean = file is DocentVirtualFile
    override fun createEditor(project: Project, file: VirtualFile): FileEditor = DocentFileEditor(project, file)
    override fun getEditorTypeId(): String = "docent-review-editor"
    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
