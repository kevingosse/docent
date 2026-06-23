package com.kevingosse.docent.trail

import com.google.gson.Gson
import java.nio.file.Files
import java.nio.file.Path

/**
 * Loads a [Trail] from external JSON so content iterates without recompiling the plugin
 * (DESIGN.md §10: "keep the Trail as external data").
 *
 * There is **no default trail** — a review is loaded only from an explicit path, supplied by the MCP
 * handoff (`docent_finalize_trail` writes `<repo>/.docent/trail.json`). With no path set, the
 * review shows an empty state.
 */
object TrailLoader {
    fun load(path: String): Trail = load(Path.of(path))

    fun load(path: Path): Trail {
        val json = Files.readString(path)
        return Gson().fromJson(json, Trail::class.java)
    }
}
