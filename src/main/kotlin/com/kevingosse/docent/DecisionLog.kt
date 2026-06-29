package com.kevingosse.docent

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Per-project log of the decisions the coding agent recorded *while it worked* — the "trace during" half
 * of the authoring side (docs/DESIGN.md §7). The authoring counterpart to [DocentReviewService]: where that
 * brokers the live *review*, this accumulates the captured *why* that the review will later present.
 *
 * The point (DESIGN §7): the Trail is synthesized from this log of things that **actually happened**, not
 * from the agent's memory at the end — which is what structurally resists confabulation. So the log is the
 * source of truth for synthesis, surfaced back to the agent by `docent_change_summary` and consumed (and
 * cleared) by `docent_finalize_trail`.
 *
 * Capture is *calibrated*, like triage (DESIGN §4): record only the non-reconstructable moments — a choice
 * between alternatives, a constraint hit, an assumption baked in, a surprise, a verification run — never a
 * blow-by-blow of the diff. That discipline lives in the tool descriptions and the authoring guideline, not
 * here; this just stores what the agent decided was worth recording.
 */
@Service(Service.Level.PROJECT)
class DecisionLog(private val project: Project) {

    private val decisions = CopyOnWriteArrayList<RecordedDecision>()
    private val idGen = AtomicInteger(0)

    /** Called (off-EDT) whenever the log changes, so a future capture view can refresh. */
    @Volatile
    var onUpdated: (() -> Unit)? = null

    init {
        // The log survives IDE restarts (the agent may record decisions, then you restart before starting the
        // review). It's persisted to disk and reloaded here so the "Start review" surface still shows the
        // pending work — the on-disk copy is consumed (deleted) by docent_finalize_trail / clear().
        load()
    }

    /** Append one recorded decision; returns its 1-based id (also the running count). [sessionToken] is the
     *  agent's workbench thread id (echoed from its instructions) so a decision can be attributed to the session
     *  that recorded it — the "Start review" picker uses this to hint which session owns the pending work. */
    fun record(
        decision: String,
        kind: String,
        alternatives: String,
        files: List<String>,
        symbol: String,
        sessionToken: String = "",
    ): Int {
        val id = idGen.incrementAndGet()
        decisions.add(RecordedDecision(id, kind, decision, alternatives, files, symbol, sessionToken))
        save()
        onUpdated?.invoke()
        return id
    }

    fun all(): List<RecordedDecision> = decisions.toList()

    fun isEmpty(): Boolean = decisions.isEmpty()

    /** Total decisions recorded so far across all sessions. */
    fun count(): Int = decisions.size

    /** Pending-decision count per [RecordedDecision.sessionToken], dropping untagged (blank-token) decisions —
     *  the "Start review" picker annotates each live session with its own count as a pick hint. */
    fun countsBySession(): Map<String, Int> =
        decisions.groupingBy { it.sessionToken }.eachCount().filterKeys { it.isNotBlank() }

    /** Start a fresh log (a new authoring task, or after a Trail is finalized and the log is consumed). */
    fun clear() {
        decisions.clear()
        idGen.set(0)
        file()?.let { runCatching { Files.deleteIfExists(it) } }
        onUpdated?.invoke()
    }

    /** Where the log persists between restarts — alongside the Trail, under the project's .idea/docent. */
    private fun file(): Path? = project.basePath?.let { Path.of(it, ".idea", "docent", "decisions.json") }

    private fun load() {
        val f = file() ?: return
        if (!Files.exists(f)) return
        runCatching {
            val arr = Gson().fromJson(Files.readString(f), Array<RecordedDecision>::class.java) ?: return
            decisions.addAll(arr)
            idGen.set(arr.maxOfOrNull { it.id } ?: 0)
        }.onFailure { LOG.warn("Docent: couldn't read the persisted decision log at $f", it) }
    }

    private fun save() {
        val f = file() ?: return
        runCatching {
            Files.createDirectories(f.parent)
            Files.writeString(f, GsonBuilder().setPrettyPrinting().create().toJson(decisions))
        }.onFailure { LOG.warn("Docent: couldn't persist the decision log to $f", it) }
    }

    companion object {
        private val LOG = logger<DecisionLog>()

        fun getInstance(project: Project): DecisionLog = project.getService(DecisionLog::class.java)
    }
}

/**
 * One non-reconstructable moment captured during authoring. [kind] is one of choice / constraint / assumption
 * / surprise / verification (free text, guided by the tool description). [decision] is what was decided *and
 * why*. [alternatives] is what was weighed and rejected (the dead-ends — the part lost forever otherwise).
 * [files] are project-root-relative paths the decision touched; [symbol] is an optional symbol/snippet hint.
 * Anchoring is deliberately *loose* (no line numbers): code keeps moving while the agent works, so concrete
 * line ranges are resolved only at synthesis time against the final diff.
 */
data class RecordedDecision(
    val id: Int,
    val kind: String,
    val decision: String,
    val alternatives: String,
    val files: List<String>,
    val symbol: String,
    /** The recording agent's workbench thread id (its sessionToken), blank if it didn't echo one. */
    val sessionToken: String = "",
)
