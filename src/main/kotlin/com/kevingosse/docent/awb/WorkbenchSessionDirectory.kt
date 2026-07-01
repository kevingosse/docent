package com.kevingosse.docent.awb

import com.intellij.platform.ai.agent.core.session.AgentSessionProvider
import com.intellij.agent.workbench.sessions.state.AgentSessionsStateStore
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VirtualFile
import com.kevingosse.docent.AgentSessionDirectory
import com.kevingosse.docent.AgentSessionInfo

/**
 * Lists the live workbench agent sessions the UI can connect a loaded Trail to (the "Connect agent…" picker in
 * `ui/DocentNavPanel`). Returns the sessions for the providers the Docent can drive ([SUPPORTED_PROVIDERS] —
 * Claude and Codex) in this project, each carrying its provider value and the thread id the broker pins via
 * [com.kevingosse.docent.DocentReviewService.linkAgentSession].
 *
 * Two sources are merged so the list matches what the user sees in the workbench:
 *  1. **Open chat tabs** — every open `AgentChatVirtualFile` (via the platform [FileEditorManager]). This is what
 *     catches a brand-new **"New Thread"** tab: a not-yet-sent session is NOT in the persisted store, but its
 *     editor tab is open, so it shows up here. Its push target is its `threadId`, or its preallocated `sessionId`
 *     while still pending (so the Docent's resume message becomes the session's first message).
 *  2. **The persisted store** ([AgentSessionsStateStore]) — started threads, including ones whose tab is closed.
 *
 * Open tabs come first (what you're looking at), then other persisted sessions most-recent-first; duplicates
 * (a started session that's also an open tab) are kept once, keyed by id.
 *
 * `AgentChatVirtualFile` is `internal` in the workbench, so source #1 reads it **by reflection** (its getters are
 * JVM-public; the Kotlin visibility check is the only barrier) — matched on the class FQN. Each source is wrapped
 * defensively so a failure in one still yields the other.
 */
internal class WorkbenchSessionDirectory(private val project: Project) : AgentSessionDirectory {

    override fun listSessions(): List<AgentSessionInfo> {
        val base = project.basePath ?: return emptyList()
        val seen = HashSet<String>()
        val result = mutableListOf<AgentSessionInfo>()

        // Thread ids the persisted store knows (across projects + worktrees) — these are reachable via the
        // launcher push even with a closed/cold tab, so an open tab that's also stored counts as reachable.
        val storedIds = runCatching { storedThreadIds() }.getOrDefault(emptySet())

        // 1) Open chat tabs (includes brand-new "New Thread" tabs absent from the persisted store).
        runCatching { result += openTabSessions(base, seen, storedIds) }
            .onFailure { LOG.warn("Docent: couldn't read open chat tabs; pending sessions may be missing", it) }

        // 2) Persisted store — started threads (incl. closed tabs), most-recent first. Stored ⇒ launcher-reachable.
        runCatching {
            service<AgentSessionsStateStore>().snapshot().projects
                .firstOrNull { samePath(it.path, base) }?.threads.orEmpty()
                .filter { !it.archived && it.provider in SUPPORTED_PROVIDERS }
                .sortedByDescending { it.updatedAt }
                .forEach { t -> if (seen.add(t.id)) result += AgentSessionInfo(t.id, t.title, t.provider.value, t.updatedAt, reachable = true) }
        }.onFailure { LOG.warn("Docent: couldn't read the workbench session store", it) }

        return result
    }

    /** Thread ids present in the persisted store (all projects + their worktrees, non-archived) — the set the
     *  launcher push can resolve. Mirrors `DocentEventNotifier.ownerProjectPath`'s scan. */
    private fun storedThreadIds(): Set<String> {
        val ids = HashSet<String>()
        service<AgentSessionsStateStore>().snapshot().projects.forEach { p ->
            p.threads.forEach { if (!it.archived) ids += it.id }
            p.worktrees.forEach { w -> w.threads.forEach { if (!it.archived) ids += it.id } }
        }
        return ids
    }

    /** Whether [vf]'s chat-tab terminal is already built (the tab has been activated at least once this IDE run).
     *  Reflected — `AgentChatFileEditor` and its `tab` field are `@Internal`. */
    private fun terminalLive(p: Project, vf: VirtualFile): Boolean = runCatching {
        FileEditorManager.getInstance(p).getEditors(vf)
            .firstOrNull { it.javaClass.name == AGENT_CHAT_FILE_EDITOR_FQN }
            ?.let { ed -> ed.javaClass.getDeclaredField("tab").apply { isAccessible = true }.get(ed) != null }
            ?: false
    }.getOrDefault(false)

    /**
     * Open chat-tab sessions, read reflectively from each `AgentChatVirtualFile` (the type is `internal`). We scan
     * **every open project**, not just this one, because the workbench can host chats in a dedicated frame (a
     * separate project window); we keep only tabs whose own `projectPath` matches ours.
     */
    private fun openTabSessions(base: String, seen: MutableSet<String>, storedIds: Set<String>): List<AgentSessionInfo> {
        val supported = SUPPORTED_PROVIDERS.map { it.value }.toSet()
        val out = mutableListOf<AgentSessionInfo>()
        for (p in ProjectManager.getInstance().openProjects) {
            val files = runCatching { FileEditorManager.getInstance(p).openFiles.asList() }.getOrDefault(emptyList())
            for (vf in files) {
                if (vf.javaClass.name != AGENT_CHAT_VFILE_FQN) continue
                runCatching {
                    // The provider getter is a value-class accessor named `getProvider-<hash>()` returning the
                    // underlying String (e.g. "claude"); the hash is build-specific, so find it by prefix.
                    val providerGetter = vf.javaClass.methods.firstOrNull { it.name.startsWith("getProvider") && it.parameterCount == 0 }
                    val provider = providerGetter?.invoke(vf) as? String ?: return@runCatching
                    if (provider !in supported) return@runCatching
                    if (!samePath(invokeString(vf, "getProjectPath"), base)) return@runCatching
                    val id = invokeString(vf, "getThreadId")?.takeIf { it.isNotBlank() }
                        ?: invokeString(vf, "getSessionId")?.takeIf { it.isNotBlank() }
                        ?: return@runCatching
                    if (!seen.add(id)) return@runCatching
                    val title = invokeString(vf, "getThreadTitle")?.takeIf { it.isNotBlank() }
                        ?: invokeString(vf, "getBootstrapThreadTitle")?.takeIf { it.isNotBlank() }
                        ?: "New thread"
                    val pendingMs = runCatching { vf.javaClass.getMethod("getPendingCreatedAtMs").invoke(vf) as? Long }.getOrNull()
                    // Reachable now if its terminal is already built, or the thread is in the store (launcher push).
                    val reachable = terminalLive(p, vf) || id in storedIds
                    out += AgentSessionInfo(id, title, provider, pendingMs ?: 0L, reachable)
                }
            }
        }
        return out
    }

    private fun invokeString(target: Any, method: String): String? =
        runCatching { target.javaClass.getMethod(method).invoke(target) as? String }.getOrNull()

    private fun samePath(a: String?, b: String): Boolean = a != null && normalizePath(a) == normalizePath(b)

    /** Match the workbench's project path to ours (path separators / trailing slash / case normalized). */
    private fun normalizePath(p: String): String = p.replace('\\', '/').trimEnd('/').lowercase()

    private companion object {
        private val LOG = logger<WorkbenchSessionDirectory>()
        private const val AGENT_CHAT_VFILE_FQN = "com.intellij.agent.workbench.chat.AgentChatVirtualFile"
        private const val AGENT_CHAT_FILE_EDITOR_FQN = "com.intellij.agent.workbench.chat.AgentChatFileEditor"

        /** Providers the Docent can drive today: Claude (Monitor delivery) and Codex (await delivery). Junie /
         *  OpenCode / others aren't wired (no confirmed MCP path + delivery mode) so they're left off the picker. */
        private val SUPPORTED_PROVIDERS = setOf(AgentSessionProvider.CLAUDE, AgentSessionProvider.CODEX)
    }
}
