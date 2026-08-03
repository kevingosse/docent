package com.kevingosse.docent.awb

/**
 * One-shot self-check of the `@Internal` air.* APIs the Docent stands on (T2 in docs/ASSESSMENT.md).
 * [DocentWorkbenchSetup] runs this once per IDE run and raises a notification listing whatever no longer
 * matches, so an AWB update that renames or relocates one of them surfaces loudly instead of as "mysteriously
 * nothing happens" — which is exactly how the 20260723 layering announced itself.
 *
 * Everything is probed **by FQN through reflection**, deliberately: the seams themselves reference most of these
 * types statically, and a static reference can only fail as a class-load error at the moment it's touched. These
 * probes name the same FQNs (see [AwbNames] and the imports in each seam) and report them as a list.
 *
 * What is checked, and why:
 *  - `AgentThreadLaunchContributor` + its mangled `contribute-…` name: [DocentLaunchContributor] implements this
 *    interface, so a rename means the EP implementation can't load and **agents launch without the Docent
 *    protocol**; a signature change alone means [AbstractMethodError] on every launch. Loudest check here.
 *  - The prompt-launch client: the fallback push channel for a thread whose terminal isn't open.
 *  - The thread-view vfile + editor and the terminal tab: the "type into the live terminal" channel and the
 *    reachability flag in the "Connect agent…" picker ([AwbTerminalTab]).
 */
internal object DocentSeamCheck {

    /** Human-readable descriptions of each seam this build no longer satisfies; empty → all good. */
    fun failures(): List<String> = buildList {
        val cl = DocentSeamCheck::class.java.classLoader

        val contributor = AwbReflect.load(cl, LAUNCH_CONTRIBUTOR_FQN)
        if (contributor == null) {
            add("AgentThreadLaunchContributor moved or is gone (launch injection: agents won't know about the Docent)")
        } else {
            val abstracts = contributor.methods.filter { java.lang.reflect.Modifier.isAbstract(it.modifiers) }
            if (abstracts.none { it.name == CONTRIBUTE_METHOD }) {
                add(
                    "AgentThreadLaunchContributor.contribute changed signature " +
                        "(found ${abstracts.map { it.name }}, expected $CONTRIBUTE_METHOD; launch injection is " +
                        "broken and launches may fail)",
                )
            }
        }

        if (AwbReflect.load(cl, PROMPT_LAUNCH_CLIENT_FQN) == null) {
            add("AgentPromptBackendLaunchClient is gone (can't push events to an idle thread, or start new sessions)")
        }

        val vfile = AwbReflect.load(cl, AwbNames.CHAT_VFILE_FQN)
        if (vfile == null) {
            add("AgentThreadViewVirtualFile is gone (thread listing and event push)")
        } else {
            if (AwbReflect.zeroArg(vfile, "getThreadId") == null) {
                add("AgentThreadViewVirtualFile.getThreadId() is gone (can't identify open thread tabs)")
            }
            if (AwbReflect.zeroArg(vfile, "getProjectPath") == null) {
                add("AgentThreadViewVirtualFile.getProjectPath() is gone (can't scope thread tabs to the project)")
            }
            // The agent-id getter is value-class-mangled (getAgentId-KdGbIeA today), so match by prefix + shape.
            // NB the vfile also has an unrelated getProviderContentConfig(), which a "getProvider*" match would
            // wrongly accept — hence agentId only.
            val agentIdGetters = vfile.methods.filter { it.parameterCount == 0 && it.name.startsWith("getAgentId") }
            if (agentIdGetters.isEmpty()) {
                add("AgentThreadViewVirtualFile has no agentId accessor (can't tell Claude from Codex tabs)")
            }
        }

        // The editor + the private content fields [AwbTerminalTab] walks to reach the live terminal.
        val editor = AwbReflect.load(cl, AwbNames.CHAT_FILE_EDITOR_FQN)
        if (editor == null) {
            add("AgentThreadViewFileEditor is gone (terminal event delivery)")
        } else if (AwbNames.EDITOR_CONTENT_FIELDS.none { name -> editor.declaredFields.any { it.name == name } }) {
            add(
                "AgentThreadViewFileEditor has none of ${AwbNames.EDITOR_CONTENT_FIELDS} " +
                    "(can't reach the live terminal; events fall back to the launch client)",
            )
        }

        // The sendText carrier itself.
        val termTab = AwbReflect.load(cl, AwbNames.TERMINAL_TAB_FQN)
        if (termTab == null) {
            add("AgentThreadViewTerminalTab moved or is gone (can't type events into the live thread terminal)")
        } else if (termTab.methods.none { it.name == "sendText" && it.parameterCount == 3 }) {
            add("AgentThreadViewTerminalTab lost sendText(text, execute, bracketedPaste) (can't type events into the session)")
        }
    }

    /** The launch EP interface [DocentLaunchContributor] implements. */
    private const val LAUNCH_CONTRIBUTOR_FQN = "com.intellij.air.backend.session.launch.AgentThreadLaunchContributor"

    /** Its single abstract method's JVM name — mangled by the `agentId: AgentId` value-class parameter. */
    private const val CONTRIBUTE_METHOD = "contribute-WeWHIlw"

    /** The frontend prompt-launch entry point used by both the push fallback and "start a new session". */
    private const val PROMPT_LAUNCH_CLIENT_FQN = "com.intellij.air.prompt.ui.AgentPromptBackendLaunchClient"
}
