package com.kevingosse.docent.awb

import com.intellij.air.frontend.session.view.tui.AgentThreadViewTerminalTab
import com.intellij.air.thread.view.AgentThreadViewVirtualFile
import com.intellij.openapi.project.Project

/**
 * The one place that resolves an open thread's **live terminal tab** — used by [DocentEventNotifier] (to type
 * an event into the running agent) and by [WorkbenchSessionDirectory] (to tell a reachable thread from a
 * merely-persisted one).
 *
 * Since Air 263.5096 the tab no longer hangs off the file editor's private content fields (the old bounded
 * field walk); every live terminal is owned by a per-project registry service
 * ([AwbNames.TERMINAL_REGISTRY_SERVICE_FQN]), keyed by the thread vfile's `tabKey`. The lookup is
 * `registry.currentEntry(vf.tabKey)?.tab`. The service class and its `currentEntry` are Kotlin-`internal`
 * (the method's JVM name carries a module suffix), so both are reached reflectively — the method by name
 * prefix, which absorbs a module rename; a *type* rename is reported by [DocentSeamCheck]. An ACP (GUI)
 * thread simply has no live terminal — that's a null, not a failure.
 */
internal object AwbTerminalTab {

    /** [vf]'s live terminal tab in [project], or null when the thread has no live terminal. */
    fun find(project: Project, vf: AgentThreadViewVirtualFile): AgentThreadViewTerminalTab? = runCatching {
        val registryClass = Class.forName(AwbNames.TERMINAL_REGISTRY_SERVICE_FQN, false, javaClass.classLoader)
        val registry = project.getService(registryClass) ?: return null
        val currentEntry = registryClass.methods.firstOrNull {
            it.name.startsWith(AwbNames.TERMINAL_REGISTRY_ENTRY_METHOD_PREFIX) &&
                it.parameterCount == 1 && it.parameterTypes[0] == String::class.java
        } ?: return null
        val entry = currentEntry.invoke(registry, vf.tabKey) ?: return null
        entry.javaClass.methods
            .firstOrNull { it.name == AwbNames.TERMINAL_ENTRY_TAB_GETTER && it.parameterCount == 0 }
            ?.invoke(entry) as? AgentThreadViewTerminalTab
    }.getOrNull()
}
