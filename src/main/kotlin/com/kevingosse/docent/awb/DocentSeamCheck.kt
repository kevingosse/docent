package com.kevingosse.docent.awb

/**
 * One-shot self-check of every `@Internal` workbench API the Docent reaches **by reflection** (T2 in
 * docs/ASSESSMENT.md). Compiled references break loudly at class-load; these reflective seams don't — an
 * Agent Workbench update that renames a class, getter, or field turns into "mysteriously nothing happens"
 * (session listing empty, events silently undelivered). [DocentWorkbenchSetup] runs this once per IDE run
 * and raises a notification listing whatever no longer matches.
 *
 * Kept in lockstep with the reflection sites: [WorkbenchSessionDirectory] / [DocentEventNotifier] (the chat
 * virtual file's getters, the editor's `tab` field, the terminal tab's `sendText`).
 */
internal object DocentSeamCheck {

    private const val AGENT_CHAT_VFILE_FQN = "com.intellij.agent.workbench.chat.AgentChatVirtualFile"
    private const val AGENT_CHAT_FILE_EDITOR_FQN = "com.intellij.agent.workbench.chat.AgentChatFileEditor"

    /** Human-readable descriptions of each seam this workbench build no longer satisfies; empty → all good. */
    fun failures(): List<String> = buildList {
        // Our module classloader sees the workbench plugin's classes (we compile against them), so a plain
        // forName (no init) is exactly what the FQN-matched reflection sites will resolve at use time.
        val cl = DocentSeamCheck::class.java.classLoader

        val vfile = load(cl, AGENT_CHAT_VFILE_FQN)
        if (vfile == null) {
            add("AgentChatVirtualFile is gone (session listing and event push)")
        } else {
            if (zeroArg(vfile, "getThreadId") == null && zeroArg(vfile, "getSessionId") == null) {
                add("AgentChatVirtualFile has neither getThreadId() nor getSessionId() (can't identify open chat tabs)")
            }
            if (zeroArg(vfile, "getProjectPath") == null) {
                add("AgentChatVirtualFile.getProjectPath() is gone (can't scope chat tabs to the project)")
            }
            if (vfile.methods.none { it.name.startsWith("getProvider") && it.parameterCount == 0 }) {
                add("AgentChatVirtualFile has no getProvider* accessor (can't tell Claude from Codex tabs)")
            }
        }

        val editor = load(cl, AGENT_CHAT_FILE_EDITOR_FQN)
        if (editor == null) {
            add("AgentChatFileEditor is gone (terminal event delivery)")
        } else {
            val tab = runCatching { editor.getDeclaredField("tab") }.getOrNull()
            if (tab == null) {
                add("AgentChatFileEditor.tab is gone (can't reach the chat terminal)")
            } else if (tab.type != Any::class.java &&
                tab.type.methods.none { it.name == "sendText" && it.parameterCount == 3 }
            ) {
                add("the chat terminal tab (${tab.type.simpleName}) lost sendText(text, execute, bracketedPaste) (can't type events into the session)")
            }
        }
    }

    private fun load(cl: ClassLoader, fqn: String): Class<*>? =
        runCatching { Class.forName(fqn, false, cl) }.getOrNull()

    private fun zeroArg(c: Class<*>, name: String) = runCatching { c.getMethod(name) }.getOrNull()
}
