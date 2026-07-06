package com.kevingosse.docent.awb

import com.intellij.air.shared.core.thread.AgentThreadProvider
import com.intellij.air.threads.core.providers.AgentThreadProviders
import com.intellij.air.threads.state.AgentThreadsStateStore
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VirtualFile
import com.kevingosse.docent.AgentSessionDirectory
import com.kevingosse.docent.AgentSessionInfo

/**
 * Lists the live AWB agent threads the UI can connect a loaded Trail to
 * (the "Connect agent…" picker). Two-source merge — open thread-view tabs (reflected) +
 * the persisted store — filtered to the providers the Docent can drive (Claude, Codex).
 *
 * See AWB-263-API-MAP.md (local-only, not committed) §B.1/§B.3/§B.5/§C for the pre-rework mapping:
 *  - `AgentSessionProvider`→`AgentThreadProvider` (no `.CLAUDE`/`.CODEX`; built via `from("claude"|"codex")`).
 *  - `AgentSessionProviders`→`AgentThreadProviders`; icons relocated to `descriptor.presentation.monochromeIcon`.
 *  - `AgentSessionsStateStore`→`AgentThreadsStateStore` (pkg `com.intellij.air.threads.state`); state shape 1:1.
 *  - The reflected vfile moved to `AgentThreadViewVirtualFile`; its `provider` getter is now a **boxed nullable
 *    `AgentThreadProvider?`** — the 262 `as? String` cast would yield null, so we read `.toString()` (== `.value`).
 *  - The editor's `tab` field is GONE — `terminalLive()` is reworked around the content abstraction (UNVERIFIED,
 *    see below) and degrades to "reachable only if in the store" on any failure.
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
                .filter { !it.archived && it.provider.value in SUPPORTED_PROVIDER_VALUES }
                .sortedByDescending { it.updatedAt }
                .forEach { t -> if (seen.add(t.id)) result += AgentSessionInfo(t.id, t.title, t.provider.value, t.updatedAt, reachable = true, icon = providerIcon(t.provider.value)) }
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

    /**
     * Whether [vf]'s thread-view terminal is already built. **UNVERIFIED on 263** (map §C.2): the editor's `tab`
     * field is gone; the terminal now hangs off a content/surface abstraction (`activeContent`/`contentHost`/
     * `mountedContent`) whose exact field is not confirmed. Best-effort: treat the tab as live if the editor
     * exposes a non-null `activeContent` (or `mountedContent`). Any reflection failure → false, so the tab is
     * reported reachable only when it's also in the store — a safe degradation (never a false "reachable").
     */
    private fun terminalLive(p: Project, vf: VirtualFile): Boolean = runCatching {
        val editor = FileEditorManager.getInstance(p).getEditors(vf)
            .firstOrNull { it.javaClass.name == AwbNames.CHAT_FILE_EDITOR_FQN } ?: return false
        val fieldNames = listOf("activeContent", "mountedContent")
        fieldNames.any { name ->
            runCatching { editor.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(editor) != null }
                .getOrDefault(false)
        }
    }.getOrDefault(false)

    /** Open thread-view-tab sessions, read reflectively from each `AgentThreadViewVirtualFile`. */
    private fun openTabSessions(base: String, seen: MutableSet<String>, storedIds: Set<String>): List<AgentSessionInfo> {
        val out = mutableListOf<AgentSessionInfo>()
        for (p in ProjectManager.getInstance().openProjects) {
            val files = runCatching { FileEditorManager.getInstance(p).openFiles.asList() }.getOrDefault(emptyList())
            for (vf in files) {
                if (vf.javaClass.name != AwbNames.CHAT_VFILE_FQN) continue
                runCatching {
                    val provider = readProviderValue(vf) ?: return@runCatching
                    if (provider !in SUPPORTED_PROVIDER_VALUES) return@runCatching
                    if (!samePath(AwbReflect.invokeString(vf, "getProjectPath"), base)) return@runCatching
                    val id = AwbReflect.invokeString(vf, "getThreadId")?.takeIf { it.isNotBlank() }
                        ?: AwbReflect.invokeString(vf, "getSessionId")?.takeIf { it.isNotBlank() }
                        ?: return@runCatching
                    if (!seen.add(id)) return@runCatching
                    val title = AwbReflect.invokeString(vf, "getThreadTitle")?.takeIf { it.isNotBlank() }
                        ?: AwbReflect.invokeString(vf, "getBootstrapThreadTitle")?.takeIf { it.isNotBlank() }
                        ?: "New thread"
                    val pendingMs = runCatching { vf.javaClass.getMethod("getPendingCreatedAtMs").invoke(vf) as? Long }.getOrNull()
                    val reachable = terminalLive(p, vf) || id in storedIds
                    out += AgentSessionInfo(id, title, provider, pendingMs ?: 0L, reachable, icon = providerIcon(provider))
                }
            }
        }
        return out
    }

    /**
     * The tab's provider value. On 263 the vfile `provider` getter is the plain non-mangled `getProvider()`
     * returning a **boxed nullable `AgentThreadProvider?`** (map §C.3), whose `toString()` is its `.value`
     * (e.g. "claude"). The 262 `as? String` cast would return null here, so we go through `toString()`.
     */
    private fun readProviderValue(vf: VirtualFile): String? = runCatching {
        val getter = vf.javaClass.methods.firstOrNull { it.name.startsWith("getProvider") && it.parameterCount == 0 } ?: return null
        getter.invoke(vf)?.toString()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** The provider's list icon (desaturated variant). 263: icons live under `descriptor.presentation` (map §B.3). */
    private fun providerIcon(provider: String): javax.swing.Icon? = runCatching {
        AgentThreadProviders.find(AgentThreadProvider.from(provider))?.presentation?.monochromeIcon
    }.getOrNull()

    private fun samePath(a: String?, b: String): Boolean = a != null && LaunchInjection.normalizePath(a) == LaunchInjection.normalizePath(b)

    private companion object {
        private val LOG = logger<WorkbenchSessionDirectory>()

        /** Provider `.value`s the Docent can drive: Claude (Monitor) and Codex (await). No `.CLAUDE`/`.CODEX`
         *  constants on 263, so we compare on the value string throughout. */
        private val SUPPORTED_PROVIDER_VALUES = setOf(AwbNames.PROVIDER_CLAUDE, AwbNames.PROVIDER_CODEX)
    }
}
