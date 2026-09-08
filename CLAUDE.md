# CLAUDE.md — Code Review Docent

An AI-guided code review experience, delivered as a **native JetBrains (IDEA / Rider) plugin**. A
coding agent captures the *story* of a change (the **Trail**); a reviewing agent (the **Docent**,
ideally a different model) walks the human through it interactively in the IDE. The goal is to keep
humans in touch with codebases increasingly written by AI.

**Read `docs/DESIGN.md` first** — it holds the full problem statement, principles, and architecture.
This file is the working cheat-sheet.

## Status

The v0 **review surface** is built and runnable in Rider (main-pane Docent tab: plan rail →
rich narration → navigable editor cells → interactive inline comment cards), driven by a
Trail JSON. **Start each session by reading `docs/STATUS.md`** — it captures the current state and
what's left to build.

## Core principles (do not violate)

- **Present 100%, hide nothing.** Triage sets *attention weight + a stated reason*; it never removes
  a change from view.
- **The Docent presents; the human asks.** No AI→human quizzes. Engagement comes from the Docent
  taking opinionated, *calibrated* positions.
- **Carry only the non-reconstructable** — the *why*, not a restatement of the diff.
- **Mental-model retention is the metric**, not bugs caught or time saved.
- **Feel like the IDE.** Full structural navigation (go-to-def, find-usages) must work everywhere,
  including inside composed views. Hence native editor components, **never** a webview rendering dead
  text.
- **Engagement via agency, not gamification.** The human drives the pace; no autoplay.

## Architecture (script / performer / stage)

- **Trail** (script) — captured + synthesized story, produced by the coding agent; frozen.
- **Docent** (performer) — live ACP agent, ideally a *different* model; performs the script, answers
  questions, verifies claims against ground truth.
- **Plugin** (stage) — native JetBrains tool window; owns orchestration, the IDE owns the code.
- Agent connection over **ACP** (model-independent); JetBrains AI is an easy first integration, not a
  lock-in.

## Tech stack

- IntelliJ Platform plugin; Kotlin 2.4.0; Gradle 9.1.0; IntelliJ Platform Gradle Plugin 2.16.0.
- Base platform: **builds against a locally-installed 2026.3 IDE that bundles Air** (IntelliJ IDEA 263.4739,
  `ideLocalPath`), `since-build 262.8665` / `until-build 263.*`, Java 21 toolchain. Air (the Agent Workbench's new
  name, plugin id `com.intellij.air` since 2026-07-28) is BUNDLED in 263 IDEs and pinned to the exact IDE build, so
  the seam compiles against `bundledPlugin("com.intellij.air")`; `airPluginPath` overrides with a standalone
  install, `riderLocalPath` is the `runRider` target / fallback base (Rider 263 doesn't bundle Air yet). See
  `build.gradle.kts`.
- Optional dependency on `com.intellij.mcpServer` (gated module `docent-mcp.xml`) for the MCP handoff;
  the core stays platform-clean (`com.intellij.modules.platform`) and loads without it.
- Native UI (Swing / IntelliJ UI DSL); code shown via real editor components (`EditorTextField` /
  embedded editors), **not** JCEF.
- `instrumentCode` and `buildSearchableOptions` are disabled (Kotlin-only, no custom Settings) — see
  the comments in `build.gradle.kts`.
- **Single artifact, one `src/awb` seam, one Air generation.** Shared Air-free code stays in `src/main`; the
  seam (gated `docent-awb.xml` on plugin id `com.intellij.air`) tracks exactly one Air build — as of 0.8.0
  **263.4739.0** (bundled in IDEA 2026.3). The core review surface is platform-clean and loads across the whole
  since/until range; on a 262 IDE (Air still id `com.intellij.agent.workbench`) the seam simply doesn't load.
- **Air has TWO session surfaces and the Docent must serve both.** Terminal (CLI in a terminal tab): the
  `threadLaunchContributor` EP appends `--append-system-prompt` / `--mcp-config` (Claude) or `-c` overrides
  (Codex) to the command line. ACP (the default Chat route for Claude/Codex since 263.x, "folded" agents): the
  launch spec has an EMPTY command, so the `acp.mcpServerProvider` EP contributes the `docent` HTTP MCP server to
  `session/new` and the `acpPromptSupplement` EP appends the protocol to the thread's first turn (reminder on later
  turns). Both bake the Air thread id in as the sessionToken. Rules: **javap the installed `air-plugin/lib/**.jar`
  and re-verify the seam whenever Air or the IDE updates** — `DocentSeamCheck` reports at runtime what a newer
  build broke. History + per-generation FQN maps: `docs/AWB-2026.3-COMPAT.md`.
- Build: `./gradlew buildPlugin` → `build/distributions/code-review-docent-<version>.zip`.
  Dev run: `./gradlew runIdea -PideaLocalPath=...` (the 2026.3 IDEA with bundled Air) or `./gradlew runRider`
  (local Rider; no Air seam there until Rider bundles it), then **Tools → Open Docent Review**.

## Vocabulary

Trail, Docent, section, trailhead, triage/attention-weight, composed view. (Definitions in the
`docs/DESIGN.md` glossary.)

## Conventions

- Docs live in `docs/`.
