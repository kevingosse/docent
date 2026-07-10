package com.kevingosse.docent.awb

import com.intellij.air.shared.core.thread.AgentThreadProvider
import com.intellij.air.threads.core.providers.AgentThreadProviders
import javax.swing.Icon

/**
 * Version-adaptive lookup of a provider's list icon, shared by [WorkbenchSessionDirectory] and
 * [WorkbenchAgentLauncher].
 *
 * AWB 263.1445 deleted the provider-descriptor registry the 262 API exposed
 * (`AgentThreadProviders`/`AgentThreadProviderDescriptor`) in favor of an EP-backed *agent* registry
 * (`com.intellij.air.threads.core.agents.AgentDescriptor`, keyed by the `AgentId` value class, presentation
 * on `AgentPresentation.icon`/`monochromeIcon` public fields). We compile against 262, so the 262 path is
 * static and the 263 path reaches the new registry's EP reflectively (via the private `AgentsKt.AGENT_EP`
 * ExtensionPointName — there is no public accessor). Icons are cosmetic: any failure → null, never a throw.
 */
internal object AwbAgentIcons {

    fun iconFor(provider: String, monochrome: Boolean): Icon? =
        runCatching { descriptor262(provider, monochrome) }.getOrNull()
            ?: runCatching { registry263(provider, monochrome) }.getOrNull()

    /** 262.8665..263.1174: the static provider-descriptor registry. Throws NoClassDefFoundError on 263.1445+. */
    private fun descriptor262(provider: String, monochrome: Boolean): Icon? {
        val presentation = AgentThreadProviders.find(AgentThreadProvider.from(provider))?.presentation ?: return null
        return if (monochrome) presentation.monochromeIcon else presentation.icon
    }

    /** 263.1445+: the `AgentDescriptor` EP, reached through `AgentsKt`'s private `AGENT_EP` field. */
    private fun registry263(provider: String, monochrome: Boolean): Icon? {
        val cl = AwbAgentIcons::class.java.classLoader
        val agentsKt = AwbReflect.load(cl, "com.intellij.air.threads.core.agents.AgentsKt") ?: return null
        val ep = agentsKt.getDeclaredField("AGENT_EP").apply { isAccessible = true }.get(null) ?: return null
        val descriptors = ep.javaClass.getMethod("getExtensionList").invoke(ep) as? List<*> ?: return null
        val descriptor = descriptors.filterNotNull().firstOrNull { d ->
            // getAgentId is value-class-mangled (getAgentId-f6jNaPk today); match by prefix + shape.
            val getter = d.javaClass.methods.firstOrNull {
                it.parameterCount == 0 && it.returnType == String::class.java && it.name.startsWith("getAgentId")
            }
            getter?.invoke(d) == provider
        } ?: return null
        val presentation = descriptor.javaClass.getMethod("getPresentation").invoke(descriptor) ?: return null
        return presentation.javaClass.getField(if (monochrome) "monochromeIcon" else "icon").get(presentation) as? Icon
    }
}
