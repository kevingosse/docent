package com.kevingosse.docent

import com.google.gson.JsonObject

/**
 * The single source of truth for the JSON envelope a [ReviewEvent] is delivered to the agent as — whether it
 * arrives via the [EventLog] file (Monitor-driven Claude) or as the return value of `docent_await_event`
 * (a provider that blocks on the tool instead). Keeping one serializer means the agent parses identical JSON
 * regardless of the delivery mechanism; only the `hint` differs, telling the agent what to do next in its mode.
 */
object ReviewEventEnvelope {

    /**
     * Build the envelope for [event]. [monitor] = the agent is watching the [EventLog] file (respond and keep
     * watching; the watch self-exits on completion); otherwise it's polling `docent_await_event` (respond, then
     * call the tool again).
     */
    fun toJson(event: ReviewEvent, monitor: Boolean): String {
        val o = JsonObject()
        o.addProperty("event", event.kind)
        when (event.kind) {
            DocentReviewService.REVIEW_COMPLETED -> {
                o.addProperty("queuedChanges", event.text.ifBlank { "(none)" })
                o.addProperty(
                    "hint",
                    if (monitor)
                        "The reviewer finished. Your watch has now exited. Implement the queued changes (you may " +
                            "edit files), then stop."
                    else
                        "The reviewer finished. Implement the queued changes now (you may edit files), then stop " +
                            "calling docent_await_event.",
                )
            }

            DocentReviewService.REVIEW_RESUMED -> {
                if (event.file.isNotBlank()) o.addProperty("trailPath", event.file)
                if (event.text.isNotBlank()) o.addProperty("subject", event.text)
                o.addProperty(
                    "hint",
                    "The reviewer opened a Docent review on this Trail and connected you as the Docent. Read the " +
                        "trail file to refresh the WHY, then respond to each reviewer action with docent_reply / " +
                        "docent_queue_change as usual.",
                )
            }

            else -> {
                o.addProperty("id", event.id)
                if (event.sectionIndex >= 0) o.addProperty("section", event.sectionIndex + 1)
                if (event.sectionHeadline.isNotBlank()) o.addProperty("sectionHeadline", event.sectionHeadline)
                if (event.file.isNotBlank()) o.addProperty("file", event.file)
                if (event.line > 0) o.addProperty("line", event.line)
                if (event.context.isNotBlank()) o.addProperty("context", event.context)
                o.addProperty("reviewer", event.text)
                o.addProperty(
                    "hint",
                    if (monitor)
                        "Answer with docent_reply(\"${event.id}\", ...). If they request a change, also call " +
                            "docent_queue_change and acknowledge it briefly (\"queued\") — do NOT edit files yet. " +
                            "Then keep watching for the next event."
                    else
                        "Answer with docent_reply(\"${event.id}\", ...); then call docent_await_event again.",
                )
            }
        }
        return o.toString()
    }
}
