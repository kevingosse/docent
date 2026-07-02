package com.kevingosse.docent.awb

import com.intellij.agent.workbench.prompt.core.AgentPromptGenerationSettings
import com.intellij.agent.workbench.prompt.core.AgentPromptInitialMessageRequest
import com.intellij.agent.workbench.prompt.core.AgentPromptLaunchProfile
import com.intellij.agent.workbench.prompt.core.AgentPromptLaunchRequest
import com.intellij.agent.workbench.prompt.core.AgentPromptLaunchers
import com.intellij.agent.workbench.sessions.buildAgentSessionLaunchProfileMenuModel
import com.intellij.agent.workbench.sessions.launchProfileActionText
import com.intellij.agent.workbench.sessions.resolveAgentSessionLaunchProfileItems
import com.intellij.agent.workbench.sessions.state.AgentSessionLaunchProfileStateService
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.platform.ai.agent.core.session.AgentSessionLaunchMode
import com.intellij.platform.ai.agent.core.session.AgentSessionProvider
import com.intellij.platform.ai.agent.sessions.core.providers.AgentSessionProviders
import com.kevingosse.docent.AgentSessionLauncher
import com.kevingosse.docent.SessionLaunchOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Starts a brand-new workbench session seeded with an initial prompt (the UI's "Start a new agent session"
 * option in `ui/DocentNavPanel`). We launch with no `targetThreadId`, so the workbench creates a fresh session;
 * the initial prompt tells it to call `docent_resume_review`, and the [DocentLaunchContributor] injects its
 * sessionToken — so the agent arms the review and pins itself as the push target without the UI ever needing
 * the new session's id. This is the reliable path for a not-yet-started session (which has no id to target).
 *
 * [launchOptions] mirrors the workbench's own "new session" menu: its **launch profiles** — the built-in
 * standard/YOLO entries per provider (CLI-availability-gated, exactly as the workbench enables them) plus any
 * user-defined profiles — filtered to the providers the Docent can drive (Claude, Codex; Junie/Pi/OpenCode have
 * no wired MCP path + delivery mode). Launching passes the profile id through [AgentPromptLaunchRequest], so a
 * user profile's model / reasoning settings apply exactly as they would from the workbench itself.
 *
 * Uses the same `AgentPromptLaunchers.find().launch(...)` bridge as [DocentEventNotifier]. `@Internal`/unstable
 * workbench API → lives in the optional, gated `awb/` module; wrapped defensively. The profile pipeline is
 * *compiled* against the installed workbench (loud at build time if it changes); if it fails at runtime we fall
 * back to plain provider launches rather than losing the feature.
 */
internal class WorkbenchAgentLauncher(private val project: Project) : AgentSessionLauncher {

    /** Profiles by id from the last [launchOptions] build, so [startSession] can launch the exact profile. */
    private val profilesById = ConcurrentHashMap<String, AgentPromptLaunchProfile>()

    override fun launchOptions(): List<SessionLaunchOption> {
        val options = runCatching { profileOptions() }
            .onFailure { LOG.warn("Docent: couldn't read the workbench launch profiles; using plain provider launches", it) }
            .getOrNull()
        if (!options.isNullOrEmpty()) return options
        // No usable profile (pipeline failed, or every supported CLI is unavailable): plain provider launches.
        return SUPPORTED_PROVIDER_VALUES.map { provider ->
            SessionLaunchOption(
                id = provider,
                label = "New ${provider.replaceFirstChar { it.titlecase() }} session",
                provider = provider,
                icon = runCatching { AgentSessionProviders.find(providerOf(provider))?.icon }.getOrNull(),
            )
        }
    }

    /** The workbench's effective launch-profile menu (built-ins + user profiles), filtered to supported providers. */
    private fun profileOptions(): List<SessionLaunchOption> {
        val descriptors = AgentSessionProviders.allProviders().filter { it.provider.value in SUPPORTED_PROVIDER_VALUES }
        if (descriptors.isEmpty()) return emptyList()
        val menuModel = buildAgentSessionLaunchProfileMenuModel(descriptors, project)
        val userProfiles = service<AgentSessionLaunchProfileStateService>().getUserLaunchProfiles()
            .filter { it.providerId in SUPPORTED_PROVIDER_VALUES }
        return resolveAgentSessionLaunchProfileItems(menuModel, userProfiles)
            .filter { it.menuItem.isEnabled } // the workbench grays these out (CLI missing); we just skip them
            .map { item ->
                profilesById[item.profile.id] = item.profile
                SessionLaunchOption(
                    id = item.profile.id,
                    label = launchProfileActionText(item),
                    provider = item.profile.providerId,
                    icon = item.menuItem.bridge.icon,
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
            // Re-resolve if the UI's option outlived the last build (profile ids are stable). Null → the
            // fallback plain-provider option; launch without a profile, exactly the pre-profile behavior.
            val profile = profilesById[option.id]
                ?: runCatching { profileOptions() }.getOrNull()?.let { profilesById[option.id] }
            val result = bridge.launch(
                AgentPromptLaunchRequest(
                    provider = providerOf(option.provider),
                    launchProfileId = profile?.id,
                    projectPath = base,
                    launchMode = profile?.launchMode ?: AgentSessionLaunchMode.STANDARD,
                    generationSettings = profile?.generationSettings ?: AgentPromptGenerationSettings.AUTO,
                    initialMessageRequest = AgentPromptInitialMessageRequest(prompt = initialPrompt, projectPath = base),
                    targetThreadId = null, // null → start a NEW session rather than prompt an existing one
                ),
            )
            if (!result.launched) LOG.info("Docent: new-session launch not accepted (${result.error})")
            result.launched
        } catch (t: Throwable) {
            LOG.warn("Docent: failed to start a new agent session", t)
            false
        }
    }

    private companion object {
        private val LOG = logger<WorkbenchAgentLauncher>()

        /** Providers the Docent can drive; must stay in sync with [WorkbenchSessionDirectory.SUPPORTED_PROVIDERS]. */
        private val SUPPORTED_PROVIDER_VALUES = listOf(AgentSessionProvider.CLAUDE.value, AgentSessionProvider.CODEX.value)

        /** AgentSessionProvider is a value class (no public constructor); match the constants we support. */
        private fun providerOf(value: String): AgentSessionProvider =
            if (value == AgentSessionProvider.CODEX.value) AgentSessionProvider.CODEX else AgentSessionProvider.CLAUDE
    }
}
