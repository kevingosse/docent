package com.kevingosse.docent.awb

import com.intellij.air.frontend.core.agentCatalogSnapshot
import com.intellij.ide.ui.icons.icon
import javax.swing.Icon

/**
 * An agent's list icon, shared by [WorkbenchSessionDirectory] and [WorkbenchAgentLauncher].
 *
 * Read off the frontend agent catalog (`agentCatalogSnapshot()`), whose `AgentDescriptorDto.presentation`
 * carries the icons as serializable `IconId`s — the supported frontend-side view of the backend `AgentDescriptor`
 * EP. (0.6.x reached the EP itself, statically on the pre-layering API and reflectively after; the catalog client
 * makes both unnecessary.) Icons are cosmetic: any failure → null, never a throw.
 */
internal object AwbAgentIcons {

    fun iconFor(provider: String, monochrome: Boolean): Icon? = runCatching {
        val presentation = agentCatalogSnapshot().agents
            .firstOrNull { it.agentId.value == provider }
            ?.presentation
            ?: return null
        (if (monochrome) presentation.monochromeIconId else presentation.iconId)?.icon()
    }.getOrNull()
}
