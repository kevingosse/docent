# Code Review Docent

A JetBrains IDE plugin that recreates the experience of a peer walking you through their change, but with the author's reasoning captured at the source, and a fresh, critical guide delivering it.

As developers delegate more implementation to AI agents, the bottleneck shifts from *writing* code to *reviewing* it. The agent hands back a large wall of code, the human has to understand it alone, not knowing where to start. The goal of this plugin is to present it as a story, divided into bite-sized sections that can be reviewed one at a time, with clear inline explanations.

## How it works

**Trail** (the script) — While a coding agent implements a task, it records decisions and synthesizes them into a structured narrative anchored to the diff. The Trail carries only what can't be reconstructed from the code: the *why*, not the *what*.

**Docent** (the guide) — The agent walks the human through the change in their IDE, ready to answer questions or debate alternatives. The human drives the pace.

## Building

Requires a local Rider installation (2025.2+, build 252+). Set the path:

```bash
# gradle.properties or environment variable
riderLocalPath=/path/to/rider
# or
RIDER_HOME=/path/to/rider
```

```bash
./gradlew buildPlugin        # → build/distributions/code-review-docent-*.zip
./gradlew runRider           # launch Rider with the plugin loaded
```

Then: **Tools → Open Docent Review**.

## Design

See [`docs/DESIGN.md`](docs/DESIGN.md) for the full problem statement, principles, and architecture.
