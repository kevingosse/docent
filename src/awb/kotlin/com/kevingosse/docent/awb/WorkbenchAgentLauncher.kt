package com.kevingosse.docent.awb

import com.intellij.air.frontend.core.agentCatalogLaunchTargetsSnapshot
import com.intellij.air.frontend.core.agentCatalogSnapshot
import com.intellij.air.frontend.launch.AgentThreadLaunchProfileStateService
import com.intellij.air.frontend.launch.buildBuiltInLaunchProfiles
import com.intellij.air.frontend.launch.resolveAgentThreadLaunchProfileItems
import com.intellij.air.prompt.ui.buildEnabledAgentCatalogMenuModel
import com.intellij.air.shared.prompt.AgentPromptBackendApi
import com.intellij.air.shared.prompt.AgentPromptInitialMessageRequest
import com.intellij.air.shared.prompt.AgentPromptLaunchProfile
import com.intellij.air.shared.prompt.AgentPromptLaunchRequest
import com.intellij.air.threads.launchProfileActionText
import com.intellij.air.threads.quickStartLabel
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.kevingosse.docent.AgentSessionLauncher
import com.kevingosse.docent.SessionLaunchOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Starts a brand-new AWB thread seeded with an initial prompt (the UI's
 * "Start a new agent session" option).
 *
 * The launch-profile picker ([profileOptions]) mirrors the workbench's own new-thread menu, entirely on the
 * frontend side: the agent catalog snapshot supplies the enabled agents, `AgentThreadLaunchProfileStateService`
 * the user's profiles + ordering, and `resolveAgentThreadLaunchProfileItems` merges them into the same items the
 * tool window shows (label via `launchProfileActionText`, icon already resolved on the item). If any of that
 * fails, [launchOptions] degrades to plain per-agent launches — new sessions still start, custom profiles just
 * don't appear.
 *
 * The launch itself goes through the backend RPC surface [AgentPromptBackendApi] (the frontend wrapper around it
 * is Kotlin-`internal`); both `getInstance()` and `launchPrompt(...)` are `suspend` → bridged with
 * [runBlockingCancellable].
 */
internal class WorkbenchAgentLauncher(private val project: Project) : AgentSessionLauncher {

    /** Profiles by id from the last [launchOptions] build, so [startSession] can launch the exact profile. */
    private val profilesById = ConcurrentHashMap<String, AgentPromptLaunchProfile>()

    override fun launchOptions(): List<SessionLaunchOption> {
        val options = runCatching { profileOptions() }
            .onFailure { LOG.warn("Docent: couldn't read the AWB launch profiles; using plain agent launches", it) }
            .getOrNull()
        if (!options.isNullOrEmpty()) return options
        // No usable profile (pipeline failed, or every supported CLI is unavailable): plain per-agent launches.
        return SUPPORTED_PROVIDER_VALUES.map { agentId ->
            SessionLaunchOption(
                id = agentId,
                label = "New ${agentId.replaceFirstChar { it.titlecase() }} session",
                provider = agentId,
                icon = AwbAgentIcons.iconFor(agentId, monochrome = false),
            )
        }
    }

    /** The AWB launch-profile menu (built-ins + user profiles), filtered to the agents the Docent can drive. */
    private fun profileOptions(): List<SessionLaunchOption> {
        val agents = agentCatalogSnapshot().agents.filter { it.agentId.value in SUPPORTED_PROVIDER_VALUES }
        if (agents.isEmpty()) return emptyList()
        val menuModel = buildEnabledAgentCatalogMenuModel(project, agents)
        val state = service<AgentThreadLaunchProfileStateService>()
        val userProfiles = state.getUserLaunchProfiles().filter { it.effectiveAgentId in SUPPORTED_PROVIDER_VALUES }
        val builtInProfiles = buildBuiltInLaunchProfiles(
            menuModel = menuModel,
            resolveName = { quickStartLabel(it) },
            catalogLaunchTargets = agentCatalogLaunchTargetsSnapshot(),
        )
        return resolveAgentThreadLaunchProfileItems(
            menuModel = menuModel,
            userProfiles = userProfiles,
            builtInProfiles = builtInProfiles,
            agentDescriptors = agents,
            hiddenBuiltInProfileIds = state.getHiddenBuiltInLaunchProfileIds(),
            profileOrder = state.getLaunchProfileOrder(),
        )
            .filter { it.isEnabled } // the workbench grays these out (CLI missing); we just skip them
            .map { item ->
                profilesById[item.profile.id] = item.profile
                SessionLaunchOption(
                    id = item.profile.id,
                    label = launchProfileActionText(item),
                    provider = item.profile.effectiveAgentId,
                    icon = item.icon,
                )
            }
    }

    override fun startSession(initialPrompt: String, option: SessionLaunchOption): Boolean {
        return try {
            val base = project.basePath ?: return false
            // Re-resolve if the UI's option outlived the last build (profile ids are stable). Null → the fallback
            // plain-agent option; synthesize a minimal profile carrying just the agent id.
            val profile = profilesById[option.id]
                ?: runCatching { profileOptions() }.getOrNull()?.let { profilesById[option.id] }
            val launchProfile = profile ?: minimalProfile(providerIdOf(option.provider))
            val result = runBlockingCancellable {
                AgentPromptBackendApi.getInstance().launchPrompt(
                    AgentPromptLaunchRequest(
                        launchProfile = launchProfile,
                        projectPath = base,
                        initialMessageRequest = AgentPromptInitialMessageRequest(prompt = initialPrompt, projectPath = base),
                        targetThreadId = null, // null → start a NEW thread rather than prompt an existing one
                    ),
                )
            }
            if (!result.launched) LOG.info("Docent: new-session launch not accepted (${result.error})")
            result.launched
        } catch (t: Throwable) {
            LOG.warn("Docent: failed to start a new agent session", t)
            false
        }
    }

    private companion object {
        private val LOG = logger<WorkbenchAgentLauncher>()

        /** `AgentId.value`s the Docent can drive; must stay in sync with
         *  [WorkbenchSessionDirectory]'s copy. */
        private val SUPPORTED_PROVIDER_VALUES = listOf(AwbNames.PROVIDER_CLAUDE, AwbNames.PROVIDER_CODEX)

        /** Normalize an option's agent id to a supported one (default Claude). */
        private fun providerIdOf(value: String): String =
            if (value == AwbNames.PROVIDER_CODEX) AwbNames.PROVIDER_CODEX else AwbNames.PROVIDER_CLAUDE

        /** A minimal launch profile carrying just the agent id, for the plain-agent fallback. */
        private fun minimalProfile(agentId: String): AgentPromptLaunchProfile =
            AgentPromptLaunchProfile(id = "docent-new-$agentId", name = "Docent", agentId = agentId)
    }
}
