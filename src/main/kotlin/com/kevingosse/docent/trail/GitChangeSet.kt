package com.kevingosse.docent.trail

/**
 * The set of files a Trail covers, read from git. Two modes, matching [Trail]'s two diff bases:
 *  - [committed] — a fixed commit (`commit~1` vs `commit`), the v0 / hand-authored case;
 *  - [workingTree] — uncommitted work (`baseRef` vs the live working tree), the authoring-side case (DESIGN §7),
 *    which must also surface **untracked** new files (they don't appear in `git diff` until added).
 *
 * Used by [com.kevingosse.docent.ui.DocentReviewController]'s "Other changes" section ("present 100%,
 * hide nothing", DESIGN §1) and by the finalize tool to validate a captured Trail's anchors against ground truth.
 * Runs git via [ProcessBuilder]; callers must invoke it **off the EDT**.
 */
object GitChangeSet {

    /** A changed file: git's M/A/D/R/C status letter + a project-root-relative path. */
    data class Change(val status: String, val path: String)

    /** Files changed by a fixed commit: `git diff --name-status commit~1 commit`. */
    fun committed(base: String, commit: String): List<Change> =
        nameStatus(base, listOf("$commit~1", commit))

    /**
     * Files changed in the working tree relative to [baseRef]: tracked changes from `git diff --name-status
     * <baseRef>`, plus untracked new files (`git ls-files --others --exclude-standard`) reported as added.
     */
    fun workingTree(base: String, baseRef: String): List<Change> {
        val tracked = nameStatus(base, listOf(baseRef))
        val trackedPaths = tracked.map { it.path }.toSet()
        val untracked = lines(base, listOf("ls-files", "--others", "--exclude-standard"))
            .filter { it.isNotBlank() && it !in trackedPaths }
            .map { Change("A", it) }
        return tracked + untracked
    }

    /** Files changed per [Trail]'s diff base (working-tree mode wins when [Trail.baseRef] is set). */
    fun forTrail(base: String, trail: Trail): List<Change> = when {
        trail.isWorkingTree() -> workingTree(base, trail.baseRef!!)
        !trail.commit.isNullOrBlank() -> committed(base, trail.commit)
        else -> emptyList()
    }

    private val HUNK_HEADER = Regex("""^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@""")

    /**
     * The "after"-side changed line ranges for [path], diffing [beforeRef] against the **working tree**
     * (`git diff -U0 <beforeRef> -- <path>`) — the same before/after the review's diff viewer shows. 1-based,
     * end-inclusive. Pure-deletion hunks (no after-side lines) are dropped. Empty when the file doesn't differ
     * or isn't tracked (e.g. untracked new files, which `git diff` doesn't surface). Off-EDT, like the rest.
     */
    fun changedLineRanges(base: String, beforeRef: String, path: String): List<IntRange> =
        lines(base, listOf("diff", "-U0", beforeRef, "--", path)).mapNotNull { line ->
            val m = HUNK_HEADER.find(line) ?: return@mapNotNull null
            val start = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val count = m.groupValues[2].ifBlank { "1" }.toIntOrNull() ?: 1
            if (count <= 0) null else start..(start + count - 1)
        }

    private fun nameStatus(base: String, refs: List<String>): List<Change> =
        lines(base, listOf("diff", "--name-status") + refs).mapNotNull { line ->
            val parts = line.split('\t')
            val status = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            // Renames/copies are "R100\told\tnew" — take the new path (last field).
            val path = parts.lastOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Change(status, path)
        }

    private fun lines(base: String, args: List<String>): List<String> = try {
        val process = ProcessBuilder(listOf("git", "-C", base) + args).start()
        val out = process.inputStream.readBytes()
        process.errorStream.readBytes() // drain so the process can exit
        if (process.waitFor() != 0) emptyList()
        else String(out, Charsets.UTF_8).lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    } catch (t: Throwable) {
        emptyList()
    }

    fun statusWord(status: String): String = when (status.firstOrNull()) {
        'A' -> "added"
        'D' -> "deleted"
        'M' -> "modified"
        'R' -> "renamed"
        'C' -> "copied"
        else -> "changed"
    }
}
