package com.kevingosse.docent.trail

/**
 * The captured story for one change — the "script" (docs/DESIGN.md §6). Authored externally
 * (hand-authored for v0) and deliberately minimal: narrative + anchors, nothing more. Importance
 * and triage are *not* fields here — the Docent conveys them in the narration (DESIGN §4/§9).
 */
data class Trail(
    val subject: String,
    val thesis: String,
    val sections: List<Section>,
    // The revision under review. Each file's diff is `commit~1` vs `commit`. Nullable because Gson
    // skips Kotlin defaults for absent JSON fields (see Comment.author); coerced/guarded at use.
    val commit: String? = null,
    // For a Trail captured over **uncommitted** work (the authoring side, DESIGN §7): the git ref the change
    // is diffed against (the "before"), with the live working tree as the "after". Set instead of [commit] when
    // the agent finalizes a Trail before committing (e.g. "HEAD"). When present it wins over [commit].
    val baseRef: String? = null,
) {
    /**
     * The git ref for the diff's "before" side: [baseRef] verbatim if this Trail was captured over uncommitted
     * work, else `commit~1` for a committed Trail. Null if neither is set (nothing to diff against). The "after"
     * side is always the live working-tree file, so both modes share one render path.
     */
    fun beforeRef(): String? = when {
        !baseRef.isNullOrBlank() -> baseRef
        !commit.isNullOrBlank() -> "$commit~1"
        else -> null
    }

    /** Whether the change-set is uncommitted (diffed against [baseRef], working tree on the "after" side). */
    fun isWorkingTree(): Boolean = !baseRef.isNullOrBlank()

    /** Caption for the diff's "after" side. */
    fun afterLabel(): String = if (isWorkingTree()) "Working tree" else (commit ?: "after")
}

/** One bite-sized step: the Docent's narration plus the code it points at. */
data class Section(
    val headline: String,
    val narration: String,
    val anchors: List<Anchor> = emptyList(),
    val comments: List<Comment>? = null,
)

/**
 * A Docent inline comment pinned to a line in the section's anchor file (GitHub-PR style). Plain text
 * for now. [author] is "docent"; "you" arrives once the reviewer can add/reply (DESIGN.md §9).
 * [line] is 1-based in the anchor file.
 */
data class Comment(
    val line: Int,
    val body: String,
    // Nullable because Gson skips Kotlin default values for absent JSON fields; defaulted at use.
    val author: String? = null,
    // Optional snippet of the target line — the code the comment is about (the agent wrote/read it, so it can
    // quote it reliably). At finalize we resolve it to the real 1-based [line] in the working-tree file (prefer a
    // match inside a changed hunk), so the agent needn't get line numbers right. [line] is the hint/fallback
    // when this is absent or unmatched. See DocentMcpToolset.resolveComment.
    val anchorText: String? = null,
)

/** A 1-based, inclusive line span within a file. */
data class LineRange(val start: Int, val end: Int)

/**
 * A pointer into the reviewed code. [path] is project-root-relative. [ranges] are the focused line spans
 * (1-based, inclusive); a file may be split into several disjoint spans, each lit while the rest dims. Omit
 * [ranges] (or use the legacy single-span [startLine]/[endLine] shorthand) to focus the whole file. [label]
 * is an optional caption for the cell.
 */
data class Anchor(
    val path: String,
    val ranges: List<LineRange>? = null,
    // Legacy single-span shorthand; folded into [focusRanges] when [ranges] is absent.
    val startLine: Int? = null,
    val endLine: Int? = null,
    val label: String? = null,
    // Docent comments pinned to lines in this file's "after" side. Shown as inline cards on the diff.
    val comments: List<Comment>? = null,
) {
    /** The focus spans as IntRanges: [ranges] if given, else the [startLine]/[endLine] shorthand, else empty. */
    fun focusRanges(): List<IntRange> = when {
        !ranges.isNullOrEmpty() -> ranges.map { it.start..it.end }
        startLine != null && endLine != null -> listOf(startLine..endLine)
        else -> emptyList()
    }
}
