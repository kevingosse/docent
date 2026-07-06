package com.kevingosse.docent.awb

import com.intellij.air.prompt.core.AgentPromptInitialMessageRequest
import com.intellij.air.prompt.core.AgentPromptLaunchProfile
import com.intellij.air.prompt.core.AgentPromptLaunchRequest
import com.intellij.air.prompt.core.AgentPromptLaunchers
import com.intellij.air.threads.state.AgentThreadsStateStore
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VirtualFile
import com.kevingosse.docent.DocentReviewService
import com.kevingosse.docent.EventNotifier
import com.kevingosse.docent.ReviewEvent

/**
 * Pushes a reviewer event into an agent's existing AWB thread — used to
 * wake a thread that isn't in a turn (the `REVIEW_RESUMED` / `START_REVIEW` events). Two channels:
 * (1) type into the live open thread-view terminal, (2) the prompt-launcher bridge with `targetThreadId`.
 *
 * See AWB-263-API-MAP.md (local-only, not committed) §B.6/§C for the pre-rework mapping:
 *  - Prompt types moved to `com.intellij.air.prompt.core`; `AgentPromptLauncherBridge.launch(...)` is now
 *    **`suspend`**, so we bridge the non-suspend [notifyAgent] via [runBlockingCancellable] (the platform's
 *    cancellation-aware blocking bridge). NB: the 262 `launch(...)` was already a blocking call made from these
 *    same EDT-invoked paths, so this preserves the existing behavior rather than regressing it (see report).
 *  - `AgentPromptLaunchRequest` is restructured around a single required `launchProfile`; there is no
 *    `provider`/`launchMode` param, so we **synthesize a minimal `AgentPromptLaunchProfile`** carrying the
 *    right `providerId`.
 *  - `AgentSessionsStateStore`→`AgentThreadsStateStore` (pkg `com.intellij.air.threads.state`).
 *  - The editor's `tab` field is GONE (map §C.2) — [terminalTabOf] does a best-effort walk of the content
 *    abstraction (**UNVERIFIED**), wrapped so any failure returns null and delivery degrades to the launcher.
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

            // Primary channel: type into the thread's LIVE open thread-view terminal (only if already built).
            if (sendViaLiveTerminal(threadId, prompt)) return true

            // Fallback for an idle/closed thread in the persisted store: the supported prompt-launcher push.
            pushViaLauncher(service, threadId, prompt)
        } catch (t: Throwable) {
            LOG.warn("Docent: failed to push a review event to the agent; falling back to the poll path", t)
            false
        }
    }

    /**
     * Fallback push via the prompt-launcher bridge. On 263 `launch(...)` is `suspend` and the request needs a
     * `launchProfile`, so we synthesize a minimal profile with the right `providerId` and bridge the suspend
     * call with [runBlockingCancellable]. Returns true if the launch was accepted.
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
        val result = runBlockingCancellable {
            bridge.launch(
                AgentPromptLaunchRequest(
                    launchProfile = minimalProfile(providerIdOf(service.agentProvider)),
                    projectPath = projectPath,
                    initialMessageRequest = AgentPromptInitialMessageRequest(prompt = prompt, projectPath = projectPath),
                    targetThreadId = threadId,
                ),
            )
        }
        if (!result.launched) {
            LOG.info(
                "Docent: push to thread $threadId (project=$projectPath) not delivered (${result.error}); " +
                    "falling back to the poll path. Known store paths: ${storeProjectPaths()}",
            )
        }
        return result.launched
    }

    /**
     * Deliver [prompt] by typing it into the thread-view tab's ALREADY-LIVE terminal. Returns false (caller
     * falls back to the launcher) if the tab isn't open or its terminal isn't reachable. Everything here is
     * `@Internal`, so the terminal tab is reached by reflection.
     */
    private fun sendViaLiveTerminal(threadId: String, prompt: String): Boolean {
        return try {
            val (proj, vf) = findOpenChatTab(threadId) ?: return false
            val tab = terminalTabOf(proj, vf) ?: run {
                LOG.info("Docent: thread tab for $threadId has no reachable live terminal; trying the launcher")
                return false
            }
            // sendText(text, shouldExecute, useBracketedPasteMode) — signature unchanged on 263 (map §C.3).
            val sendText = tab.javaClass.methods.firstOrNull { it.name == "sendText" && it.parameterCount == 3 } ?: return false
            sendText.isAccessible = true
            sendText.invoke(tab, prompt, true, true)
            LOG.info("Docent: delivered to open thread tab for thread $threadId via terminal sendText")
            true
        } catch (t: Throwable) {
            LOG.warn("Docent: terminal sendText to the open thread tab failed for thread $threadId; trying the launcher", t)
            false
        }
    }

    /**
     * The live terminal tab hanging off the open thread-view editor. **UNVERIFIED on 263** (map §C.2/§C.3): the
     * `tab` field is gone; the terminal now lives under a content abstraction (`activeContent`/`contentHost`/
     * `mountedContent`). Best-effort reflective walk: from the editor, for each candidate content field, return
     * the value itself if it already exposes `sendText(3)`, else scan its declared fields one level deep for an
     * object that does (its class name contains "TerminalTab"). Any failure → null (caller uses the launcher).
     * This must be re-verified + hardened on a real 263 build.
     */
    private fun terminalTabOf(proj: Project, vf: VirtualFile): Any? = runCatching {
        val editor = FileEditorManager.getInstance(proj).getEditors(vf)
            .firstOrNull { it.javaClass.name == AwbNames.CHAT_FILE_EDITOR_FQN } ?: return null
        for (fieldName in listOf("activeContent", "mountedContent", "contentHost")) {
            val content = runCatching {
                editor.javaClass.getDeclaredField(fieldName).apply { isAccessible = true }.get(editor)
            }.getOrNull() ?: continue
            if (hasSendText(content)) return content
            // One level deeper: a content object likely holds the terminal tab in a field.
            for (f in content.javaClass.declaredFields) {
                val child = runCatching { f.apply { isAccessible = true }.get(content) }.getOrNull() ?: continue
                if (hasSendText(child)) return child
            }
        }
        null
    }.getOrNull()

    private fun hasSendText(o: Any): Boolean =
        o.javaClass.methods.any { it.name == "sendText" && it.parameterCount == 3 }

    /** The open AgentThreadViewVirtualFile (and its project) whose thread/session id is [threadId]. */
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

    /** The project path the store records for [threadId], scanning projects + worktrees; null when not persisted. */
    private fun ownerProjectPath(threadId: String): String? = runCatching {
        val state = service<AgentThreadsStateStore>().snapshot()
        for (p in state.projects) {
            if (p.threads.any { it.id == threadId && !it.archived }) return@runCatching p.path
            for (w in p.worktrees) if (w.threads.any { it.id == threadId && !it.archived }) return@runCatching w.path
        }
        null
    }.getOrNull()

    /** Project + worktree paths the store knows, for diagnosing a TARGET_THREAD_NOT_FOUND push. */
    private fun storeProjectPaths(): List<String> = runCatching {
        service<AgentThreadsStateStore>().snapshot().projects.flatMap { p -> listOf(p.path) + p.worktrees.map { it.path } }
    }.getOrDefault(emptyList())

    private companion object {
        private val LOG = logger<DocentEventNotifier>()

        /** Map a stored provider value back to a provider id for the synthesized launch profile; default to
         *  Claude when unknown/null (the historical single-provider case). */
        fun providerIdOf(value: String?): String =
            if (value == AwbNames.PROVIDER_CODEX) AwbNames.PROVIDER_CODEX else AwbNames.PROVIDER_CLAUDE

        /** A minimal launch profile carrying just the provider id — enough for a `targetThreadId` push (map §B.6:
         *  the 262 provider/launchMode params are folded into a required `launchProfile`). */
        fun minimalProfile(providerId: String): AgentPromptLaunchProfile =
            AgentPromptLaunchProfile(id = "docent-push", name = "Docent", providerId = providerId)
    }
}
