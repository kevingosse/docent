package com.kevingosse.docent.awb

import com.intellij.air.backend.session.api.sessionWorkspaceIdFromBackendPath
import com.intellij.air.frontend.core.AgentLaunchAvailabilityClient

import com.intellij.air.frontend.core.agentCatalogSnapshot
import com.intellij.air.frontend.core.launchAvailabilityModelOrNull
import com.intellij.air.frontend.launch.AgentThreadLaunchProfileStateService
import com.intellij.air.frontend.launch.resolveAgentThreadLaunchProfileItems
import com.intellij.air.frontend.prompt.ui.buildEnabledAgentCatalogMenuModel
import com.intellij.air.shared.prompt.AgentPromptBackendApi
import com.intellij.air.shared.session.buildBuiltInLaunchProfiles
import com.intellij.air.shared.prompt.AgentPromptInitialMessageRequest
import com.intellij.air.shared.prompt.AgentPromptLaunchProfile
import com.intellij.air.shared.prompt.AgentPromptLaunchRequest

import com.intellij.air.frontend.launch.quickStartLabel
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.runBlockingMaybeCancellable
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
 * [runBlockingMaybeCancellable] on a pooled thread (the platform forbids blocking the EDT, and [startSession] is
 * always a click), with the verdict handed back on the EDT.
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
        // Narrow the catalog to Claude/Codex first, so every model built from it is already scoped to us.
        val catalog = agentCatalogSnapshot().let { snapshot ->
            snapshot.copy(agents = snapshot.agents.filter { it.agentId.value in SUPPORTED_PROVIDER_VALUES })
        }
        if (catalog.agents.isEmpty()) return emptyList()
        // buildEnabledAgentCatalogMenuModel applies the user's per-agent enable setting; the availability model
        // carries CLI reachability (which routes actually work) and is what decides launchability below.
        val menuModel = buildEnabledAgentCatalogMenuModel(project, catalog)
        val availability = project.service<AgentLaunchAvailabilityClient>().state.value
            // The enable-setting is already applied by the menu model above, so this only has to answer
            // "is the agent switched on at all" — routes/CLI reachability come from the state itself.
            .launchAvailabilityModelOrNull(isAgentEnabled = { true })
            ?: return emptyList()
        val state = service<AgentThreadLaunchProfileStateService>()
        val userProfiles = state.getUserLaunchProfiles().filter { it.agentId in SUPPORTED_PROVIDER_VALUES }
        // preferTerminalSurface=false: the built-in Chat route (ACP for Claude/Codex since Air 263.x), exactly what
        // Air's own new-thread menu offers by default. The Docent works on both surfaces, so no need to steer.
        val builtInProfiles = buildBuiltInLaunchProfiles(
            menuModel = menuModel,
            availabilityModel = availability,
            resolveName = { quickStartLabel(it) },
            preferTerminalSurface = false,
            catalogLaunchTargets = catalog.launchTargets,
        )
        return resolveAgentThreadLaunchProfileItems(
            menuModel = menuModel,
            availabilityModel = availability,
            builtInProfiles = builtInProfiles,
            userProfiles = userProfiles,
            deletedBuiltInProfileIds = state.getDeletedBuiltInLaunchProfileIds(),
            profileOrder = state.getLaunchProfileOrder(),
        )
            // The workbench grays un-launchable profiles out (CLI missing, route unavailable); we just skip them.
            .filter { availability.isProfileLaunchable(it.profile) }
            .map { item ->
                profilesById[item.profile.id] = item.profile
                SessionLaunchOption(
                    id = item.profile.id,
                    // Air's own action-text helper went Kotlin-internal in 263.x; the profile name is what it shows.
                    label = item.profile.name,
                    provider = item.profile.agentId,
                    icon = item.icon,
                )
            }
    }

    override fun startSession(initialPrompt: String, option: SessionLaunchOption, onResult: (Boolean) -> Unit) {
        val base = project.basePath ?: return onResult(false)
        val app = ApplicationManager.getApplication()
        app.executeOnPooledThread {
            val launched = try {
                // Re-resolve if the UI's option outlived the last build (profile ids are stable). Null → the
                // fallback plain-agent option; synthesize a minimal profile carrying just the agent id.
                val profile = profilesById[option.id]
                    ?: runCatching { profileOptions() }.getOrNull()?.let { profilesById[option.id] }
                val launchProfile = profile ?: minimalProfile(providerIdOf(option.provider))
                val result = runBlockingMaybeCancellable {
                    AgentPromptBackendApi.getInstance().launchPrompt(
                        AgentPromptLaunchRequest(
                            // 263.4825: addressed by workspace id + project directory (was: one projectPath).
                            workspaceId = sessionWorkspaceIdFromBackendPath(base),
                            projectDirectory = base,
                            launchProfile = launchProfile,
                            initialMessageRequest = AgentPromptInitialMessageRequest(prompt = initialPrompt),
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
            app.invokeLater({ if (!project.isDisposed) onResult(launched) }, ModalityState.any())
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
