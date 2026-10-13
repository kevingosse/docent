package com.kevingosse.docent.awb

import com.intellij.air.frontend.session.terminal.AgentTerminalRuntimeManager
import com.intellij.air.frontend.session.terminal.AgentThreadViewTerminalTab
import com.intellij.air.thread.view.AgentThreadViewVirtualFile
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project

/**
 * The one place that resolves an open thread's **live terminal tab** — used by [DocentEventNotifier] (to type
 * an event into the running agent) and by [WorkbenchSessionDirectory] (to tell a reachable thread from a
 * merely-persisted one).
 *
 * Since Air 263.7029 every live terminal is owned by the app-level [AgentTerminalRuntimeManager], keyed by the
 * thread vfile's `localSessionIdentity` (263.5096–263.6460: a per-project `AgentThreadViewLiveTerminalRegistryService`
 * keyed by `tabKey`, reached reflectively). The manager and its `runtime(...)` / `runtimesOf(...)` are public, so
 * the lookup is static. An ACP (GUI) thread simply has no live terminal — that's a null, not a failure.
 */
internal object AwbTerminalTab {

    /** [vf]'s live terminal tab in [project], or null when the thread has no live terminal. */
    fun find(project: Project, vf: AgentThreadViewVirtualFile): AgentThreadViewTerminalTab? = runCatching {
        val manager = ApplicationManager.getApplication().getServiceIfCreated(AgentTerminalRuntimeManager::class.java)
            ?: return null
        val runtime = vf.localSessionIdentity?.let { manager.runtime(it) }
            ?: manager.runtimesOf(project).firstOrNull { it.file === vf }
        runtime?.tab
    }.getOrNull()
}
