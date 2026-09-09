package com.kevingosse.docent.awb

import com.intellij.air.shared.prompt.AgentPromptBackendApi
import com.intellij.air.shared.prompt.AgentPromptInitialMessageRequest
import com.intellij.air.shared.prompt.AgentPromptLaunchProfile
import com.intellij.air.shared.prompt.AgentPromptLaunchProfileKind
import com.intellij.air.shared.prompt.AgentPromptLaunchRequest
import com.intellij.air.thread.view.AgentThreadViewVirtualFile
import com.intellij.air.backend.session.api.AgentThread
import com.intellij.air.backend.session.api.agentLaunchRouteOrNull
import com.intellij.air.backend.session.api.sessionWorkspaceIdFromBackendPath
import com.intellij.air.backend.session.runtime.model.AgentWorkspaceThreads
import com.intellij.air.backend.session.runtime.state.AgentThreadsStateStore
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.runBlockingMaybeCancellable
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
 *    [runBlockingMaybeCancellable] — same as the pre-layering bridge call. Callers must be off the EDT (the
 *    platform forbids blocking there); [DocentReviewService.pushToAgent] provides the pooled-thread hop.
 *  - `AgentPromptLaunchRequest` is built around a required `launchProfile`. Air resolves it to an exact **launch
 *    route** (agent id + launch target + interaction surface) and then looks the target thread up BY THAT ROUTE —
 *    a profile carrying only an agent id has no route and fails as `PROVIDER_UNAVAILABLE` before the thread is
 *    even looked at. So the profile is synthesized from the target thread's own stored route (the same recipe
 *    Air's code-review follow-up uses); the agent-id-only profile is a last resort when the thread isn't in the
 *    store.
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
        // 263.4825: the request is addressed by workspace id + project directory (was: one projectPath). Prefer
        // the store's own entry for the thread's workspace; otherwise derive the id from the path the same way
        // Air's backend does.
        val owner = ownerEntry(threadId)
        val workspace = owner?.first
        val thread = owner?.second
        val projectPath = workspace?.path
            ?: service.agentProjectPath?.takeIf { it.isNotBlank() }
            ?: project.basePath
            ?: return false
        val profile = thread?.let(::routeProfile)
            ?: minimalProfile(providerIdOf(service.agentProvider)).also {
                LOG.info("Docent: thread $threadId has no stored launch route; pushing with an agent-id-only profile")
            }
        val result = runBlockingMaybeCancellable {
            AgentPromptBackendApi.getInstance().launchPrompt(
                AgentPromptLaunchRequest(
                    workspaceId = workspace?.workspaceId ?: sessionWorkspaceIdFromBackendPath(projectPath),
                    projectDirectory = workspace?.projectDirectory ?: projectPath,
                    launchProfile = profile,
                    initialMessageRequest = AgentPromptInitialMessageRequest(prompt = prompt),
                    targetThreadId = threadId,
                ),
            )
        }
        if (!result.launched) {
            LOG.info(
                "Docent: push to thread $threadId (project=$projectPath, route=${profile.agentId}/" +
                    "${profile.launchTargetId}/${profile.interactionSurfaceId}) not delivered (${result.error}); " +
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

    /** The store's workspace (project or worktree entry) holding [threadId] + the thread itself; null when not
     *  persisted. */
    private fun ownerEntry(threadId: String): Pair<AgentWorkspaceThreads, AgentThread>? = runCatching {
        val state = service<AgentThreadsStateStore>().snapshot()
        fun AgentWorkspaceThreads.live() = threads.firstOrNull { it.id == threadId && !it.archived }
        for (p in state.projects) {
            p.live()?.let { return@runCatching p to it }
            for (w in p.worktrees) w.live()?.let { return@runCatching w to it }
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

        /** A launch profile pinned to [thread]'s own route, so Air's exact-route resolution + target lookup both
         *  land on it (folded ACP threads included: the route carries the ACP launch target + surface). Null when
         *  the thread has no resolvable route (nothing we can address then). */
        fun routeProfile(thread: AgentThread): AgentPromptLaunchProfile? {
            val route = thread.agentLaunchRouteOrNull() ?: return null
            return AgentPromptLaunchProfile(
                id = "docent-push",
                name = "Docent",
                kind = AgentPromptLaunchProfileKind.TEMPORARY,
                agentId = route.agentId.value,
                launchTargetId = route.targetId.value,
                interactionSurfaceId = route.interactionSurfaceId.value,
            )
        }

        /** Last-resort profile carrying just the agent id; Air can't resolve a route from it, so a push with it
         *  is expected to fail — kept so the failure is logged with the store diagnostics rather than skipped. */
        fun minimalProfile(agentId: String): AgentPromptLaunchProfile =
            AgentPromptLaunchProfile(id = "docent-push", name = "Docent", agentId = agentId)
    }
}
