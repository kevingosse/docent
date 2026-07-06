package com.kevingosse.docent.awb

import com.intellij.air.prompt.core.AgentPromptInitialMessageRequest
import com.intellij.air.prompt.core.AgentPromptLaunchProfile
import com.intellij.air.prompt.core.AgentPromptLaunchRequest
import com.intellij.air.prompt.core.AgentPromptLaunchers
import com.intellij.air.shared.core.thread.AgentThreadProvider
import com.intellij.air.threads.buildAgentThreadLaunchProfileMenuModel
import com.intellij.air.threads.launchProfileActionText
import com.intellij.air.threads.resolveAgentThreadLaunchProfileItems
import com.intellij.air.threads.core.providers.AgentThreadProviders
import com.intellij.air.threads.state.AgentThreadLaunchProfileStateService
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
 * See AWB-263-API-MAP.md (local-only, not committed) §B.4/§B.6/§G:
 *  - Menu pipeline renamed + moved to `com.intellij.air.threads`
 *    (`buildAgentThreadLaunchProfileMenuModel` / `resolveAgentThreadLaunchProfileItems` / `launchProfileActionText`).
 *  - `AgentThreadLaunchProfileStateService` moved to `com.intellij.air.threads.state`.
 *  - Icons relocated: `descriptor.presentation.icon`; the menu item exposes a convenience `item.icon`.
 *  - `AgentPromptLaunchRequest` is restructured around a single required `launchProfile`; `AgentPromptGenerationSettings`
 *    and `AgentPromptLaunchProfile.generationSettings` are GONE (model options are profile fields now), so the
 *    generationSettings line is dropped. For the plain-provider fallback we synthesize a minimal profile.
 *  - `AgentPromptLauncherBridge.launch(...)` is now `suspend` → bridged with [runBlockingCancellable].
 */
internal class WorkbenchAgentLauncher(private val project: Project) : AgentSessionLauncher {

    /** Profiles by id from the last [launchOptions] build, so [startSession] can launch the exact profile. */
    private val profilesById = ConcurrentHashMap<String, AgentPromptLaunchProfile>()

    override fun launchOptions(): List<SessionLaunchOption> {
        val options = runCatching { profileOptions() }
            .onFailure { LOG.warn("Docent: couldn't read the AWB launch profiles; using plain provider launches", it) }
            .getOrNull()
        if (!options.isNullOrEmpty()) return options
        // No usable profile (pipeline failed, or every supported CLI is unavailable): plain provider launches.
        return SUPPORTED_PROVIDER_VALUES.map { provider ->
            SessionLaunchOption(
                id = provider,
                label = "New ${provider.replaceFirstChar { it.titlecase() }} session",
                provider = provider,
                icon = runCatching { AgentThreadProviders.find(AgentThreadProvider.from(provider))?.presentation?.icon }.getOrNull(),
            )
        }
    }

    /** The AWB launch-profile menu (built-ins + user profiles), filtered to supported providers. */
    private fun profileOptions(): List<SessionLaunchOption> {
        val descriptors = AgentThreadProviders.allProviders().filter { it.provider.value in SUPPORTED_PROVIDER_VALUES }
        if (descriptors.isEmpty()) return emptyList()
        val menuModel = buildAgentThreadLaunchProfileMenuModel(descriptors, project)
        val userProfiles = service<AgentThreadLaunchProfileStateService>().getUserLaunchProfiles()
            .filter { it.providerId in SUPPORTED_PROVIDER_VALUES }
        return resolveAgentThreadLaunchProfileItems(menuModel, userProfiles)
            .filter { it.menuItem.isEnabled } // the workbench grays these out (CLI missing); we just skip them
            .map { item ->
                profilesById[item.profile.id] = item.profile
                SessionLaunchOption(
                    id = item.profile.id,
                    label = launchProfileActionText(item),
                    provider = item.profile.providerId,
                    icon = item.icon, // 263: convenience field on the wrapper (icons relocated off the bridge)
                )
            }
    }

    override fun startSession(initialPrompt: String, option: SessionLaunchOption): Boolean {
        return try {
            val base = project.basePath ?: return false
            val bridge = AgentPromptLaunchers.find() ?: run {
                LOG.info("Docent: no prompt-launcher bridge; can't start a new session")
                return false
            }
            // Re-resolve if the UI's option outlived the last build (profile ids are stable). Null → the fallback
            // plain-provider option; synthesize a minimal profile carrying just the provider id (map §B.6).
            val profile = profilesById[option.id]
                ?: runCatching { profileOptions() }.getOrNull()?.let { profilesById[option.id] }
            val launchProfile = profile ?: minimalProfile(providerIdOf(option.provider))
            val result = runBlockingCancellable {
                bridge.launch(
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

        /** Provider `.value`s the Docent can drive; must stay in sync with
         *  [WorkbenchSessionDirectory.SUPPORTED_PROVIDER_VALUES]. */
        private val SUPPORTED_PROVIDER_VALUES = listOf(AwbNames.PROVIDER_CLAUDE, AwbNames.PROVIDER_CODEX)

        /** Normalize an option's provider value to a supported provider id (default Claude). */
        private fun providerIdOf(value: String): String =
            if (value == AwbNames.PROVIDER_CODEX) AwbNames.PROVIDER_CODEX else AwbNames.PROVIDER_CLAUDE

        /** A minimal launch profile carrying just the provider id, for the plain-provider fallback (map §B.6). */
        private fun minimalProfile(providerId: String): AgentPromptLaunchProfile =
            AgentPromptLaunchProfile(id = "docent-new-$providerId", name = "Docent", providerId = providerId)
    }
}
