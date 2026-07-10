package com.kevingosse.docent.awb

import com.intellij.air.shared.core.thread.AgentThread
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
 * Version notes (262.8665 air.* baseline vs the 263.1445 provider→agent rework):
 *  - `AgentThreadsStateStore` and its state shape are unchanged, EXCEPT the per-thread provider accessor:
 *    262 `AgentThread.provider: AgentThreadProvider` became 263.1445 `agentId: AgentId`. Both are String
 *    value classes, so the getter is *mangled* on both sides (`getProvider-wYjB3lY` → `getAgentId-f6jNaPk`)
 *    and a static call can't span the two — [threadAgentId] resolves it reflectively by prefix + shape.
 *  - The reflected vfile (`AgentThreadViewVirtualFile`): 262 has plain `getProvider()` (boxed
 *    `AgentThreadProvider?`, read via `.toString()` == `.value`); 263.1445 replaced it with a mangled
 *    `getAgentId-…(): String`. NB the 263 vfile also has `getProviderContentConfig()` — a bare
 *    "getProvider*" prefix match would grab that, so [readProviderValue] matches exact/mangled names only.
 *  - Provider icons: registry moved (see [AwbAgentIcons]).
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
                .mapNotNull { t -> threadAgentId(t)?.let { t to it } }
                .filter { (t, provider) -> !t.archived && provider in SUPPORTED_PROVIDER_VALUES }
                .sortedByDescending { (t, _) -> t.updatedAt }
                .forEach { (t, provider) -> if (seen.add(t.id)) result += AgentSessionInfo(t.id, t.title, provider, t.updatedAt, reachable = true, icon = providerIcon(provider)) }
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
     * The tab's provider value. 262.8665..263.1174: the plain `getProvider()` returning a boxed nullable
     * `AgentThreadProvider?`, whose `toString()` is its `.value` (e.g. "claude"). 263.1445+: a mangled
     * `getAgentId-…(): String`. Matched by exact / mangled-prefix name so the 263 vfile's unrelated
     * `getProviderContentConfig()` can't shadow it.
     */
    private fun readProviderValue(vf: VirtualFile): String? = runCatching {
        val getter = vf.javaClass.methods.firstOrNull {
            it.parameterCount == 0 &&
                (it.name == "getProvider" || it.name.startsWith("getProvider-") || it.name.startsWith("getAgentId"))
        } ?: return null
        getter.invoke(vf)?.toString()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /**
     * The stored thread's provider value ("claude"/"codex"). The getter is value-class-mangled on BOTH API
     * generations (`getProvider-wYjB3lY` on 262, `getAgentId-f6jNaPk` on 263.1445) so it must be found
     * reflectively by prefix + shape; either way it returns the raw String.
     */
    private fun threadAgentId(t: AgentThread): String? = runCatching {
        val getter = t.javaClass.methods.firstOrNull {
            it.parameterCount == 0 && it.returnType == String::class.java &&
                (it.name.startsWith("getProvider-") || it.name.startsWith("getAgentId"))
        } ?: return null
        getter.invoke(t) as? String
    }.getOrNull()

    /** The provider's list icon (desaturated variant); see [AwbAgentIcons] for the version split. */
    private fun providerIcon(provider: String): javax.swing.Icon? = AwbAgentIcons.iconFor(provider, monochrome = true)

    private fun samePath(a: String?, b: String): Boolean = a != null && LaunchInjection.normalizePath(a) == LaunchInjection.normalizePath(b)

    private companion object {
        private val LOG = logger<WorkbenchSessionDirectory>()

        /** Provider `.value`s the Docent can drive: Claude (Monitor) and Codex (await). No `.CLAUDE`/`.CODEX`
         *  constants on 263, so we compare on the value string throughout. */
        private val SUPPORTED_PROVIDER_VALUES = setOf(AwbNames.PROVIDER_CLAUDE, AwbNames.PROVIDER_CODEX)
    }
}
