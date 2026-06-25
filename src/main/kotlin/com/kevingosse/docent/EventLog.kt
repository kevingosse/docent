package com.kevingosse.docent

import com.intellij.openapi.diagnostic.logger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.stream.Collectors

/**
 * Per-review append-only NDJSON log of reviewer actions, at `<repo>/.idea/docent/events-<nonce>.ndjson`
 * (under `.idea/` — the IDE is required anyway, and `.idea/` is already git-ignored, so no extra entry).
 *
 * This is the inbound channel for a **Monitor-driven** agent (Claude Code): the agent watches this file with
 * its background-watch tool, so each reviewer action reaches it as a chat notification **outside any MCP
 * tool-call budget** — sidestepping Claude Code's stack of undocumented MCP timeouts that made a blocking
 * `docent_await_event` a dead end (60s request / ~5min SSE idle; see the await-event timeout saga). The only
 * MCP traffic left on the review loop is the agent's short *replies* (`docent_reply` / `docent_queue_change`).
 *
 * Each line is the same JSON envelope [ReviewEventEnvelope] builds for `docent_await_event`, so the agent
 * parses identical JSON no matter how the event reached it.
 *
 * **Per-review isolation:** each review gets a fresh file under a new nonce ([begin] sweeps prior `events-*`
 * files and creates an empty one), so an agent watching this review's file never sees a previous review's
 * messages, and a zombie watch from an abandoned review tails a name that no longer grows.
 */
class EventLog private constructor(private val file: Path, val relativePath: String) {

    /** Append one already-serialized JSON envelope line. Synchronized: the UI posts events from the EDT while
     *  "Complete review" may fire from a dialog callback — both land here. */
    @Synchronized
    fun append(jsonLine: String) {
        runCatching {
            Files.writeString(file, jsonLine + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }.onFailure { LOG.warn("Docent: failed to append to the event log at $file", it) }
    }

    companion object {
        private val LOG = logger<EventLog>()

        /** Marker the watch command greps for to exit itself once the review is done (see [watchCommand]). */
        private const val COMPLETED_MARKER = """"event":"review_completed""""

        /**
         * Begin a fresh event log for a new review under [baseDir] (the repo root). Sweeps any prior
         * `events-*.ndjson` (best-effort — a file a zombie watch holds open on Windows just stays, harmless)
         * and creates a new empty file under [nonce]. Returns null if the directory can't be prepared.
         */
        fun begin(baseDir: String, nonce: String): EventLog? = runCatching {
            val dir = Path.of(baseDir, ".idea", "docent")
            Files.createDirectories(dir)
            sweepOldLogs(dir)
            val rel = ".idea/docent/events-$nonce.ndjson"
            val f = dir.resolve("events-$nonce.ndjson")
            Files.deleteIfExists(f)
            Files.createFile(f)
            EventLog(f, rel)
        }.onFailure { LOG.warn("Docent: couldn't begin an event log under $baseDir", it) }.getOrNull()

        private fun sweepOldLogs(dir: Path) {
            runCatching {
                Files.list(dir).use { stream ->
                    stream.filter { it.fileName.toString().let { n -> n.startsWith("events-") && n.endsWith(".ndjson") } }
                        .collect(Collectors.toList())
                }.forEach { runCatching { Files.deleteIfExists(it) } }
            }
        }

        /**
         * A POSIX-sh command the agent runs with its background-watch (Monitor) tool, persistent, to stream this
         * review's events. It polls the log once a second, prints each new line (the agent responds to each), and
         * **exits itself** once it prints the `review_completed` line — so the watch cleans up without the agent
         * having to stop it. A poll loop (not `tail -f`) is deliberate: it terminates cleanly on the marker
         * instead of leaving `tail` hung waiting for a SIGPIPE that a now-quiet log never triggers, and it doesn't
         * depend on `tail -f`'s append-detection semantics on Windows/Git-Bash.
         */
        fun watchCommand(relativePath: String): String =
            "n=0; f='$relativePath'; " +
                "while :; do " +
                "t=\$(wc -l < \"\$f\" 2>/dev/null || echo 0); " +
                "while [ \"\$n\" -lt \"\$t\" ]; do " +
                "n=\$((n+1)); l=\$(sed -n \"\${n}p\" \"\$f\"); printf '%s\\n' \"\$l\"; " +
                "case \"\$l\" in *'$COMPLETED_MARKER'*) exit 0;; esac; " +
                "done; sleep 1; done"
    }
}
