package com.kevingosse.docent.awb

import com.intellij.air.backend.session.api.sessionWorkspaceIdFromBackendPath
import com.intellij.air.frontend.core.AgentLaunchAvailabilityClient
import com.intellij.air.frontend.core.agentCatalogSnapshot
import com.intellij.air.frontend.core.launchAvailabilityModelOrNull
import com.intellij.air.frontend.launch.AgentSessionRouteItem
import com.intellij.air.frontend.launch.agentSessionRouteItems
import com.intellij.air.frontend.launch.preset.agentPickRows
import com.intellij.air.frontend.launch.preset.launchProfileOn
import com.intellij.air.frontend.launch.presets.AgentSessionPresetStateService
import com.intellij.air.frontend.launch.quickStartLabel
import com.intellij.air.frontend.prompt.ui.buildEnabledAgentCatalogMenuModel
import com.intellij.air.shared.core.thread.AgentId
import com.intellij.air.shared.prompt.AgentPromptBackendApi
import com.intellij.air.shared.prompt.AgentPromptInitialMessageRequest
import com.intellij.air.shared.prompt.AgentPromptLaunchProfile
import com.intellij.air.shared.prompt.AgentPromptLaunchRequest
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
 * The picker ([profileOptions]) mirrors Air's own new-session menu, entirely on the frontend side. Since Air
 * 263.5160 that menu is built from **routes + presets** (the old "launch profiles" store was migrated into
 * `AgentSessionPresetStateService` and deleted): the agent catalog snapshot supplies the enabled agents,
 * `agentSessionRouteItems` turns them into per-agent launch routes, `agentPickRows` makes the plain per-agent rows
 * (each carrying a ready `AgentPromptLaunchProfile`), and every user preset for a supported agent becomes one more
 * row via `launchProfileOn` — the same recipe Air's `PresetPickSource` uses. If any of that fails, [launchOptions]
 * degrades to plain per-agent launches — new sessions still start, presets just don't appear.
 *
 * The launch itself goes through the backend RPC surface [AgentPromptBackendApi] (the frontend wrapper around it
 * is Kotlin-`internal`); both `getInstance()` and `launchPrompt(...)` are `suspend` → bridged with
 * [runBlockingMaybeCancellable] on a pooled thread (the platform forbids blocking the EDT, and [startSession] is
 * always a click), with the verdict handed back on the EDT.
 */
internal class WorkbenchAgentLauncher(private val project: Project) : AgentSessionLauncher {

    /** Profiles by option id from the last [launchOptions] build, so [startSession] can launch the exact one. */
    private val profilesById = ConcurrentHashMap<String, AgentPromptLaunchProfile>()

    override fun launchOptions(): List<SessionLaunchOption> {
        val options = runCatching { profileOptions() }
            .onFailure { LOG.warn("Docent: couldn't read the AWB launch routes/presets; using plain agent launches", it) }
            .getOrNull()
        if (!options.isNullOrEmpty()) return options
        // No usable route (pipeline failed, or every supported CLI is unavailable): plain per-agent launches.
        return SUPPORTED_PROVIDER_VALUES.map { agentId ->
            SessionLaunchOption(
                id = agentId,
                label = "New ${agentId.replaceFirstChar { it.titlecase() }} session",
                provider = agentId,
                icon = AwbAgentIcons.iconFor(agentId, monochrome = false),
            )
        }
    }

    /** Air's new-session menu (per-agent routes + user presets), filtered to the agents the Docent can drive. */
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
        val routes: List<AgentSessionRouteItem> = agentSessionRouteItems(
            menuModel,
            availability,
            { true },
            { quickStartLabel(it) },
            catalog.launchTargets,
            catalog.agents,
        ).filter { it.agentId.value in SUPPORTED_PROVIDER_VALUES }
        if (routes.isEmpty()) return emptyList()

        profilesById.clear()
        val options = ArrayList<SessionLaunchOption>()

        // Plain per-agent rows, exactly as Air's AgentRowsPickSource builds them (no last-used / preference
        // overrides: the Docent's picker is a one-shot menu, not the sticky new-session pick).
        for (row in agentPickRows(routes, emptyMap(), emptyMap())) {
            // Air grays un-launchable rows out (CLI missing, route unavailable); we just skip them. A row without
            // a launch profile / route is a non-launch entry (header, "manage…") and is skipped too.
            if (!row.enabled) continue
            val launchProfile = row.launchProfile ?: continue
            val id = "route:" + row.id.value
            profilesById[id] = launchProfile
            options += SessionLaunchOption(
                id = id,
                label = row.text,
                provider = launchProfile.agentId.value,
                icon = row.icon ?: row.routeItem?.icon,
            )
        }

        // User presets (Air's PresetPickSource): a preset pins session parameters + a pre-prompt onto its agent's
        // default route. Presets whose agent has no launchable route here are skipped like Air does.
        val presets = runCatching { service<AgentSessionPresetStateService>().presets() }
            .onFailure { LOG.info("Docent: couldn't read the Air session presets; offering plain agent routes only", it) }
            .getOrDefault(emptyList())
        for (preset in presets) {
            if (preset.agentId !in SUPPORTED_PROVIDER_VALUES) continue
            val route = routes.firstOrNull { it.agentId.value == preset.agentId } ?: continue
            val id = "preset:" + preset.id
            profilesById[id] = launchProfileOn(route, preset.sessionParameters, prompt = preset.prePrompt, name = preset.name)
            options += SessionLaunchOption(
                id = id,
                label = preset.name,
                provider = preset.agentId,
                icon = route.icon,
            )
        }
        return options
    }

    override fun startSession(initialPrompt: String, option: SessionLaunchOption, onResult: (Boolean) -> Unit) {
        val base = project.basePath ?: return onResult(false)
        val app = ApplicationManager.getApplication()
        app.executeOnPooledThread {
            val launched = try {
                // Re-resolve if the UI's option outlived the last build (route/preset ids are stable). Null → the
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

        /** A minimal launch profile carrying just the agent id, for the plain-agent fallback (Air resolves the
         *  agent's default route from it — 263.5160's `resolveAgentThreadLaunchProfile`). */
        private fun minimalProfile(agentId: String): AgentPromptLaunchProfile =
            AgentPromptLaunchProfile(name = "Docent", agentId = AgentId.from(agentId))
    }
}
