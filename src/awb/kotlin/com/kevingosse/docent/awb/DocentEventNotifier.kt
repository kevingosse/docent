package com.kevingosse.docent.awb

import com.intellij.air.shared.prompt.AgentPromptBackendApi
import com.intellij.air.shared.prompt.AgentPromptInitialMessageRequest
import com.intellij.air.shared.prompt.AgentPromptLaunchProfile
import com.intellij.air.shared.prompt.AgentPromptLaunchRequest
import com.intellij.air.thread.view.AgentThreadViewVirtualFile
import com.intellij.air.threads.state.AgentThreadsStateStore
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.kevingosse.docent.DocentReviewService
import com.kevingosse.docent.EventNotifier
import com.kevingosse.docent.ReviewEvent

/**
 * Pushes a reviewer event into an agent's existing AWB thread — used to
 * wake a thread that isn't in a turn (the `REVIEW_RESUMED` / `START_REVIEW` events). Two channels:
 * (1) type into the live open thread-view terminal ([AwbTerminalTab]), (2) the backend prompt-launch client
 * with `targetThreadId`.
 *
 * Notes on the AWB surface (see docs/AWB-2026.3-COMPAT.md):
 *  - The launcher-bridge lookup (`AgentPromptLaunchers.find()`) is gone; prompts now go through the backend RPC
 *    surface [AgentPromptBackendApi] (its frontend wrapper is Kotlin-`internal`, this interface is not). Both
 *    `getInstance()` and `launchPrompt(...)` are `suspend`, bridged from this non-suspend [notifyAgent] with
 *    [runBlockingCancellable] — same as the pre-layering bridge call.
 *  - `AgentPromptLaunchRequest` is built around a required `launchProfile`, so we **synthesize a minimal
 *    [AgentPromptLaunchProfile]** carrying just the target `agentId`.
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

            // Fallback for an idle/closed thread in the persisted store: the supported prompt-launch push.
            pushViaLauncher(service, threadId, prompt)
        } catch (t: Throwable) {
            LOG.warn("Docent: failed to push a review event to the agent; falling back to the poll path", t)
            false
        }
    }

    /** Fallback push via the backend prompt-launch API. Returns true if the launch was accepted. */
    private fun pushViaLauncher(service: DocentReviewService, threadId: String, prompt: String): Boolean {
        val projectPath = ownerProjectPath(threadId)
            ?: service.agentProjectPath?.takeIf { it.isNotBlank() }
            ?: project.basePath
            ?: return false
        val result = runBlockingCancellable {
            AgentPromptBackendApi.getInstance().launchPrompt(
                AgentPromptLaunchRequest(
                    launchProfile = minimalProfile(providerIdOf(service.agentProvider)),
                    projectPath = projectPath,
                    initialMessageRequest = AgentPromptInitialMessageRequest(prompt = prompt),
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
     * falls back to the launch client) if the tab isn't open or its terminal isn't built yet.
     */
    private fun sendViaLiveTerminal(threadId: String, prompt: String): Boolean {
        return try {
            val (proj, vf) = findOpenChatTab(threadId) ?: return false
            val tab = AwbTerminalTab.find(proj, vf) ?: run {
                LOG.info("Docent: thread tab for $threadId has no reachable live terminal; trying the launch client")
                return false
            }
            tab.sendText(prompt, true, true)
            LOG.info("Docent: delivered to open thread tab for thread $threadId via terminal sendText")
            true
        } catch (t: Throwable) {
            LOG.warn("Docent: terminal sendText to the open thread tab failed for thread $threadId; trying the launch client", t)
            false
        }
    }

    /** The open `AgentThreadViewVirtualFile` (and its project) whose thread id is [threadId]. */
    private fun findOpenChatTab(threadId: String): Pair<Project, AgentThreadViewVirtualFile>? {
        for (p in ProjectManager.getInstance().openProjects) {
            if (p.isDisposed) continue
            val files = runCatching { FileEditorManager.getInstance(p).openFiles.asList() }.getOrDefault(emptyList())
            for (vf in files) {
                if (vf !is AgentThreadViewVirtualFile) continue
                if (vf.threadId?.takeIf { it.isNotBlank() } == threadId) return p to vf
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

        /** Map a stored agent id back to one the synthesized launch profile can carry; default to Claude when
         *  unknown/null (the historical single-provider case). */
        fun providerIdOf(value: String?): String =
            if (value == AwbNames.PROVIDER_CODEX) AwbNames.PROVIDER_CODEX else AwbNames.PROVIDER_CLAUDE

        /** A minimal launch profile carrying just the agent id — enough for a `targetThreadId` push. */
        fun minimalProfile(agentId: String): AgentPromptLaunchProfile =
            AgentPromptLaunchProfile(id = "docent-push", name = "Docent", agentId = agentId)
    }
}
