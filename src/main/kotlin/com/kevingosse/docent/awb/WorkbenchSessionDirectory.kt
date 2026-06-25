package com.kevingosse.docent.awb

import com.intellij.platform.ai.agent.core.session.AgentSessionProvider
import com.intellij.agent.workbench.sessions.state.AgentSessionsStateStore
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.kevingosse.docent.AgentSessionDirectory
import com.kevingosse.docent.AgentSessionInfo

/**
 * Lists the live workbench agent sessions the UI can connect a loaded Trail to (the "Connect agent…" picker in
 * `ui/DocentNavPanel`). Returns the **Claude** sessions for this project (the push transport,
 * [DocentEventNotifier], targets Claude), each carrying the thread id the broker pins via
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

        // 1) Open chat tabs (includes brand-new "New Thread" tabs absent from the persisted store).
        runCatching { result += openTabSessions(base, seen) }
            .onFailure { LOG.warn("Docent: couldn't read open chat tabs; pending sessions may be missing", it) }

        // 2) Persisted store — started threads (incl. closed tabs), most-recent first.
        runCatching {
            service<AgentSessionsStateStore>().snapshot().projects
                .firstOrNull { samePath(it.path, base) }?.threads.orEmpty()
                .filter { !it.archived && it.provider == AgentSessionProvider.CLAUDE }
                .sortedByDescending { it.updatedAt }
                .forEach { t -> if (seen.add(t.id)) result += AgentSessionInfo(t.id, t.title, t.provider.value, t.updatedAt) }
        }.onFailure { LOG.warn("Docent: couldn't read the workbench session store", it) }

        return result
    }

    /**
     * Open chat-tab sessions, read reflectively from each `AgentChatVirtualFile` (the type is `internal`). We scan
     * **every open project**, not just this one, because the workbench can host chats in a dedicated frame (a
     * separate project window); we keep only tabs whose own `projectPath` matches ours.
     */
    private fun openTabSessions(base: String, seen: MutableSet<String>): List<AgentSessionInfo> {
        val claude = AgentSessionProvider.CLAUDE.value
        val out = mutableListOf<AgentSessionInfo>()
        val files = ProjectManager.getInstance().openProjects.flatMap { p ->
            runCatching { FileEditorManager.getInstance(p).openFiles.asList() }.getOrDefault(emptyList())
        }
        for (vf in files) {
            if (vf.javaClass.name != AGENT_CHAT_VFILE_FQN) continue
            runCatching {
                // The provider getter is a value-class accessor named `getProvider-<hash>()` returning the
                // underlying String (e.g. "claude"); the hash is build-specific, so find it by prefix.
                val providerGetter = vf.javaClass.methods.firstOrNull { it.name.startsWith("getProvider") && it.parameterCount == 0 }
                val provider = providerGetter?.invoke(vf) as? String ?: return@runCatching
                if (provider != claude) return@runCatching
                if (!samePath(invokeString(vf, "getProjectPath"), base)) return@runCatching
                val id = invokeString(vf, "getThreadId")?.takeIf { it.isNotBlank() }
                    ?: invokeString(vf, "getSessionId")?.takeIf { it.isNotBlank() }
                    ?: return@runCatching
                if (!seen.add(id)) return@runCatching
                val title = invokeString(vf, "getThreadTitle")?.takeIf { it.isNotBlank() }
                    ?: invokeString(vf, "getBootstrapThreadTitle")?.takeIf { it.isNotBlank() }
                    ?: "New thread"
                val pendingMs = runCatching { vf.javaClass.getMethod("getPendingCreatedAtMs").invoke(vf) as? Long }.getOrNull()
                out += AgentSessionInfo(id, title, provider, pendingMs ?: 0L)
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
    }
}
