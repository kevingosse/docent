package com.kevingosse.docent.awb

import com.intellij.air.shared.core.identity.SessionWorkspaceId
import com.intellij.air.shared.core.thread.AgentId
import com.intellij.air.shared.prompt.AgentPromptExistingSessionRequest
import com.intellij.air.shared.prompt.AgentPromptInitialMessageRequest
import com.intellij.air.shared.prompt.AgentPromptLaunchProfile
import com.intellij.air.shared.prompt.AgentPromptLaunchRequest
import java.lang.reflect.Modifier

/**
 * Builds the Air prompt requests the Docent sends, at whatever shape the running Air has.
 *
 * A compiled `AgentPromptInitialMessageRequest(prompt = …)` binds to the exact synthetic defaults constructor
 * `(<every field>, mask, marker)`. Every field Air adds breaks that call with NoSuchMethodError, under the same
 * since-build: 271.2467 added `author` + `authoredText` to the initial message, `background` to the launch request
 * and `environmentConfigDisplayName` to the launch profile. So each request is built from named values by
 * [construct], which finds that constructor reflectively and leaves every field not named at its Kotlin default.
 */
internal object AirRequests {

    fun initialMessage(prompt: String): AgentPromptInitialMessageRequest =
        construct(AgentPromptInitialMessageRequest::class.java, mapOf("prompt" to prompt))

    fun existingSession(
        workspaceId: SessionWorkspaceId,
        projectDirectory: String,
        threadId: String,
        prompt: String,
    ): AgentPromptExistingSessionRequest = construct(
        AgentPromptExistingSessionRequest::class.java,
        mapOf(
            "workspaceId" to workspaceId,
            "projectDirectory" to projectDirectory,
            "threadId" to threadId,
            "initialMessageRequest" to initialMessage(prompt),
        ),
    )

    fun launch(
        workspaceId: SessionWorkspaceId,
        projectDirectory: String,
        launchProfile: AgentPromptLaunchProfile,
        prompt: String,
    ): AgentPromptLaunchRequest = construct(
        AgentPromptLaunchRequest::class.java,
        mapOf(
            "workspaceId" to workspaceId,
            "projectDirectory" to projectDirectory,
            "launchProfile" to launchProfile,
            "initialMessageRequest" to initialMessage(prompt),
            // 263.7351 (IJAI-3626): required, no default. Air's own non-composer launches pass false (agent UI is
            // an opt-in of the new-session composer).
            "agentUi" to false,
        ),
    )

    /** A launch profile carrying just a name and an agent id. `agentId` is a value class, so its field holds the
     *  raw string. */
    fun minimalProfile(name: String, agentId: AgentId): AgentPromptLaunchProfile =
        construct(AgentPromptLaunchProfile::class.java, mapOf("name" to name, "agentId" to agentId.value))

    /**
     * `T(<values by field name>)` with every other field at its default, through the synthetic defaults constructor
     * `(field_0 … field_{n-1}, mask_0 … mask_{m-1}, marker)`, m = ceil(n / 32). A set mask bit means "use the
     * default", so it's set for every field not in [values]. The field names come from the class's instance fields,
     * which Kotlin declares in constructor order; the types are checked against the constructor so a class that
     * breaks that assumption fails here, loudly, instead of getting a value in the wrong slot.
     */
    fun <T> construct(type: Class<T>, values: Map<String, Any?>): T {
        // A value-class field (AgentId) adds a second public constructor ending in the marker: the accessor for the
        // private real one, `(<every field>, marker)`, with no mask. The longest one with an int before the marker
        // is the defaults constructor.
        val ctor = type.constructors
            .filter { c -> c.parameterTypes.let { it.size >= 2 && it.last().name == DEFAULT_MARKER && it[it.size - 2] == Integer.TYPE } }
            .maxByOrNull { it.parameterCount }
            ?: error("${type.name} has no defaults constructor")
        val types = ctor.parameterTypes
        val n = (types.size - 1 downTo 0).firstOrNull { it + (it + 31) / 32 == types.size - 1 }
            ?: error("unexpected ${type.name} defaults constructor: ${types.toList()}")
        val fields = type.declaredFields.filter { !Modifier.isStatic(it.modifiers) }.take(n)
        require(fields.size == n && fields.indices.all { fields[it].type == types[it] }) {
            "${type.name} fields ${fields.map { "${it.name}: ${it.type.name}" }} don't line up with its constructor ${types.toList()}"
        }
        val names = fields.map { it.name }
        values.keys.firstOrNull { it !in names }?.let { error("${type.name} has no field '$it' (has $names)") }

        val args = arrayOfNulls<Any>(types.size)
        val masks = IntArray((n + 31) / 32)
        for (i in 0 until n) {
            if (names[i] in values) {
                args[i] = values[names[i]]
            } else {
                args[i] = zeroOf(types[i])
                masks[i / 32] = masks[i / 32] or (1 shl (i % 32))
            }
        }
        masks.forEachIndexed { k, mask -> args[n + k] = mask }
        return type.cast(ctor.newInstance(*args))
    }

    /** The placeholder for a primitive parameter whose default the mask selects (null for references). */
    fun zeroOf(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Byte.TYPE -> 0.toByte()
        Character.TYPE -> 0.toChar()
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        else -> null
    }

    private const val DEFAULT_MARKER = "kotlin.jvm.internal.DefaultConstructorMarker"
}
