package com.kevingosse.docent.awb

import com.intellij.agent.workbench.prompt.core.AgentPromptInitialMessageRequest
import com.intellij.agent.workbench.prompt.core.AgentPromptLaunchRequest
import com.intellij.agent.workbench.prompt.core.AgentPromptLaunchers
import com.intellij.agent.workbench.sessions.state.AgentSessionsStateStore
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ai.agent.core.session.AgentSessionLaunchMode
import com.intellij.platform.ai.agent.core.session.AgentSessionProvider
import com.kevingosse.docent.DocentReviewService
import com.kevingosse.docent.EventNotifier
import com.kevingosse.docent.ReviewEvent

/**
 * **262 build variant.** Pushes a reviewer event straight into an agent's existing workbench thread — used to
 * wake a session that isn't in a turn: the `REVIEW_RESUMED` event from "Connect agent…" and the `START_REVIEW`
 * event from the on-demand review trigger. Installed onto the service by [LaunchInjection.registerPushTarget]
 * / [DocentWorkbenchSetup]; the [DocentReviewService.agentThreadId] captured at link time is the thread we target.
 *
 * Two delivery channels, tried in order ([notifyAgent]):
 *  1. **Live open chat-tab terminal** ([sendViaLiveTerminal]) — type the prompt into the session's terminal.
 *     Only works when the tab's terminal is already built (activated at least once this IDE run); cold tabs
 *     are reported unreachable. This is the only channel that works when the persisted store is empty.
 *  2. **Prompt-launcher bridge** ([AgentPromptLaunchers] with `targetThreadId`) — the supported "send to an
 *     existing session" API, but resolves the target from the persisted store, so it fails
 *     `TARGET_THREAD_NOT_FOUND` for .slnx solutions whose store has no threads.
 *
 * It's `@Internal`/unstable workbench API → lives in the optional, gated `awb/` module, and channel 1 reaches
 * the editor's terminal by reflection. Every workbench touch is wrapped defensively; any failure returns false.
 */
internal class DocentEventNotifier(private val project: Project) : EventNotifier {

    override fun notifyAgent(event: ReviewEvent): Boolean {
        return try {
            val service = DocentReviewService.getInstance(project)
            val threadId = service.agentThreadId?.takeIf { it.isNotBlank() } ?: run {
                LOG.info("Docent: no captured agent thread id; falling back to the poll path")
                return false
            }
            val prompt = EventPrompt.build(project, event)

            // Primary channel: type the prompt into the session's LIVE open chat-tab terminal. Only if it's already
            // built — we don't activate a cold tab and type into a booting terminal (brittle; can type without
            // submitting). Cold tabs are reported unreachable so the UI tells the user to activate them first.
            if (sendViaLiveTerminal(threadId, prompt)) return true

            // Fallback for an idle/closed session in the persisted store: the supported prompt-launcher push.
            pushViaLauncher(service, threadId, prompt)
        } catch (t: Throwable) {
            LOG.warn("Docent: failed to push a review event to the agent; falling back to the poll path", t)
            false
        }
    }

    /**
     * Fallback for a session with no reachable open-tab terminal: the supported prompt-launcher push. The
     * workbench resolves the target by finding the thread UNDER the request's project path (case/separator-
     * sensitive), and a thread may live under the dedicated chat-frame project, so pass the path the store
     * records for it. Returns true if the launch was accepted.
     */
    private fun pushViaLauncher(service: DocentReviewService, threadId: String, prompt: String): Boolean {
        val projectPath = ownerProjectPath(threadId)
            ?: service.agentProjectPath?.takeIf { it.isNotBlank() }
            ?: project.basePath
            ?: return false
        val bridge = AgentPromptLaunchers.find() ?: run {
            LOG.info("Docent: no prompt-launcher bridge; falling back to the poll path")
            return false
        }
        val result = bridge.launch(
            AgentPromptLaunchRequest(
                provider = providerOf(service.agentProvider),
                projectPath = projectPath,
                launchMode = AgentSessionLaunchMode.STANDARD,
                initialMessageRequest = AgentPromptInitialMessageRequest(prompt = prompt, projectPath = projectPath),
                targetThreadId = threadId,
            ),
        )
        if (!result.launched) {
            LOG.info(
                "Docent: push to thread $threadId (project=$projectPath) not delivered (${result.error}); " +
                    "falling back to the poll path. Known store paths: ${storeProjectPaths()}",
            )
        }
        return result.launched
    }

    /**
     * Deliver [prompt] by typing it into the chat tab's ALREADY-LIVE terminal — the same mechanism the workbench's
     * own file-drop / initial-message dispatch use (`AgentChatFileEditor.tab.sendText`). Returns false (caller
     * falls back to the launcher) if the tab isn't open or its terminal isn't built yet. Everything here is
     * `@Internal`, so the terminal tab is reached by reflection.
     */
    private fun sendViaLiveTerminal(threadId: String, prompt: String): Boolean {
        return try {
            val (proj, vf) = findOpenChatTab(threadId) ?: return false
            val tab = terminalTabOf(proj, vf) ?: run {
                LOG.info("Docent: chat tab for $threadId has no live terminal (not activated this run); trying the launcher")
                return false
            }
            // sendText(text, shouldExecute, useBracketedPasteMode) — execute it; bracketed-paste keeps multi-line
            // intact. setAccessible bypasses the language check: the method is public but its declaring class
            // (ToolWindowAgentChatTerminalTab) is `internal`, so a plain invoke throws IllegalAccessException.
            val sendText = tab.javaClass.methods.firstOrNull { it.name == "sendText" && it.parameterCount == 3 } ?: return false
            sendText.isAccessible = true
            sendText.invoke(tab, prompt, true, true)
            LOG.info("Docent: delivered to open chat tab for thread $threadId via terminal sendText")
            true
        } catch (t: Throwable) {
            LOG.warn("Docent: terminal sendText to the open chat tab failed for thread $threadId; trying the launcher", t)
            false
        }
    }

    /** The [AwbNames.CHAT_FILE_EDITOR_FQN]'s lazily-created terminal `tab` (reflected), or null if it isn't built
     *  yet (the tab was never activated this IDE run). */
    private fun terminalTabOf(proj: Project, vf: VirtualFile): Any? = runCatching {
        val editor = FileEditorManager.getInstance(proj).getEditors(vf)
            .firstOrNull { it.javaClass.name == AwbNames.CHAT_FILE_EDITOR_FQN } ?: return null
        editor.javaClass.getDeclaredField("tab").apply { isAccessible = true }.get(editor)
    }.getOrNull()

    /** The open AgentChatVirtualFile (and its project) whose thread/session id is [threadId], across all open
     *  projects (the workbench can host chats in a dedicated frame). Read reflectively — the type is `@Internal`. */
    private fun findOpenChatTab(threadId: String): Pair<Project, VirtualFile>? {
        for (p in ProjectManager.getInstance().openProjects) {
            if (p.isDisposed) continue
            val files = runCatching { FileEditorManager.getInstance(p).openFiles.asList() }.getOrDefault(emptyList())
            for (vf in files) {
                if (vf.javaClass.name != AwbNames.CHAT_VFILE_FQN) continue
                val id = AwbReflect.invokeString(vf, "getThreadId")?.takeIf { it.isNotBlank() }
                    ?: AwbReflect.invokeString(vf, "getSessionId")?.takeIf { it.isNotBlank() }
                if (id == threadId) return p to vf
            }
        }
        return null
    }

    /**
     * The project path the workbench's session store records for [threadId] — the exact string its prompt
     * launcher matches against, scanning both projects and their worktrees. Returns null when the thread isn't
     * persisted (so the caller falls back to our path).
     */
    private fun ownerProjectPath(threadId: String): String? = runCatching {
        val state = service<AgentSessionsStateStore>().snapshot()
        for (p in state.projects) {
            if (p.threads.any { it.id == threadId && !it.archived }) return@runCatching p.path
            for (w in p.worktrees) if (w.threads.any { it.id == threadId && !it.archived }) return@runCatching w.path
        }
        null
    }.getOrNull()

    /** Project + worktree paths the store knows, for diagnosing a TARGET_THREAD_NOT_FOUND push. */
    private fun storeProjectPaths(): List<String> = runCatching {
        service<AgentSessionsStateStore>().snapshot().projects.flatMap { p -> listOf(p.path) + p.worktrees.map { it.path } }
    }.getOrDefault(emptyList())

    private companion object {
        private val LOG = logger<DocentEventNotifier>()

        /** Map a stored provider value (`AgentSessionProvider.value`) back to the provider for the launch request;
         *  default to Claude when unknown/null. `AgentSessionProvider` is a value class (no `entries`/`values()`),
         *  so match against the constants we support. */
        fun providerOf(value: String?): AgentSessionProvider =
            if (value == AgentSessionProvider.CODEX.value) AgentSessionProvider.CODEX else AgentSessionProvider.CLAUDE
    }
}
