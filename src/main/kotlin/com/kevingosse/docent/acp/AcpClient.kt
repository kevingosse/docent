package com.kevingosse.docent.acp

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.thisLogger
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** An ACP/JSON-RPC error surfaced from the agent or from transport teardown. */
class AcpError(message: String) : RuntimeException(message)

/**
 * RETAINED FOR THE FUTURE CRITIC (not currently wired): the only caller, the self-spawn conversation backend,
 * was dropped (interactive review now requires a connected workbench agent). This transport is kept because it
 * is reusable as-is for the planned "Request code review → different-model critic" (docs/STATUS.md) — spawning a
 * second, different-model agent over stdio. See [DocentAgentSession].
 *
 * Minimal hand-rolled ACP client (docs/DESIGN.md §6): JSON-RPC 2.0 framed as NDJSON over a child
 * process's stdio. Deliberately **not** the official Kotlin ACP SDK — this keeps us platform-clean
 * (loads in Rider / IDEA-CE at build 243) with zero new dependencies (Gson is already on the platform
 * classpath, just like [com.kevingosse.docent.trail.TrailLoader]).
 *
 * Threading mirrors the codebase's existing idiom (pooled thread + `invokeLater`, see `DocentPanel`):
 * a single daemon reader thread drains stdout line-by-line; responses complete the matching pending
 * future, agent→client *requests* go to [requestHandler] (which returns the JSON result), and
 * notifications go to [notificationHandler]. Writes are serialized on the stdin writer. Callers run
 * [request] from a background thread and block on the returned future; **UI work is marshalled to the
 * EDT by the caller**, never here.
 */
class AcpClient(
    private val commandLine: GeneralCommandLine,
    /** Agent→client requests needing a reply (e.g. `session/request_permission`, `fs/read_text_file`). */
    private val requestHandler: (method: String, params: JsonObject?) -> JsonObject,
    /** Agent→client notifications (e.g. `session/update`). */
    private val notificationHandler: (method: String, params: JsonObject?) -> Unit,
    /** Invoked once when the process exits; [err] carries the tail of stderr when non-empty. */
    private val onExit: (code: Int?, err: String?) -> Unit,
) : Disposable {

    private val gson = Gson()
    private val nextId = AtomicInteger(0)
    private val pending = ConcurrentHashMap<Int, CompletableFuture<JsonObject>>()
    private val stderrTail = StringBuilder()

    @Volatile private var process: Process? = null
    @Volatile private var writer: BufferedWriter? = null
    @Volatile private var disposed = false

    /** Spawns the agent and starts the reader/stderr/exit threads. Throws if the process can't start. */
    fun start() {
        val p = commandLine.createProcess()
        process = p
        writer = BufferedWriter(OutputStreamWriter(p.outputStream, StandardCharsets.UTF_8))

        thread("acp-reader") { readLoop(p) }
        thread("acp-stderr") { drainStderr(p) }
        thread("acp-exit") {
            val code = try { p.waitFor() } catch (_: InterruptedException) { null }
            failAllPending("agent process exited (code=$code)")
            if (!disposed) onExit(code, stderrTail.toString().trim().ifBlank { null })
        }
    }

    /** Sends a JSON-RPC request and returns a future completed with its `result` (or failed on error). */
    fun request(method: String, params: JsonObject?): CompletableFuture<JsonObject> {
        val id = nextId.getAndIncrement()
        val future = CompletableFuture<JsonObject>()
        pending[id] = future
        writeLine(JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", id)
            addProperty("method", method)
            if (params != null) add("params", params)
        })
        return future
    }

    private fun readLoop(p: Process) {
        BufferedReader(InputStreamReader(p.inputStream, StandardCharsets.UTF_8)).use { reader ->
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isNotBlank()) handleLine(line)
                }
            } catch (t: Throwable) {
                if (!disposed) thisLogger().warn("ACP read loop error", t)
            }
        }
    }

    private fun handleLine(line: String) {
        val msg = try {
            JsonParser.parseString(line).asJsonObject
        } catch (t: Throwable) {
            thisLogger().warn("ACP: unparseable line: $line"); return
        }
        val idEl: JsonElement? = msg.get("id")?.takeUnless { it.isJsonNull }
        val hasMethod = msg.has("method")
        when {
            // Agent→client request: run the handler and reply, echoing the id verbatim (may be string).
            hasMethod && idEl != null -> {
                val method = msg.get("method").asString
                val result = try {
                    requestHandler(method, paramsOf(msg))
                } catch (t: Throwable) {
                    thisLogger().warn("ACP: request handler failed for $method", t); JsonObject()
                }
                respond(idEl, result)
            }
            // Agent→client notification.
            hasMethod -> notificationHandler(msg.get("method").asString, paramsOf(msg))
            // Response to one of our requests.
            idEl != null -> {
                val fut = pending.remove(idEl.asInt) ?: return
                val error = msg.get("error")?.takeUnless { it.isJsonNull }
                if (error != null) fut.completeExceptionally(AcpError(error.toString()))
                else fut.complete(msg.get("result")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject())
            }
        }
    }

    private fun respond(id: JsonElement, result: JsonObject) =
        writeLine(JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            add("id", id)
            add("result", result)
        })

    private fun writeLine(msg: JsonObject) {
        val w = writer ?: return
        val text = gson.toJson(msg)
        synchronized(w) {
            try {
                w.write(text); w.write("\n"); w.flush()
            } catch (t: Throwable) {
                if (!disposed) thisLogger().warn("ACP: write failed", t)
            }
        }
    }

    private fun drainStderr(p: Process) {
        BufferedReader(InputStreamReader(p.errorStream, StandardCharsets.UTF_8)).use { reader ->
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    thisLogger().debug("ACP[stderr] $line")
                    synchronized(stderrTail) {
                        stderrTail.append(line).append('\n')
                        if (stderrTail.length > 4000) stderrTail.delete(0, stderrTail.length - 4000)
                    }
                }
            } catch (_: Throwable) {
                // stream closed on teardown; nothing to report
            }
        }
    }

    private fun failAllPending(reason: String) {
        val it = pending.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            it.remove()
            entry.value.completeExceptionally(AcpError(reason))
        }
    }

    /** `params` as a JsonObject, or null when absent / not an object. */
    private fun paramsOf(msg: JsonObject): JsonObject? =
        msg.get("params")?.takeIf { it.isJsonObject }?.asJsonObject

    private fun thread(name: String, body: () -> Unit) =
        Thread(body, name).apply { isDaemon = true; start() }

    override fun dispose() {
        disposed = true
        failAllPending("ACP client disposed")
        try { process?.destroy() } catch (_: Throwable) {}
        try { writer?.close() } catch (_: Throwable) {}
    }
}
