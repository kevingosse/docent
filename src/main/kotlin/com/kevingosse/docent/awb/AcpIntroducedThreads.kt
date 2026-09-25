package com.kevingosse.docent.awb

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import java.util.concurrent.ConcurrentHashMap

/**
 * Which Air **ACP** threads already received the full Docent protocol — remembered **across IDE runs**.
 *
 * The ACP surface has no per-session system prompt, so [AcpInjection] rides the protocol on a thread's first
 * turn and leaves every later turn untouched. That "afterwards" used to be an in-memory set: every IDE
 * restart forgot it, so the first turn after **resuming** an old thread got the whole protocol again, as if the
 * thread were new. The protocol is already in that thread's transcript (it went out with its real first turn),
 * so re-sending it is pure noise. Persisting the set makes "first turn" mean the thread's first turn ever, not
 * its first turn since the IDE started.
 *
 * Kept bounded: the newest [MAX_REMEMBERED] ids win. A thread evicted after a thousand newer ones would get the
 * protocol once more on resume — harmless, and it keeps the state file from growing forever. Machine-local
 * (`RoamingType.DISABLED`): thread ids are per-machine Air state.
 *
 * Falls back to a process-lifetime set when no application is running (unit tests, tooling).
 */
@Service(Service.Level.APP)
@State(
    name = "CodeReviewDocentAcpThreads",
    storages = [Storage("code-review-docent-acp-threads.xml", roamingType = RoamingType.DISABLED)],
)
internal class AcpIntroducedThreads : PersistentStateComponent<AcpIntroducedThreads.State> {

    class State {
        /** Insertion-ordered (oldest first) so eviction drops the least recent. */
        var threadIds: MutableList<String> = mutableListOf()
    }

    private val ids: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val order = ArrayDeque<String>()

    fun contains(threadId: String): Boolean = threadId in ids

    fun add(threadId: String) {
        if (threadId.isBlank() || !ids.add(threadId)) return
        synchronized(order) {
            order.addLast(threadId)
            while (order.size > MAX_REMEMBERED) ids.remove(order.removeFirst())
        }
    }

    override fun getState(): State = State().also { s ->
        synchronized(order) { s.threadIds = order.toMutableList() }
    }

    override fun loadState(state: State) {
        synchronized(order) {
            order.clear()
            ids.clear()
            for (id in state.threadIds) if (id.isNotBlank() && ids.add(id)) order.addLast(id)
        }
    }

    companion object {
        const val MAX_REMEMBERED: Int = 1000

        private val fallback: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /** The persistent store, or an in-memory stand-in when no IDE application is running. */
        private fun store(): AcpIntroducedThreads? =
            if (ApplicationManager.getApplication() == null) null else runCatching { service<AcpIntroducedThreads>() }.getOrNull()

        fun isIntroduced(threadId: String): Boolean = store()?.contains(threadId) ?: (threadId in fallback)

        fun markIntroduced(threadId: String) {
            val s = store()
            if (s != null) s.add(threadId) else fallback += threadId
        }
    }
}
