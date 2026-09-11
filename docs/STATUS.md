# Status — current state & what's left

Read **`docs/DESIGN.md`** (the why / principles / architecture) and **`CLAUDE.md`** (conventions,
build/run) first. This file is the short "where we are and what's next" snapshot — the *how it was
built* lives in the code and its comments, not here.

> Last established: 2026-07-03, plugin **0.4.0** (first-wave release prep). Everything under
> "Where we are" is **click-tested in a live Rider** — EXCEPT the 0.4.0 onboarding items marked
> *(built, not yet click-tested)* below — and the full loop has been run end-to-end on a couple of
> **real reviews in separate projects** (dogfooding on *this* repo is awkward — the agent authoring
> the change is the one under review). `docs/ASSESSMENT.md` (2026-07-01) predates this pass and
> inherited stale "not click-tested" claims from the previous STATUS — its judgment stands, its
> facts don't all.

> **2026-07-10, 0.6.0:** Agent Workbench 263.1445 (IDEA 2026.3 dev line) changed several `air.*`
> signatures, and 0.5.2's stale launch-contributor registration was failing **every** AWB thread
> launch there (`AbstractMethodError`). 0.6.x spanned both API generations — dual-mangled `contribute`,
> reflective provider getters, seam-check tripwire. **Superseded by 0.7.0 below.**

> **2026-08-03, 0.7.0:** AWB `262.8665.20260723` (shipped with the Rider 2026.2.0.1 release, and pushed
> as a routine Marketplace update onto 0.6.4 installs) re-layered the namespace into
> `air.backend.*`/`air.frontend.*`/`air.shared.*` and **moved the launch EP interface**, which killed
> launch injection, both push channels, "start a new agent session" and the agent icons (the seam-check
> balloon fired on 2 of the 5 probes). Because the interface *moved*, one class can no longer implement
> two generations, so 0.7.0 is a **clean retarget** to that layout: dual-mangle + `src/awbStub` deleted,
> pushes on the public `AgentPromptBackendApi`, the launch-profile picker **re-ported** (custom profiles
> work again, no longer degraded), the editor→terminal walk now verified against the real classes and
> extracted to `AwbTerminalTab`. Details: `docs/AWB-2026.3-COMPAT.md` (2026-08-03 update).

> **2026-08-03, 0.7.1:** 0.7.0 had been aimed at the AWB build that happened to be installed — a
> *date-stamped nightly* (`262.8665.20260723`), which is built against that day's platform and dies on a
> released Rider: after the `262.8665.328 → .385` (2026.2 → 2026.2.0.1) update, every agent thread tab
> failed with `NoClassDefFoundError: …TransferableTerminalSessionFactory` (deleted from the platform's
> terminal frontend). The **release-line** build `262.8665.28` vendors what it needs and also drops the
> `intellij.platform.ide.impl.wsl` dependency that was excluding all the backend ACP modules, so the seam
> is retargeted there and `agentWorkbenchVersion` now pins a release-line build on principle. Delta
> absorbed: prompt-request/profile shape, the catalog+availability pipeline behind the launch-profile
> picker, and one extra hop in the editor→terminal chain (`AwbTerminalTab` now does a bounded field walk
> instead of hard-coding a shape). Details: `docs/AWB-2026.3-COMPAT.md` (0.7.1 section).
> **Built and javap-verified; not yet click-tested in a live review.**

> **2026-09-08, 0.8.0:** the plugin went dead on IntelliJ IDEA 2026.3 (263.4739) with its **bundled Air**
> (the Agent Workbench's new name). Two independent breaks, diagnosed from the Ultimate source:
> (1) Air's plugin id became `com.intellij.air` (2026-07-28, no alias), so the optional `docent-awb.xml`
> gated on `com.intellij.agent.workbench` never loaded — no seam, no seam-check balloon, silence;
> (2) Claude and Codex are now **"folded" ACP agents**: the default Chat surface launches them through the
> `claude-acp`/`codex-acp` adapters with an EMPTY terminal command, so `--append-system-prompt` /
> `--mcp-config` injection has nothing to attach to (the launch EP still fires, on nothing). Fix: the seam
> now depends on `com.intellij.air`, builds against the local IDEA 2026.3's bundled Air
> (`ideLocalPath` + `bundledPlugin("com.intellij.air")`), and serves **both Air surfaces**:
> Terminal (CLI in a tab) keeps the launch-contributor CLI injection, now skipped when the command is
> empty; ACP gets the `docent` MCP entry via Air's `acp.mcpServerProvider` EP (an HTTP `McpServer` in
> `session/new`, the same way Air adds `jetbrains_air_ide`) and the protocol via the `acpPromptSupplement`
> EP (full protocol appended wire-only to a thread's first turn, a one-line reminder afterwards — there is
> no per-session system-prompt hook on the ACP path). Both surfaces bake the Air thread id in as the
> sessionToken. Also absorbed: `AgentThreadsStateStore` moved to `air.backend.session.runtime.state`,
> `buildBuiltInLaunchProfiles` gained `preferTerminalSurface`, `launchProfileActionText` went
> Kotlin-internal. `DocentSeamCheck` now probes the two ACP EPs too. Details:
> `docs/AWB-2026.3-COMPAT.md` (0.8.0 section). **User-verified live on IDEA 2026.3 / Air 263.4739.0**
> (2026-09-08): the ACP Claude thread gets the `docent` MCP server and the protocol.

> **2026-09-09, 0.8.1:** "Connect agent…" threw on an IDEA 2026.3 nightly (263.4825):
> `runBlockingCancellable` is forbidden on the EDT, and every push into an agent thread (connect, start
> review, nudge, new-session launch) was a click handler blocking on Air's suspending launch API — on the
> ACP surface there is no live terminal to type into, so that fallback is now the *normal* path. Fix: the
> service's `pushToAgent` runs the `EventNotifier` on a pooled thread and hands the verdict back on the
> EDT; `nudge` and `AgentSessionLauncher.startSession` became callback-style; the UI shows a
> "Contacting…/Starting…" notice meanwhile. Same build also moved the seam: `AgentPromptLaunchRequest`
> is keyed by `workspaceId: SessionWorkspaceId` + `projectDirectory` instead of `projectPath` (taken from
> the store's workspace entry, else derived via `sessionWorkspaceIdFromBackendPath`). `DocentSeamCheck`
> now probes the request's field shape. Details: `docs/AWB-2026.3-COMPAT.md` (0.8.1 section).
> **User-verified on 263.4825** that the EDT exception is gone.

> **2026-09-09, 0.8.2:** with 0.8.1 the push ran but Air rejected every one with `PROVIDER_UNAVAILABLE`
> (idea.log: "push to thread acp:… not delivered (PROVIDER_UNAVAILABLE)"), so "Connect agent…" and
> "Start review" always showed "Couldn't message that session" even for a live thread. Cause: the synthesized
> launch profile carried only an agent id. Air resolves the profile to an exact **route** (agent + launch
> target + interaction surface) and looks the target thread up BY that route; a profile without target +
> surface has no route and fails before the thread is considered. Fix: `DocentEventNotifier` builds the profile
> from the target `AgentThread`'s stored route (`agentLaunchRouteOrNull()`), the recipe Air's code-review
> follow-up uses. **Never ran on the user's IDE** — see 0.8.3.

> **2026-09-11, 0.8.3:** the user's IDE had moved to IU-263.4953 and the 0.8.2 push died before reaching Air
> with `NoSuchMethodError: AgentPromptLaunchRequest.<init>(…)` (idea.log, swallowed into "Couldn't message that
> session"). Air added a trailing `preallocatedThreadId: String?` to the request; the Kotlin synthetic default
> ctor changed arity, so the 0.8.2 binary (compiled on 263.4825) no longer bound. Fix: rebuild against 263.4953
> (`ideLocalPath`); no source change needed. The 0.8.2 route-profile fix therefore ships first-run in 0.8.3.
> **User-verified on 263.4953:** "Start review" reaches the live ACP thread.

> **2026-07-10, 0.6.2:** resumed Codex tabs froze at "Loading MCP (x/y)" forever. Root cause
> (isolated standalone, no IDE): the Codex CLI deadlocks when a `resume --remote` *client* carries
> `mcp_servers.*` overrides its app-server doesn't have — and the workbench spawns that app-server
> itself with no contributor hook, so the server side can never match. Fix: skip the
> `mcp_servers.docent.*` injection on remote-resume commands (`LaunchInjection.injectCodexConfig`);
> `developer_instructions` still injected. Trade-off: resumed Codex sessions have no `docent_*`
> tools until the CLI bug is fixed upstream (reported). New sessions and Claude are unaffected.

## Where we are

The v0 loop is **built, click-tested, and used on real changes**. It is **diff-centric**: a
**Trail** (JSON) drives a Docent walkthrough in a main-pane editor tab. The plan rail lists
sections and, under the current section, the files each touches; selecting a file shows the IDE's
own **diff viewer** (side-by-side by default, unified switchable) with narration above it.
Interactive **inline comment cards** and a gutter **"+"** work directly on the diff in both
viewers. Sub-file focus dims out-of-scope hunks and labels each dimmed hunk by its owning section,
with a clickable hint to jump there.

Both halves of the loop, all verified live:

- **Capture side** — MCP tools (`docent_record_decision` / `docent_change_summary` /
  `docent_finalize_trail`) let the coding agent author a Trail as it works, written to
  `<repo>/.idea/docent/trail.json`. **On-demand review mode:** the agent keeps recording decisions
  (tagged with its `sessionToken`) and never auto-finalizes; the user triggers the review — in
  chat, or by clicking a live workbench session in the **nav rail's no-trail surface**, which lists
  sessions with their pending-decision counts and live-refreshes as decisions land. The decision
  log persists across IDE restarts (`<repo>/.idea/docent/decisions.json`) and spans the whole
  iteration, so the Trail covers the final design, not each intermediate step.
- **Review side** — the live **Docent** talks to the UI through `McpLoopBackend`: the workbench
  agent reached through the in-IDE MCP server. Conversation and inline-comment affordances are
  gated on a connected agent. IDE→agent delivery is a per-review NDJSON **`EventLog`** file the
  agent watches (Claude, `DeliveryMode.MONITOR`) or a blocking `docent_await_event` (Codex,
  `DeliveryMode.AWAIT`) — rationale in "Direction" below. Human remarks are classified as
  *questions* (answered in-thread) or *requested changes* (queued until **"Complete review"**
  dispatches them).
- **Resume, both directions** — from the agent (`docent_resume_review` reloads a trail and arms the
  loop) and from the UI (**Connect agent…** lists live workbench sessions with honest reachability
  and links the loaded trail to one).
- **Providers: Claude + Codex, both live-verified.** The connect/launch path is
  provider-parameterized (`awb/DocentLaunchContributor`). Codex gets the protocol via
  `-c developer_instructions`, has its 60s MCP timeout lifted via
  `-c mcp_servers.<name>.tool_timeout_sec=3600`, and uses AWAIT delivery.
- **UI (0.3.x redesign, per `docs/UI.md`)** — shared visual kit (`ui/DocentUi.kt`: one palette with
  a reserved critic color, single markup renderer, cards/chips, segmented progress, live-width
  wrapping components); trailhead as a title page with derived scope stats and visited-✓ route
  cards; persistent trail header (progress strip, "Section N of M", file chips with per-file ±);
  restyled section chat (tinted Docent cards, shared markup path, in-transcript thinking row,
  growing input); rail polish (file-type icons, comment counts, "n of m read" echo, inline
  notices); keyboard step nav (Alt+. / Alt+, / overview, shared with the diff toolbar).

Workbench integration ships as **optional modules** gated on `com.intellij.mcpServer` and
`agent-workbench`; the core stays platform-clean and loads without either.

- **First-run onboarding (0.4.0, built, not yet click-tested).** The silent new-user failure —
  agents never seeing the `docent_*` tools — is closed by giving Docent **its own MCP endpoint**.
  (a) `DocentMcpEndpoint` holds a long-lived `McpServerService.authorizedSession` on the IDE's
  *private* MCP server (separate port, per-IDE-run `IJ_MCP_AUTH_TOKEN`, runs regardless of the
  user-facing "Enable MCP server" setting) with a `TextMcpToolFilter` exposing ONLY the docent
  toolset — so agents get exactly 8 tools, not the public server's ~119, and nothing is duplicated
  when the user's own client config also registers the public server. (b) `DocentLaunchContributor`
  injects it per launch, ADDITIVELY (never `--strict-mcp-config`, no AWB registry keys): Claude gets
  `--mcp-config {"mcpServers":{"docent":{url, headers:{IJ_MCP_AUTH_TOKEN}}}}` (variadic, merges with
  all other MCP sources; skipped if AWB's own managed wiring put `--strict-mcp-config` on the
  command); Codex gets `-c mcp_servers.docent.url/.http_headers.IJ_MCP_AUTH_TOKEN/.tool_timeout_sec`
  (dotted `-c` paths CREATE the entry — both verified against the live CLIs). URL + token are
  resolved fresh per launch, so they can't go stale. No `~/.codex/config.toml` entry required
  anymore (a user `rider` entry, if present, still gets its timeout patched defensively).
  (c) Fallback ladder + honest surfaces: endpoint → public-server URL (`McpStreamUrlProvider`) →
  nothing; `McpPrereq`/`DocentMcpPrereqActivity` arm the endpoint at project open and only warn
  (nav-rail notice + balloon, one-click "Turn on the MCP server" = restore the fallback path) when
  BOTH rungs are unavailable. The MCP-server setting is no longer a first-run prerequisite.
- **Follow-up loop v1 (0.4.0, built, not yet click-tested).** On `review_completed` the agent is now
  instructed — in the envelope hint (compaction-proof), the protocol tails, and the toolset text —
  to record a decision per implemented reviewer request and announce the delta is reviewable; the
  standard on-demand start surface then hosts the follow-up review (see "What's left" #1).
- **Release engineering:** CI/release builds are pinned to **2026.2-EAP8-SNAPSHOT** (= build
  262.8377, the exact local dev base and AWB pin) instead of the rolling `2026.2-SNAPSHOT`; README
  rewritten as a first-user install/usage guide.
- **Single universal artifact for the air.* AWB API (0.5.2, released & user-verified on both 262
  EAP9+ and 2026.3).** 2026.3 renamed the whole AWB API (`com.intellij.air.*`, Session→Thread, both
  launch EPs) under the *same* plugin id — and that same rework then landed mid-262-line (Rider EAP9,
  build 262.8665). The air.* API is **byte-identical** across 262.8665 and 263.1174 for every type the
  seam touches (`javap -public` diff, 2026-07-06), and no AWB 263 exists on any public channel (so CI
  couldn't build a native 263 anyway). Both facts point one way: compile ONE binary against air.* and
  let each IDE supply its own AWB at runtime. The old `-PawbTarget=262|263` split, the `src/awb262|awb263`
  twin trees, and all `*263` gradle/CI knobs are gone; the AWB-touching seam is one `src/awb` tree, shared
  AWB-free code (incl. launch-injection surgery, `awb/LaunchInjection.kt`) stays in `src/main`. Ships as one
  zip, `since-build 262.8665` / `until-build 263.*` — the Marketplace serves it to any IDE in that range.
  History (0.5.0 dual-variant era, live 263 verification): `docs/AWB-2026.3-COMPAT.md`; the member-exact
  API map (`AWB-263-API-MAP.md`) is local-only, kept outside the repo. **Since 0.7.0 the *seam* inside
  that one artifact targets a single AWB generation** (the layered `262.8665.20260723` API) — the core
  surface still spans the whole build range.

**Known constraint:** on **.slnx** solutions the workbench's persisted session store has empty
thread lists (AWB bug), so the supported prompt-launch push (`AgentPromptBackendApi`) can't find the
target thread. Worked around by typing into the thread's open tab terminal
(`AgentThreadViewTerminalTab.sendText`, reached via `AwbTerminalTab`) with the launcher push as fallback —
this is the path that actually runs, and it works.

## Direction (standing decisions)

The live Docent runs **over ACP** (model-independent), integrated with the workbench **via MCP**
(full rationale in `docs/DESIGN.md` §6):

- **The Docent _is_ the coding agent** for now: it guides each section and answers questions with
  authentic context. The *critic* — a different-model, read-only reviewer — is an optional later
  pass, and is where the trust engine (calibrated opinions, claim auditing) lives.
- **Hand-rolled minimal ACP client** (NDJSON JSON-RPC over stdio), retained unwired in `acp/` as
  the foundation for the critic. The official SDK is the fallback.
- **Read-only posture during review:** auto-approve read/search/execute; reject edit/delete/move
  until "Complete review" dispatches the queued changes.
- **File-watch delivery, not a blocking MCP call**, for Claude: a blocking `docent_await_event`
  dies at Claude Code's stack of undocumented MCP timeouts (60s request, ~5min SSE idle). The IDE
  appends reviewer actions to the EventLog file; the agent's background-monitor tool picks them up
  outside any tool-call budget. AWAIT remains as the mode for providers with sane timeouts (Codex).
  The one push into the agent's thread is the UI-initiated *resume* — waking an idle agent is the
  only case a file watch can't cover.

## What's left

Ranked by what real use has actually surfaced:

1. **Review the agent's response-changes — the roughest edge in real use.** v1 shipped in 0.4.0:
   the agent records a decision per implemented request, and the on-demand surface hosts a
   follow-up review of the delta (the synthesized "Other changes" section absorbs the
   already-reviewed remainder). Still open — the real UI continuity: a resolved/changed state on
   the original requested-change card with its edit attached, and a baseline snapshot at
   "Complete review" so an uncommitted follow-up diffs against the reviewed state instead of HEAD.
2. **The critic** (the differentiator): "Request code review" → spawn the different-model critic
   over the retained ACP transport; its findings land as a **visually distinct third comment layer**
   (author / critic / you — `CommentCard` already separates two, and the kit reserves the color).
   Its brief includes the Trail's claims, explicitly prompted to verify them against the diff and
   flag divergence.
3. **Surface agent tool calls / thoughts** in the UI — "trust via visible verification" is a design
   pillar that's currently invisible.

### Smaller backlog

- **Pre-dispatch queue review** (reviewer request, 2026-07-03): on "Complete review", show the
  accumulated requested-change list and let the reviewer edit the wording, drop, or reorder items
  before they're dispatched — the queue text is the agent's marching orders and currently goes out
  sight-unseen, including the Docent's own paraphrasing of each request.

- Robustness items from `docs/ASSESSMENT.md` §3–4 that survive this status pass: unread-reply
  signal (F4), anchor re-resolution at load (F3), tests for the interval/anchor math (T3),
  single-sourcing the Trail schema text (T4). **F1 partially done in 0.4.2** (review-response,
  reported live in a real review): section chat transcripts now persist across section switches
  (durable model on `DocentReviewController.sectionChats`, replayed on rebuild, mirrored per
  streamed chunk); still open — replies streaming AFTER the panel is disposed are dropped (the
  turn sink lives on the panel), and other per-review UI state (scroll positions, expanded cards)
  still resets. **Also 0.4.2:** Ctrl+wheel prose zoom over the review pane (`DocentUi.fontScale`,
  persisted; IdeEventQueue dispatcher; embedded diff editors excluded — they keep the IDE's own
  zoom). **Done in 0.3.2 (built, not yet click-tested):** T1 — a ~2.5-min liveness
  timeout on every posted remark replaces the infinite spinner with a "Docent isn't responding"
  notice + a **Nudge** link (re-pushes the retained event into the agent's chat via the notifier;
  a late reply still lands); T2 — `awb/DocentSeamCheck` verifies every reflectively-reached AWB
  seam once per IDE run and raises a balloon (new `Code Review Docent` notification group) listing
  what a workbench update broke, and seam-installation failures in `DocentWorkbenchSetup` now
  notify instead of being swallowed.
- `CODEX_MCP_SERVER_NAME` (`rider`) is now defensive-only (0.4.0 injects Docent's own `docent`
  MCP entry, so tool visibility no longer depends on the user's `~/.codex/config.toml`) — it just
  patches the timeout on the user entry when one exists under that name; a setting would still be
  cleaner for non-Rider IDEs.
- Comments on unchanged/folded lines in unified sit inside a collapsed context block until expanded.
- Multi-line comment ranges, and pinning a comment to the **before** side.
- Junie / OpenCode providers (no confirmed MCP path + delivery mode yet).

## Reference

We mined JetBrains' **`agent-workbench`** plugin (IntelliJ Community; Apache-2.0; platform 262) for
patterns. Its AI review is ACP-backed and streams structured findings, but renders them as a flat,
non-interactive list in the Problems tool window — that's the open lane for us. Borrowed: a finding
model with a stable `id` + line *range* + `reasoning` separate from `message`; stream-partial then
reconcile; a read-only permission policy; the protocol layer isolated in its own module.
