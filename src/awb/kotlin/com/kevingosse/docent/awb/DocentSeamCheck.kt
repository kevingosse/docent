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
 *  - The thread-view vfile, the live-terminal registry and the terminal tab: the "type into the live terminal"
 *    channel and the reachability flag in the "Connect agent…" picker ([AwbTerminalTab]).
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
            add("AgentPromptBackendApi is gone (can't push events to an idle thread, or start new sessions)")
        }
        // The request the seam constructs statically: 263.4825 re-keyed it from `projectPath` to
        // `workspaceId: SessionWorkspaceId` + `projectDirectory` — a silent NoSuchMethodError inside the push
        // otherwise, which the notifier's catch-all would report as a bland "not delivered".
        val launchRequest = AwbReflect.load(cl, PROMPT_LAUNCH_REQUEST_FQN)
        if (launchRequest == null) {
            add("AgentPromptLaunchRequest is gone (can't start new sessions)")
        } else if (LAUNCH_REQUEST_FIELDS.any { name -> launchRequest.declaredFields.none { it.name == name } }) {
            add(
                "AgentPromptLaunchRequest changed shape (has ${launchRequest.declaredFields.map { it.name }}, " +
                    "expected $LAUNCH_REQUEST_FIELDS; new-session launch will fail)",
            )
        }
        // 263.5160: prompting an EXISTING thread is its own call + request (was: launchPrompt + targetThreadId).
        val existingRequest = AwbReflect.load(cl, PROMPT_EXISTING_REQUEST_FQN)
        if (existingRequest == null) {
            add("AgentPromptExistingSessionRequest is gone (can't push events to an idle thread)")
        } else if (EXISTING_REQUEST_FIELDS.any { name -> existingRequest.declaredFields.none { it.name == name } }) {
            add(
                "AgentPromptExistingSessionRequest changed shape (has ${existingRequest.declaredFields.map { it.name }}, " +
                    "expected $EXISTING_REQUEST_FIELDS; event push will fail)",
            )
        }
        if (AwbReflect.load(cl, WORKSPACE_IDS_FQN)?.methods?.none { it.name == WORKSPACE_ID_FROM_PATH } != false) {
            add("sessionWorkspaceIdFromBackendPath is gone (can't address a launch request by workspace)")
        }

        // The ACP-surface seams (the default Chat route for Claude/Codex since Air 263.x). Their EP interfaces are
        // implemented statically by DocentAcpMcpServerProvider / DocentAcpPromptSupplement, so a rename means the
        // EP implementation can't load and ACP threads get neither the docent MCP entry nor the protocol.
        val mcpProvider = AwbReflect.load(cl, ACP_MCP_PROVIDER_FQN)
        if (mcpProvider == null) {
            add("AcpMcpServerProvider moved or is gone (ACP threads won't get the docent MCP tools)")
        } else if (mcpProvider.methods.none {
                java.lang.reflect.Modifier.isAbstract(it.modifiers) && it.name == ACP_MCP_PROVIDER_METHOD &&
                    it.parameterCount == ACP_MCP_PROVIDER_ARITY
            }
        ) {
            // 263.5712 grew the method by an McpCapabilities parameter; a name-only probe let that slip past, so the
            // arity (Kotlin params + the suspend Continuation) is part of the check now.
            add(
                "AcpMcpServerProvider.getMcpServers changed signature (found " +
                    "${mcpProvider.methods.filter { java.lang.reflect.Modifier.isAbstract(it.modifiers) }.map { "${it.name}/${it.parameterCount}" }}; " +
                    "ACP threads won't get the docent MCP tools)",
            )
        }
        // The provider reads the thread id off the SessionRef as localSessionId.value (263.5096 — before that,
        // a direct `threadId`); a re-shape breaks thread bookkeeping inside the provider's catch-all.
        val sessionRef = AwbReflect.load(cl, SESSION_REF_FQN)
        if (sessionRef != null && AwbReflect.zeroArg(sessionRef, "getLocalSessionId") == null) {
            add("SessionRef lost localSessionId (ACP threads can't be identified; docent MCP still contributed)")
        }

        val supplement = AwbReflect.load(cl, ACP_PROMPT_SUPPLEMENT_FQN)
        if (supplement == null) {
            add("AcpPromptSupplement moved or is gone (ACP threads won't receive the Docent protocol)")
        } else if (supplement.methods.none { java.lang.reflect.Modifier.isAbstract(it.modifiers) && it.name == ACP_PROMPT_SUPPLEMENT_METHOD }) {
            add("AcpPromptSupplement.supplement changed signature (ACP threads won't receive the Docent protocol)")
        }

        if (AwbReflect.load(cl, THREADS_STATE_STORE_FQN) == null) {
            add("AgentThreadsStateStore moved or is gone (persisted thread listing and ACP agent lookup)")
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
            if (AwbReflect.zeroArg(vfile, "getTabKey") == null) {
                add("AgentThreadViewVirtualFile.getTabKey() is gone (can't look the live terminal up in the registry)")
            }
            // The agent-id getter is value-class-mangled (getAgentId-KdGbIeA today), so match by prefix + shape.
            // NB the vfile also has an unrelated getProviderContentConfig(), which a "getProvider*" match would
            // wrongly accept — hence agentId only.
            val agentIdGetters = vfile.methods.filter { it.parameterCount == 0 && it.name.startsWith("getAgentId") }
            if (agentIdGetters.isEmpty()) {
                add("AgentThreadViewVirtualFile has no agentId accessor (can't tell Claude from Codex tabs)")
            }
        }

        // The live-terminal registry [AwbTerminalTab] queries (263.5096+: replaces the old walk over the file
        // editor's private content fields).
        val registry = AwbReflect.load(cl, AwbNames.TERMINAL_REGISTRY_SERVICE_FQN)
        if (registry == null) {
            add(
                "AgentThreadViewLiveTerminalRegistryService moved or is gone " +
                    "(can't reach the live terminal; events fall back to the launch client)",
            )
        } else {
            val currentEntry = registry.methods.firstOrNull {
                it.name.startsWith(AwbNames.TERMINAL_REGISTRY_ENTRY_METHOD_PREFIX) &&
                    it.parameterCount == 1 && it.parameterTypes[0] == String::class.java
            }
            if (currentEntry == null) {
                add(
                    "AgentThreadViewLiveTerminalRegistryService lost currentEntry(tabKey) " +
                        "(can't reach the live terminal; events fall back to the launch client)",
                )
            } else if (currentEntry.returnType.methods.none {
                    it.name == AwbNames.TERMINAL_ENTRY_TAB_GETTER && it.parameterCount == 0
                }
            ) {
                add(
                    "AgentThreadViewLiveTerminalEntry lost its tab accessor " +
                        "(can't reach the live terminal; events fall back to the launch client)",
                )
            }
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

    /** The prompt-launch RPC surface used by both the push fallback and "start a new session". */
    private const val PROMPT_LAUNCH_CLIENT_FQN = "com.intellij.air.shared.prompt.AgentPromptBackendApi"

    /** Its new-session request, and the fields the seam passes (263.5160 shape: `targetThreadId` is gone). */
    private const val PROMPT_LAUNCH_REQUEST_FQN = "com.intellij.air.shared.prompt.AgentPromptLaunchRequest"
    private val LAUNCH_REQUEST_FIELDS = listOf("workspaceId", "projectDirectory", "launchProfile", "initialMessageRequest")

    /** Its existing-thread request (`promptExistingSession`), and the fields the push passes. */
    private const val PROMPT_EXISTING_REQUEST_FQN = "com.intellij.air.shared.prompt.AgentPromptExistingSessionRequest"
    private val EXISTING_REQUEST_FIELDS = listOf("workspaceId", "projectDirectory", "threadId", "initialMessageRequest")

    /** `SessionWorkspaceIds.kt` top-level helpers; the seam derives a workspace id from a path with it. */
    private const val WORKSPACE_IDS_FQN = "com.intellij.air.backend.session.api.SessionWorkspaceIdsKt"
    private const val WORKSPACE_ID_FROM_PATH = "sessionWorkspaceIdFromBackendPath"

    /** The ACP EP interfaces [DocentAcpMcpServerProvider] / [DocentAcpPromptSupplement] implement (not value-class
     *  mangled: `SessionRef` / `AcpAgentId` are ordinary types). */
    private const val ACP_MCP_PROVIDER_FQN = "com.intellij.air.acp.AcpMcpServerProvider"
    private const val ACP_MCP_PROVIDER_METHOD = "getMcpServers"
    /** 263.5712: (projectDir, sessionScope, sessionRef, agentId, mcpCapabilities) + the suspend Continuation. */
    private const val ACP_MCP_PROVIDER_ARITY = 6
    private const val ACP_PROMPT_SUPPLEMENT_FQN = "com.intellij.air.acp.runtime.AcpPromptSupplement"
    private const val ACP_PROMPT_SUPPLEMENT_METHOD = "supplement"

    /** The ACP session handle passed to the MCP provider; the thread id hides in its localSessionId. */
    private const val SESSION_REF_FQN = "com.intellij.air.backend.session.api.SessionRef"

    /** Air's persisted thread store (moved from `air.threads.state` in 263.x). */
    private const val THREADS_STATE_STORE_FQN = "com.intellij.air.backend.session.runtime.state.AgentThreadsStateStore"
}
