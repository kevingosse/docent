package com.kevingosse.docent.awb

/**
 * One-shot self-check of the `@Internal` air.* APIs the Docent reaches
 * **by reflection** (T2 in docs/ASSESSMENT.md). [DocentWorkbenchSetup] runs this once per IDE run and raises a
 * notification listing whatever no longer matches, so an AWB update that renames/relocates a reflected member
 * surfaces loudly instead of as "mysteriously nothing happens".
 *
 * Reflects the air.* API across both generations we support (262.8665..263.1174 and the 263.1445+
 * provider→agent rework):
 *  - The vfile / editor types live under `com.intellij.air.thread.view.*` ([AwbNames]).
 *  - `AgentThreadViewFileEditor` has **no `tab` field** (content/surface abstraction), so we do NOT probe for
 *    `tab`; probing it would false-alarm.
 *  - The vfile provider getter is `getProvider()` (boxed `AgentThreadProvider?`) on 262 and the mangled
 *    `getAgentId-…(): String` on 263.1445+ (`getProviderContentConfig` deliberately doesn't count).
 *  - `sendText`'s 3-arg signature is unchanged but lives on [AwbNames.TERMINAL_TAB_FQN]; the editor→tab
 *    traversal itself is UNVERIFIED (see [DocentEventNotifier]) — we can only confirm the target method exists.
 *  - `AgentThreadLaunchContributor.contribute` must be one of the two mangled signatures
 *    `DocentLaunchContributor` carries — a third rename would otherwise resurface as an [AbstractMethodError]
 *    that breaks every AWB launch (the 0.5.2-on-263.1445 failure mode), so it gets the loudest check here.
 */
internal object DocentSeamCheck {

    /** Human-readable descriptions of each seam this build no longer satisfies; empty → all good. */
    fun failures(): List<String> = buildList {
        val cl = DocentSeamCheck::class.java.classLoader

        // The launch-contributor EP interface: its single abstract method's mangled name must be one of the
        // two generations DocentLaunchContributor implements, else agents launch WITHOUT the Docent protocol
        // (or worse, the whole launch pipeline fails on our stale registration).
        val contributor = AwbReflect.load(cl, "com.intellij.air.threads.launch.AgentThreadLaunchContributor")
        if (contributor == null) {
            add("AgentThreadLaunchContributor is gone (launch injection: agents won't know about the Docent)")
        } else {
            val known = setOf("contribute-QyV-CsE", "contribute-WeWHIlw")
            val abstracts = contributor.methods.filter { java.lang.reflect.Modifier.isAbstract(it.modifiers) }
            if (abstracts.none { it.name in known }) {
                add(
                    "AgentThreadLaunchContributor.contribute changed signature again " +
                        "(found ${abstracts.map { it.name }}; launch injection is broken and launches may fail)",
                )
            }
        }

        val vfile = AwbReflect.load(cl, AwbNames.CHAT_VFILE_FQN)
        if (vfile == null) {
            add("AgentThreadViewVirtualFile is gone (thread listing and event push)")
        } else {
            if (AwbReflect.zeroArg(vfile, "getThreadId") == null && AwbReflect.zeroArg(vfile, "getSessionId") == null) {
                add("AgentThreadViewVirtualFile has neither getThreadId() nor getSessionId() (can't identify open thread tabs)")
            }
            if (AwbReflect.zeroArg(vfile, "getProjectPath") == null) {
                add("AgentThreadViewVirtualFile.getProjectPath() is gone (can't scope thread tabs to the project)")
            }
            // 262: plain getProvider() (boxed AgentThreadProvider?); 263.1445+: mangled getAgentId-…(): String.
            // Must mirror WorkbenchSessionDirectory.readProviderValue — NOT a bare "getProvider" prefix, which
            // would false-pass on the 263 vfile's unrelated getProviderContentConfig().
            val providerGetters = vfile.methods.filter {
                it.parameterCount == 0 &&
                    (it.name == "getProvider" || it.name.startsWith("getProvider-") || it.name.startsWith("getAgentId"))
            }
            if (providerGetters.isEmpty()) {
                add("AgentThreadViewVirtualFile has no provider/agentId accessor (can't tell Claude from Codex tabs)")
            }
        }

        // The editor type must still load; its `tab` field is INTENTIONALLY gone on 263 (content abstraction),
        // so we don't check for it. The live-terminal delivery path degrades to the launcher push if the
        // (UNVERIFIED) editor→tab traversal fails at runtime.
        if (AwbReflect.load(cl, AwbNames.CHAT_FILE_EDITOR_FQN) == null) {
            add("AgentThreadViewFileEditor is gone (terminal event delivery)")
        }

        // The sendText carrier — signature confirmed on 263, but the class must still exist and expose it.
        val termTab = AwbReflect.load(cl, AwbNames.TERMINAL_TAB_FQN)
        if (termTab == null) {
            add("AgentThreadViewTerminalTab is gone (can't type events into the live thread terminal)")
        } else if (termTab.methods.none { it.name == "sendText" && it.parameterCount == 3 }) {
            add("AgentThreadViewTerminalTab lost sendText(text, execute, bracketedPaste) (can't type events into the session)")
        }
    }
}
