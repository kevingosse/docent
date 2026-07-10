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
- Base platform: **builds against the locally-installed Rider** (build 262 / 2026.2 EAP9+), `since-build 262.8665`,
  Java 21 toolchain. (Bumped from IC 2024.3 once the MCP integration needed `com.intellij.mcpServer`,
  which is bundled only in 2025+; building against the local Rider is zero-download and exact-match — see
  `build.gradle.kts`. Kotlin had to move 2.1→2.4 to read Rider 262's platform metadata.)
- Optional dependency on `com.intellij.mcpServer` (gated module `docent-mcp.xml`) for the MCP handoff;
  the core stays platform-clean (`com.intellij.modules.platform`) and loads without it.
- Native UI (Swing / IntelliJ UI DSL); code shown via real editor components (`EditorTextField` /
  embedded editors), **not** JCEF.
- `instrumentCode` and `buildSearchableOptions` are disabled (Kotlin-only, no custom Settings) — see
  the comments in `build.gradle.kts`.
- **Single universal artifact** since 0.5.2: 2026.3 renamed the entire AWB API (every type moved to
  `com.intellij.air.*`, `Session`→`Thread`), and that rework then landed mid-262-line too — Rider EAP9
  (build 262.8665) carries the same `air.*` API. So the old `awb262`/`awb263` split collapsed into one
  `src/awb` seam compiled against the `air.*` API, shipped as one zip with `since-build 262.8665`,
  `until-build 263.*` — it loads on 2026.2 EAP9+ *and* all of 2026.3. (There's also no CI-buildable
  native 263: no AWB 263 exists on any public channel, so a single air.* binary was the only way to
  cover 263 anyway.) Shared AWB-free code stays in `src/main`.
- **The air.* API diverged again at AWB 263.1445** (0.6.0): `AgentThreadTerminalLaunchSpec`→
  `AgentThreadLaunchSpec`, `AgentThreadProvider`→`AgentId`, provider registry→`AgentRegistry`. The one
  artifact still covers both generations: `DocentLaunchContributor` carries a hand-mangled second
  `contribute` (backtick-named, descriptor via the compile-only `src/awbStub` stub, reflective body),
  and the other seams read the provider getters reflectively by mangle-prefix. Full story:
  `docs/AWB-2026.3-COMPAT.md`; the member-exact API map (`AWB-263-API-MAP.md`) is kept **outside the
  repo** (local-only, next to the compile-classpath dir).
- Build: `./gradlew buildPlugin` → `build/distributions/code-review-docent-<version>.zip`.
  Dev run: `./gradlew runRider` (launches local Rider with the plugin, no SDK download) then
  **Tools → Open Docent Review**. `./gradlew runIde` uses the IC sandbox but lacks C#/C++ nav.

## Vocabulary

Trail, Docent, section, trailhead, triage/attention-weight, composed view. (Definitions in the
`docs/DESIGN.md` glossary.)

## Conventions

- Docs live in `docs/`.
