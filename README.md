# Code Review Docent

A JetBrains IDE plugin that recreates the experience of a peer walking you through their change — with the author's reasoning captured at the source, and a guide delivering it in your editor.

As developers delegate more implementation to AI agents, the bottleneck shifts from *writing* code to *reviewing* it. The agent hands back a wall of code, and you have to understand it alone, not knowing where to start. Docent presents the change as a **story**: bite-sized sections you review one at a time, each with the *why* behind it — the alternatives weighed, the constraints hit, the assumptions made — and a live agent standing by to answer questions.

## How a review works

1. **You ask an agent for a code review** (Claude Code or Codex, in a session opened in the IDE's Agent Workbench): "let's start a code review". The agent composes the walkthrough in one go and opens it. You can also ask from the **Code Review Docent** tool window (left sidebar), which lists the live sessions.
2. **The Docent walks you through it.** Sections in a plan rail, narration above a real diff viewer, inline comments on the exact lines they're about. Everything is your IDE: Ctrl-click, find-usages, side-by-side or unified diff.
3. **You drive.** Ask questions in any section — the agent answers from its first-hand knowledge of the change. Ask for changes — they're queued, not applied, while you keep reviewing.
4. **Complete the review.** The queued change requests are dispatched to the agent, which implements them. Ask for a new review to walk through the follow-up delta.

Nothing is hidden: every changed file appears in some section (an auto-synthesized "Other changes" section catches the remainder), so you stay in touch with the whole change.

## Requirements

- **Rider 2026.2 EAP 8** (build 262.8377) — [EAP download](https://www.jetbrains.com/rider/nextversion/). Other IntelliJ-based IDEs of the same build line work too; the plugin's core is IDE-agnostic.
- The **Agent Workbench** plugin (JetBrains Marketplace), matching the IDE build.
- A coding agent CLI: **Claude Code** or **Codex** (both verified; the workbench launches them).

## Install

1. Download `code-review-docent-<version>.zip` from the [latest release](https://github.com/kevingosse/docent/releases/latest) — don't unzip it.
2. In the IDE: **Settings → Plugins → ⚙ → Install Plugin from Disk…**, pick the zip, restart.

## First run

1. Open your project and open the **Code Review Docent** tool window (left sidebar).
2. Launch an agent session from **Air** (the Agent Workbench) — Chat or Terminal, either works. Docent injects everything it needs into the launch automatically — its protocol, and its own MCP endpoint (serving only the 6 `docent_*` tools; additive, your existing MCP configuration is untouched).
3. Ask the agent to start a code review, or click the session in the tool window. The review opens as an editor tab; step through sections with the plan rail or **Alt+.** / **Alt+,**.

## Troubleshooting

- **The agent doesn't know about the Docent** — check, in order: the panel isn't showing an MCP warning (it self-diagnoses and offers the fix); the agent was launched *from Air* (a plain-terminal agent doesn't get the Docent protocol); the agent's session is still open.
- **"Docent isn't responding" on a question** — the agent may be busy or its session closed; use the **Nudge** link on the notice, or reopen the session and click **Connect agent…**.
- **A workbench update broke something** — Docent self-checks its workbench integration at startup and raises a notification listing exactly what broke; update the plugin.

## Building from source

Requires a local 2026.3 JetBrains IDE that bundles **Air** (IntelliJ IDEA build 263.4739+; Air is the Agent
Workbench's new name):

```bash
# ~/.gradle/gradle.properties (or environment variables IDE_HOME / RIDER_HOME / AIR_PLUGIN)
ideLocalPath=/path/to/intellij-idea-2026.3
# optional: riderLocalPath=/path/to/rider  (runRider target / fallback base)
# optional: airPluginPath=/path/to/air-plugin  (compile against a standalone Air install instead of the bundled one)
```

```bash
./gradlew buildPlugin        # → build/distributions/code-review-docent-*.zip
./gradlew runIdea -PideaLocalPath=/path/to/intellij-idea-2026.3   # launch IDEA with the plugin loaded
```

Without a local IDE configured, the build downloads the IntelliJ IDEA Ultimate build named by `ideaVersion` in `gradle.properties` (this is what CI does).

## Design

The vocabulary: the **Trail** is the captured story of a change; the **Docent** is the live agent that performs it. See [`docs/DESIGN.md`](docs/DESIGN.md) for the full problem statement, principles, and architecture, and [`docs/STATUS.md`](docs/STATUS.md) for the current state.
