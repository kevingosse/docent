package com.kevingosse.docent.awb

import com.intellij.air.thread.view.AgentThreadViewVirtualFile
import com.intellij.air.threads.state.AgentThreadsStateStore
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.kevingosse.docent.AgentSessionDirectory
import com.kevingosse.docent.AgentSessionInfo

/**
 * Lists the live AWB agent threads the UI can connect a loaded Trail to
 * (the "Connect agent…" picker). Two-source merge — open thread-view tabs + the persisted store — filtered to
 * the agents the Docent can drive (Claude, Codex).
 *
 * Both sources are read through their real types now that the seam targets a single AWB generation: the store's
 * `AgentThread.agentId` and the tab's `AgentThreadViewVirtualFile.agentId` are both `AgentId` value classes, so
 * `.value` is the "claude"/"codex" string. Reachability needs the private editor→content→terminal chain, which
 * lives in [AwbTerminalTab]; when it can't be resolved the thread is still listed as reachable if the store
 * knows it, which is the safe degradation (never a false "reachable").
 */
internal class WorkbenchSessionDirectory(private val project: Project) : AgentSessionDirectory {

    override fun listSessions(): List<AgentSessionInfo> {
        val base = project.basePath ?: return emptyList()
        val seen = HashSet<String>()
        val result = mutableListOf<AgentSessionInfo>()

        val storedIds = runCatching { storedThreadIds() }.getOrDefault(emptySet())

        // 1) Open thread-view tabs (includes brand-new tabs absent from the persisted store).
        runCatching { result += openTabSessions(base, seen, storedIds) }
            .onFailure { LOG.warn("Docent: couldn't read open thread tabs; pending sessions may be missing", it) }

        // 2) Persisted store — started threads (incl. closed tabs), most-recent first. Stored ⇒ launcher-reachable.
        runCatching {
            service<AgentThreadsStateStore>().snapshot().projects
                .firstOrNull { samePath(it.path, base) }?.threads.orEmpty()
                .map { t -> t to t.agentId.value }
                .filter { (t, agentId) -> !t.archived && agentId in SUPPORTED_PROVIDER_VALUES }
                .sortedByDescending { (t, _) -> t.updatedAt }
                .forEach { (t, agentId) -> if (seen.add(t.id)) result += AgentSessionInfo(t.id, t.title, agentId, t.updatedAt, reachable = true, icon = providerIcon(agentId)) }
        }.onFailure { LOG.warn("Docent: couldn't read the AWB thread store", it) }

        return result
    }

    /** Thread ids present in the persisted store (all projects + their worktrees, non-archived). */
    private fun storedThreadIds(): Set<String> {
        val ids = HashSet<String>()
        service<AgentThreadsStateStore>().snapshot().projects.forEach { p ->
            p.threads.forEach { if (!it.archived) ids += it.id }
            p.worktrees.forEach { w -> w.threads.forEach { if (!it.archived) ids += it.id } }
        }
        return ids
    }

    /** Open thread-view-tab sessions, one per `AgentThreadViewVirtualFile` scoped to this project. */
    private fun openTabSessions(base: String, seen: MutableSet<String>, storedIds: Set<String>): List<AgentSessionInfo> {
        val out = mutableListOf<AgentSessionInfo>()
        for (p in ProjectManager.getInstance().openProjects) {
            val files = runCatching { FileEditorManager.getInstance(p).openFiles.asList() }.getOrDefault(emptyList())
            for (vf in files) {
                if (vf !is AgentThreadViewVirtualFile) continue
                runCatching {
                    val agentId = vf.agentId?.value ?: return@runCatching
                    if (agentId !in SUPPORTED_PROVIDER_VALUES) return@runCatching
                    if (!samePath(vf.projectPath, base)) return@runCatching
                    val id = vf.threadId?.takeIf { it.isNotBlank() } ?: return@runCatching
                    if (!seen.add(id)) return@runCatching
                    val title = vf.threadTitle?.takeIf { it.isNotBlank() }
                        ?: vf.bootstrapThreadTitle?.takeIf { it.isNotBlank() }
                        ?: "New thread"
                    val reachable = AwbTerminalTab.find(p, vf) != null || id in storedIds
                    out += AgentSessionInfo(id, title, agentId, vf.pendingCreatedAtMs ?: 0L, reachable, icon = providerIcon(agentId))
                }
            }
        }
        return out
    }

    /** The agent's list icon (desaturated variant); see [AwbAgentIcons]. */
    private fun providerIcon(provider: String): javax.swing.Icon? = AwbAgentIcons.iconFor(provider, monochrome = true)

    private fun samePath(a: String?, b: String): Boolean = a != null && LaunchInjection.normalizePath(a) == LaunchInjection.normalizePath(b)

    private companion object {
        private val LOG = logger<WorkbenchSessionDirectory>()

        /** `AgentId.value`s the Docent can drive: Claude (Monitor) and Codex (await). Must stay in sync with
         *  [WorkbenchAgentLauncher.SUPPORTED_PROVIDER_VALUES]. */
        private val SUPPORTED_PROVIDER_VALUES = setOf(AwbNames.PROVIDER_CLAUDE, AwbNames.PROVIDER_CODEX)
    }
}
