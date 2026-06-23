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
- Base platform: **builds against the locally-installed Rider** (build 262 / 2026.2), `since-build 252`,
  Java 21 toolchain. (Bumped from IC 2024.3 once the MCP integration needed `com.intellij.mcpServer`,
  which is bundled only in 2025+; building against the local Rider is zero-download and exact-match — see
  `build.gradle.kts`. Kotlin had to move 2.1→2.4 to read Rider 262's platform metadata.)
- Optional dependency on `com.intellij.mcpServer` (gated module `docent-mcp.xml`) for the MCP handoff;
  the core stays platform-clean (`com.intellij.modules.platform`) and loads without it.
- Native UI (Swing / IntelliJ UI DSL); code shown via real editor components (`EditorTextField` /
  embedded editors), **not** JCEF.
- `instrumentCode` and `buildSearchableOptions` are disabled (Kotlin-only, no custom Settings) — see
  the comments in `build.gradle.kts`.
- Build: `./gradlew buildPlugin` → `build/distributions/code-review-docent-*.zip`.
  Dev run: `./gradlew runRider` (launches local Rider with the plugin, no SDK download) then
  **Tools → Open Docent Review**. `./gradlew runIde` uses the IC sandbox but lacks C#/C++ nav.

## Vocabulary

Trail, Docent, section, trailhead, triage/attention-weight, composed view. (Definitions in the
`docs/DESIGN.md` glossary.)

## Conventions

- Docs live in `docs/`.
