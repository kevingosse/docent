package com.kevingosse.docent.awb

/**
 * **263 (2026.3) build variant.** One-shot self-check of the `@Internal` 263 APIs the Docent reaches
 * **by reflection** (T2 in docs/ASSESSMENT.md). [DocentWorkbenchSetup] runs this once per IDE run and raises a
 * notification listing whatever no longer matches, so a 263 update that renames/relocates a reflected member
 * surfaces loudly instead of as "mysteriously nothing happens".
 *
 * Ported to AWB-263-API-MAP.md (local-only, not committed) §C — materially restructured, not just renamed:
 *  - The vfile / editor types moved under `com.intellij.air.thread.view.*` ([AwbNames]).
 *  - `AgentThreadViewFileEditor` **no longer has a `tab` field** (content/surface abstraction), so — unlike the
 *    262 twin — we do NOT probe for `tab`. Its absence is EXPECTED on 263; probing it would false-alarm.
 *  - The vfile `provider` getter is now the plain non-mangled `getProvider()` returning a boxed
 *    `AgentThreadProvider?` (262 was a name-mangled value-class accessor returning String).
 *  - `sendText`'s 3-arg signature is unchanged but lives on [AwbNames.TERMINAL_TAB_FQN]; the editor→tab
 *    traversal itself is UNVERIFIED (see [DocentEventNotifier]) — we can only confirm the target method exists.
 */
internal object DocentSeamCheck {

    /** Human-readable descriptions of each seam this 263 build no longer satisfies; empty → all good. */
    fun failures(): List<String> = buildList {
        val cl = DocentSeamCheck::class.java.classLoader

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
            // 263: getProvider() is the plain non-mangled getter returning a boxed AgentThreadProvider? (map §C.3).
            if (vfile.methods.none { it.name.startsWith("getProvider") && it.parameterCount == 0 }) {
                add("AgentThreadViewVirtualFile has no getProvider* accessor (can't tell Claude from Codex tabs)")
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
