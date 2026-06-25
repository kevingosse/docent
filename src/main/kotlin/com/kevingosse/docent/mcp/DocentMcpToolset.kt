package com.kevingosse.docent.mcp

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.projectOrNull
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.kevingosse.docent.DecisionLog
import com.kevingosse.docent.DeliveryMode
import com.kevingosse.docent.DocentReviewService
import com.kevingosse.docent.EventLog
import com.kevingosse.docent.ReviewEvent
import com.kevingosse.docent.ReviewEventEnvelope
import com.kevingosse.docent.trail.Comment
import com.kevingosse.docent.trail.GitChangeSet
import com.kevingosse.docent.trail.Trail
import com.kevingosse.docent.trail.TrailLoader
import com.kevingosse.docent.ui.DocentReviewController
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.coroutineContext

/**
 * The Docent's MCP tools (docs/DESIGN.md §6/§7), in two halves that close one loop:
 *
 *  - **Authoring (capture, §7):** as you implement, [docent_record_decision] logs the non-reconstructable
 *    *why*; when done, [docent_change_summary] returns ground truth + your log, you compose a Trail, and
 *    [docent_finalize_trail] writes it and opens the review.
 *  - **Review (perform, §6):** [docent_finalize_trail] opens the review and arms the live loop. You do NOT
 *    poll: the IDE PUSHES each reviewer action into your session (see `awb/DocentEventNotifier`), and you respond
 *    with [docent_reply] and record requested changes with [docent_queue_change] (read-only — no edits until the
 *    human completes the review). [docent_await_event] remains only as a catch-up for a missed push.
 *
 * Published into the IDE's in-process MCP server via `com.intellij.mcpServer.mcpToolset` (optional config
 * `docent-mcp.xml`), so the core plugin still loads where the MCP server is absent.
 *
 * **Descriptions matter:** the MCP server derives each tool/param description from [McpDescription] (KDoc is
 * not retained at runtime), so the agent only sees what's annotated here. Keep the annotations the source of
 * truth for the agent-facing contract.
 */
class DocentMcpToolset : McpToolset {

    /**
     * Expose these tools **eagerly**, not behind the universal tool-router. Every MCP tool defaults to
     * `routerOnly = true`; when the IDE runs in "Universal tool-router mode" (VIA_ROUTER) that means the agent
     * only reaches a router-only tool by *discovering* it through `execute_tool` — so it never sees the Docent
     * unless explicitly prompted to look. `McpServerService` exposes router-only tools directly in that mode
     * **iff** `tool.descriptor.category.alwaysIncluded` is set (the `McpToolFilter.AlwaysIncluded` path), and
     * `asTools()` copies this flag onto every tool's category. So overriding this to true is the registration-
     * level equivalent of the user unchecking "router-only" for each Docent tool — done automatically.
     *
     * The platform warns to set this only after evaluating tool-list pollution; that's an accepted trade here —
     * the Docent's purpose is to be reachable the moment a review/authoring task starts, with no prompting.
     *
     * CAVEAT (field-tested 2026-06-18): this is **necessary but not sufficient** with the JetBrains workbench,
     * because that workbench launches the real **Claude Code CLI**, which defers MCP tools on the *client* side
     * (its own tool-search) regardless of how the IDE advertises them — even manually unchecking "router-only"
     * in Rider settings didn't make the CLI load them eagerly. The actual lever for "the agent knows about the
     * Docent without being prompted" is a **CLAUDE.md / skill in the reviewed repo** (loaded by the CLI), not an
     * IDE-side flag. Kept anyway as correct IDE-side hygiene (and it does help non-deferring clients).
     */
    override fun alwaysIncluded(): Boolean = true

    /** Not experimental — `McpToolset.isExperimental()` defaults to *true*, which an `AllowNonExperimental` filter would hide. */
    override fun isExperimental(): Boolean = false

    // ===== Review side (docs/DESIGN.md §6 — the live, agent-driven review loop) =====

    @McpTool
    @McpDescription(
        """
        |Block until the reviewer's next action and return it as a small JSON envelope. Use this ONLY if your
        |instructions told you to poll — i.e. you were NOT given a shell command to watch the review's events
        |file. (If you were given a watch command, use THAT; this tool will just sit idle.) Envelopes:
        |  - {"event":"message"|"comment", "id", ...}: answer with docent_reply using the same id; if they ask
        |    for a change, also call docent_queue_change, then call this tool again.
        |  - {"event":"review_completed", "queuedChanges"}: the review is done — implement the queued changes.
        |It blocks until something is pending (no server-side timeout); keep calling it after each event until
        |review_completed.
        """
    )
    suspend fun docent_await_event(): String {
        val project = coroutineContext.projectOrNull
            ?: return errorJson("No IDE project is open.")
        return eventJson(DocentReviewService.getInstance(project).awaitEvent())
    }

    @McpTool
    @McpDescription(
        """
        |Answer the reviewer. The reply lands in the UI element that raised the event — the section conversation
        |for a "message", or the inline comment thread for a "comment". Always pass the event's id verbatim.
        """
    )
    suspend fun docent_reply(
        @McpDescription("The id from the docent_await_event envelope you are answering.")
        eventId: String,
        @McpDescription("Your reply, in the author's voice — the WHY, not a restatement of the diff.")
        text: String,
    ): String {
        val project = coroutineContext.projectOrNull
            ?: return "No IDE project is open."
        val delivered = DocentReviewService.getInstance(project).deliverReply(eventId, text)
        return if (delivered) "Delivered."
        else "No reviewer element is waiting on '$eventId' (it may have been dismissed). Reply ignored."
    }

    @McpTool
    @McpDescription(
        """
        |Record a change the reviewer asked for. It is held read-only in the review's queue and implemented only
        |when the reviewer completes the review (you'll receive the full queue then via review_completed). Do NOT
        |edit files now. When you agree with a request, acknowledge it briefly in docent_reply ("queued") — do not
        |pre-describe the implementation; you'll do the work after the review.
        """
    )
    suspend fun docent_queue_change(
        @McpDescription("What to change, in your own words.")
        summary: String,
        @McpDescription("Optional project-root-relative path the change concerns.")
        file: String = "",
        @McpDescription("Optional 1-based line in `file`.")
        line: Int = 0,
        @McpDescription("Optional 1-based section number the change relates to.")
        section: Int = 0,
    ): String {
        val project = coroutineContext.projectOrNull
            ?: return "No IDE project is open."
        val count = DocentReviewService.getInstance(project)
            .queueChange(summary, file, line, if (section > 0) section - 1 else -1)
        return "Queued change #$count. It will be applied when the reviewer completes the review."
    }

    @McpTool
    @McpDescription(
        """
        |Resume a review on a Trail that was already authored — its JSON exists on disk (e.g. you stopped before
        |walking the human through it and want to pick it up now). This loads the Trail into the IDE review UI and
        |arms the live loop with you as the Docent; you do NOT re-author or re-write it. Because your earlier
        |context may be gone, READ the trail file after this so you can answer from its recorded WHY (the
        |narration and inline comments), not from memory. Pass your sessionToken (from your system instructions)
        |so the reviewer's actions route back to THIS session. Defaults to <repo>/.idea/docent/trail.json.
        |After this, drive the review exactly as after docent_finalize_trail: the IDE pushes each reviewer action
        |to you; reply with docent_reply, record requested changes with docent_queue_change (read-only until the
        |reviewer completes the review).
        """
    )
    @Suppress("SENSELESS_COMPARISON") // Gson/TrailLoader can leave required fields null when JSON omits them.
    suspend fun docent_resume_review(
        @McpDescription("Path to the Trail JSON. Relative paths resolve against the project root. Blank → .idea/docent/trail.json.")
        path: String = "",
        @McpDescription(
            "Your sessionToken from your system instructions, so the live review can message you (it routes the " +
                "reviewer's actions back to THIS session). Omit only if you have no sessionToken."
        )
        sessionToken: String = "",
    ): String {
        val project = coroutineContext.projectOrNull ?: return "No IDE project is open."
        val base = project.basePath ?: return "The project has no base path on disk."

        val abs = path.trim().let {
            when {
                it.isBlank() -> Path.of(base, ".idea", "docent", "trail.json")
                else -> Path.of(it).let { p -> if (p.isAbsolute) p else Path.of(base, it) }
            }
        }
        if (!Files.exists(abs)) {
            return "No Trail file at $abs. Author one with docent_finalize_trail first, or pass the correct path."
        }
        val trail = try {
            TrailLoader.load(abs)
        } catch (t: Throwable) {
            return "Couldn't read the Trail at $abs (${t.message}). It must be a valid Trail JSON object."
        }
        if (trail == null || trail.subject == null || trail.sections == null || trail.sections.isEmpty()) {
            return "The Trail at $abs is missing a subject or sections[]; it doesn't look like a finalized Trail."
        }
        return armReview(project, abs.toString(), trail, sessionToken)
    }

    /**
     * Point the review UI at [path], arm the live loop, and return the agent's drive-the-review instructions.
     * Called by [docent_finalize_trail] after writing the Trail to open the review.
     */
    private fun armReview(project: Project, path: String, trail: Trail, sessionToken: String = ""): String {
        val service = DocentReviewService.getInstance(project)
        service.reset()
        service.trailPath = path
        // The session that arms the review self-identifies via the sessionToken it echoed from its system
        // prompt (= its workbench thread id). This is the push target — captured here, not from the racy
        // last-launch capture, so review messages route to THIS session. Blank → no push; falls back to poll.
        // linkAgentSession marks the review active and notifies the UI so its affordances enable.
        service.linkAgentSession(sessionToken.trim().ifBlank { null })
        ApplicationManager.getApplication().invokeLater {
            val controller = DocentReviewController.getInstance(project)
            controller.loadTrail() // force-load the handed-off path, then show nav + review
            controller.openReview()
        }

        val logPath = service.eventLogPath
        val monitor = service.deliveryMode == DeliveryMode.MONITOR && logPath != null
        return buildString {
            appendLine("Opened the Docent review for \"${trail.subject}\" (${trail.sections.size} sections).")
            appendLine("You are now the live Docent — the author of this change, walking the human through it.")
            appendLine()
            if (monitor) {
                appendLine("Watch this review's events with the Monitor tool (persistent: true), running EXACTLY this command:")
                appendLine()
                appendLine("  " + EventLog.watchCommand(logPath!!))
                appendLine()
                appendLine("Each line it prints is one reviewer action, as JSON. For each:")
            } else {
                appendLine("Drive the review by calling docent_await_event (it blocks until the reviewer acts). For each event:")
            }
            appendLine("  - a reviewer question/comment: answer from your first-hand knowledge of WHY (not a restatement")
            appendLine("    of the diff) via docent_reply(eventId, text) using the event id in the JSON. If they request")
            appendLine("    a change, also call docent_queue_change(...) and acknowledge it briefly (\"queued\") — do NOT")
            appendLine("    edit files yet, and don't pre-describe the implementation.")
            appendLine("  - a \"review_completed\" event: the reviewer is done. NOW implement the queued changes")
            appendLine("    (editing is allowed), then stop.")
            appendLine()
            if (monitor) {
                appendLine("Start the Monitor watch now, then end your turn — each printed line arrives as a new message for")
                appendLine("you to handle. The watch exits itself on review_completed; do NOT also call docent_await_event.")
                append("So: confirm the review is open, start the Monitor watch, and end your turn.")
            } else {
                append("Start now by calling docent_await_event, and keep calling it after each event until review_completed.")
            }
        }
    }

    // ===== Authoring side (docs/DESIGN.md §7 — "trace during, synthesize after") =====
    //
    // As the agent works it calls docent_record_decision; when the change-set is done it reads
    // docent_change_summary and submits the synthesized story with docent_finalize_trail, which writes the
    // Trail and opens the very review the tools above drive. This closes the capture -> review loop.

    @McpTool
    @McpDescription(
        """
        |The Code Review Docent captures the non-reconstructable WHY of a change and then walks a human reviewer
        |through it live — so they stay in touch with code that is increasingly written by AI. This tool is the
        |capture step (you'll synthesize a Trail and present it later).
        |Record one NON-reconstructable decision while you work — the WHY the diff can't show. Call this AS YOU
        |GO, not from memory at the end: the Trail is later synthesized from this log, which is what keeps it
        |honest. Be calibrated (this is not a diff narration) — record only what a reviewer should know:
        |  - choice: you picked one approach over real alternatives;
        |  - constraint: something external forced an awkward shape;
        |  - assumption: you relied on something not guaranteed by the code;
        |  - surprise: you discovered something non-obvious mid-task;
        |  - verification: you checked a claim against ground truth (and what you found).
        |The full authoring flow: record_decision (repeatedly) -> change_summary -> compose Trail ->
        |finalize_trail. Use docent_list_decisions to review what you've logged.
        """
    )
    suspend fun docent_record_decision(
        @McpDescription("What you decided AND why — the non-reconstructable part, not a restatement of the code.")
        decision: String,
        @McpDescription("One of: choice / constraint / assumption / surprise / verification.")
        kind: String = "",
        @McpDescription("What you weighed and rejected, and why (the dead-ends — lost forever otherwise).")
        alternatives: String = "",
        @McpDescription("Files this decision touched, comma- or newline-separated, project-root-relative.")
        files: String = "",
        @McpDescription(
            "Optional symbol/snippet hint (a function or type name) to help anchor it later. Do NOT pass line " +
                "numbers — code keeps moving; concrete line ranges are resolved at finalize against the final diff."
        )
        symbol: String = "",
    ): String {
        val project = coroutineContext.projectOrNull ?: return "No IDE project is open."
        val fileList = files.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        val id = DecisionLog.getInstance(project).record(decision, kind.trim(), alternatives.trim(), fileList, symbol.trim())
        return "Recorded decision #$id. Keep recording as you work; call docent_change_summary then " +
            "docent_finalize_trail when the change-set is done."
    }

    @McpTool
    @McpDescription("Return the decisions you've recorded so far for this task, as a JSON array. Use it to review or self-check your log before finalizing (there is no edit — record a corrected decision if one was wrong).")
    suspend fun docent_list_decisions(): String {
        val project = coroutineContext.projectOrNull ?: return errorJson("No IDE project is open.")
        return JsonObject().apply { add("recordedDecisions", decisionsJson(project)) }.toString()
    }

    @McpTool
    @McpDescription(
        """
        |Read the ground truth for synthesis: the files you changed (vs baseRef, including untracked new files,
        |each with its exact `changedLineRanges`) and the decisions you recorded. Call this when the change-set is
        |done, BEFORE composing the Trail — build the narrative FROM the recorded decisions (so it can't drift
        |from what actually happened), anchored to the changed files. Use `changedLineRanges` for anchor `ranges`
        |and `anchorText` (a quoted snippet) for comment lines instead of guessing numbers — see `guidance`. The
        |returned `guidance` restates the Trail JSON schema AND the section-vs-inline-comment granularity rule —
        |read it carefully before composing.
        """
    )
    suspend fun docent_change_summary(
        @McpDescription("The git ref your work is diffed against (the \"before\"); defaults to HEAD.")
        baseRef: String = "HEAD",
    ): String {
        val project = coroutineContext.projectOrNull ?: return errorJson("No IDE project is open.")
        val base = project.basePath ?: return errorJson("The project has no base path on disk.")
        val ref = baseRef.ifBlank { "HEAD" }

        val o = JsonObject()
        o.addProperty("baseRef", ref)
        val files = JsonArray()
        GitChangeSet.workingTree(base, ref).forEach { c ->
            files.add(JsonObject().apply {
                addProperty("status", GitChangeSet.statusWord(c.status))
                addProperty("path", c.path)
                // The exact after-side changed-line ranges (1-based, end-inclusive) — set anchor `ranges` from
                // these so focus spans land on real changed lines. Cheap (just numbers), not a content read-back.
                val ranges = GitChangeSet.changedLineRanges(base, ref, c.path)
                if (ranges.isNotEmpty()) add("changedLineRanges", JsonArray().apply {
                    ranges.forEach { add(JsonObject().apply { addProperty("start", it.first); addProperty("end", it.last) }) }
                })
            })
        }
        o.add("changedFiles", files)
        o.add("recordedDecisions", decisionsJson(project))
        o.addProperty(
            "guidance",
            "Compose a Trail JSON: {subject, thesis(HTML), sections:[{headline, narration(HTML), " +
                "anchors:[{path, ranges?:[{start,end}], label?, comments?:[{line, anchorText, body}]}]}]}. Line " +
                "numbers (ranges start/end and comment line) are 1-based and end-INCLUSIVE, against the CURRENT " +
                "working-tree file. " +
                "LINE-NUMBER ACCURACY — do NOT guess line numbers from memory: " +
                "(1) set each anchor's `ranges` from the `changedLineRanges` returned above (the exact changed " +
                "spans for that file); " +
                "(2) for every comment, set `anchorText` to a short, UNIQUE substring of the exact line the comment " +
                "is about (quote the code verbatim, e.g. a distinctive token or call) — the IDE resolves it to the " +
                "real line at finalize, so you needn't count lines; still put your best-guess `line` as a fallback " +
                "(it disambiguates when a snippet appears more than once). " +
                "GRANULARITY — the most important rule: a SECTION is a functional block of the change; its headline " +
                "and narration must stand on their own and be understandable WITHOUT looking at the code (the " +
                "high-level what and why a reviewer needs before reading anything). Anything that points at specific " +
                "code — a line, a symbol, a concrete mechanism — belongs in an INLINE COMMENT on that line, NOT in " +
                "the section narration. Never split one method or function across sections. Aim for a few coherent " +
                "sections carried by rich inline comments, not many thin sections. " +
                "Build everything FROM the recorded decisions above (the non-reconstructable why) — do NOT restate " +
                "the diff. Anchor each section to files from changedFiles. Then submit with " +
                "docent_finalize_trail(trailJson, baseRef=\"$ref\").",
        )
        return o.toString()
    }

    @McpTool
    @McpDescription(
        """
        |Finish authoring: take the Trail you synthesized from your recorded decisions, validate it against the
        |actual change-set, write it to <repo>/.idea/docent/trail.json, and open the review on it — at which point
        |you become the live Docent. The Trail is diffed against the working tree, so baseRef is
        |forced onto it (any `commit` field is ignored). Each comment's `anchorText` is resolved to the real
        |working-tree line here, so quoted snippets beat hand-counted line numbers. Validation flags anchors that
        |aren't on changed files, comment snippets it couldn't locate, and warns if nothing was recorded during
        |the task (a sign the story may be from memory, not the log).
        |Granularity reminder: sections = functional blocks understandable without the code; code-specific points
        |= inline comments. Never split one method across sections.
        """
    )
    @Suppress("SENSELESS_COMPARISON") // Gson can leave required fields null when JSON omits them (bypasses ctor).
    suspend fun docent_finalize_trail(
        @McpDescription("The synthesized Trail, as a JSON object (see docent_change_summary's `guidance` for the schema).")
        trailJson: String,
        @McpDescription("The git ref the change is diffed against; defaults to HEAD.")
        baseRef: String = "HEAD",
        @McpDescription(
            "Your sessionToken from your system instructions. REQUIRED so the live review can message you " +
                "(it routes the reviewer's actions back to THIS session). Omit only if you have no sessionToken."
        )
        sessionToken: String = "",
    ): String {
        val project = coroutineContext.projectOrNull ?: return "No IDE project is open."
        val base = project.basePath ?: return "The project has no base path on disk."
        val ref = baseRef.ifBlank { "HEAD" }

        val parsed = try {
            Gson().fromJson(trailJson, Trail::class.java)
        } catch (t: Throwable) {
            return "Couldn't parse the Trail JSON (${t.message}). It must be a JSON object with subject, thesis, and sections[]."
        }
        if (parsed == null || parsed.subject == null || parsed.thesis == null || parsed.sections == null || parsed.sections.isEmpty()) {
            return "The Trail must have a subject, a thesis, and a non-empty sections[]."
        }
        // Force the working-tree base; a captured Trail is diffed against baseRef, not a commit.
        val warnings = mutableListOf<String>()
        // Resolve each comment's anchorText to a real working-tree line (line-number accuracy, DESIGN §7) before
        // forcing the base, so the stored Trail carries correct 1-based lines regardless of what the agent guessed.
        val trail = resolveAnchors(base, ref, parsed.copy(baseRef = ref, commit = null), warnings)

        // Audit against ground truth (DESIGN §7): every anchor should sit on a file the change actually touched.
        val changed = GitChangeSet.workingTree(base, ref).map { it.path }.toSet()
        trail.sections.flatMap { it.anchors }.map { it.path }.distinct()
            .filter { it !in changed }
            .forEach { warnings.add("anchored file isn't in the change-set (vs $ref): $it") }
        if (DecisionLog.getInstance(project).isEmpty()) {
            warnings.add("no decisions were recorded during authoring — the narrative may be reconstructed from memory rather than a log (DESIGN §7).")
        }

        val file = Path.of(base, ".idea", "docent", "trail.json")
        try {
            Files.createDirectories(file.parent)
            Files.writeString(file, GsonBuilder().setPrettyPrinting().create().toJson(trail))
        } catch (t: Throwable) {
            return "Couldn't write the Trail to $file: ${t.message}"
        }

        val report = buildString {
            appendLine("Wrote the Trail for \"${trail.subject}\" (${trail.sections.size} sections) to $file.")
            if (warnings.isNotEmpty()) {
                appendLine("Validation warnings:")
                warnings.forEach { appendLine("  - $it") }
            }
        }.trimEnd()

        DecisionLog.getInstance(project).clear() // the log has been synthesized into the Trail; start fresh
        return "$report\n\n" + armReview(project, file.toString(), trail, sessionToken)
    }

    // ----- Anchor-by-text resolution (line-number accuracy, DESIGN §7) -----

    /**
     * Resolve every comment's [Comment.anchorText] to a real 1-based line in the working-tree file, so the stored
     * Trail carries correct lines even when the agent's guessed [Comment.line] is off. Caches git/file reads per
     * path; appends a warning per snippet that couldn't be located (or matched only outside the change-set).
     */
    private fun resolveAnchors(base: String, ref: String, trail: Trail, warnings: MutableList<String>): Trail {
        val linesCache = HashMap<String, List<String>?>()
        val hunksCache = HashMap<String, List<IntRange>>()
        fun fileLines(path: String) = linesCache.getOrPut(path) {
            try { Files.readAllLines(Path.of(base, path)) } catch (t: Throwable) { null }
        }
        fun hunks(path: String) = hunksCache.getOrPut(path) { GitChangeSet.changedLineRanges(base, ref, path) }

        val sections = trail.sections.map { section ->
            section.copy(anchors = section.anchors.map { anchor ->
                val comments = anchor.comments
                if (comments.isNullOrEmpty()) anchor
                else anchor.copy(comments = comments.map { resolveComment(anchor.path, it, fileLines(anchor.path), hunks(anchor.path), warnings) })
            })
        }
        return trail.copy(sections = sections)
    }

    /** Locate [c]'s anchorText in [lines] (prefer a match inside a changed [hunks] span, nearest the hinted line);
     *  return the comment re-pinned to that line, or unchanged (with a warning) when the snippet can't be matched. */
    private fun resolveComment(path: String, c: Comment, lines: List<String>?, hunks: List<IntRange>, warnings: MutableList<String>): Comment {
        val needle = c.anchorText?.trim().orEmpty()
        if (needle.isEmpty() || lines == null) return c
        val matches = lines.indices.filter { lines[it].contains(needle) }.map { it + 1 } // 1-based after-side lines
        if (matches.isEmpty()) {
            warnings.add("comment anchorText not found in $path (kept line ${c.line}): \"${needle.take(60)}\"")
            return c
        }
        val inHunk = matches.filter { ln -> hunks.any { ln in it } }
        val pool = inHunk.ifEmpty {
            warnings.add("comment anchorText matched only outside the change-set in $path (using line ${matches.first()}): \"${needle.take(60)}\"")
            matches
        }
        val chosen = if (c.line > 0) pool.minByOrNull { kotlin.math.abs(it - c.line) }!! else pool.first()
        return if (chosen == c.line) c else c.copy(line = chosen)
    }

    // ----- JSON envelopes the agent parses -----

    /** The recorded decisions as a JSON array, shared by [docent_list_decisions] and [docent_change_summary]. */
    private fun decisionsJson(project: Project): JsonArray {
        val arr = JsonArray()
        DecisionLog.getInstance(project).all().forEach { d ->
            arr.add(JsonObject().apply {
                addProperty("id", d.id)
                if (d.kind.isNotBlank()) addProperty("kind", d.kind)
                addProperty("decision", d.decision)
                if (d.alternatives.isNotBlank()) addProperty("alternatives", d.alternatives)
                if (d.files.isNotEmpty()) addProperty("files", d.files.joinToString(", "))
                if (d.symbol.isNotBlank()) addProperty("symbol", d.symbol)
            })
        }
        return arr
    }

    /** The await path's envelope: same JSON the [EventLog] file carries, but with poll-flavored hints. */
    private fun eventJson(event: ReviewEvent): String = ReviewEventEnvelope.toJson(event, monitor = false)

    private fun errorJson(message: String): String =
        JsonObject().apply { addProperty("event", "error"); addProperty("message", message) }.toString()
}
