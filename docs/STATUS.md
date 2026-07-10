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
> launch there (`AbstractMethodError`). The single artifact now spans both API generations —
> dual-mangled `contribute`, reflective provider getters, seam-check tripwire. Details:
> `docs/AWB-2026.3-COMPAT.md` (2026-07-10 update). Known degradation on 263.1445+: the session
> picker shows plain provider launches instead of AWB launch profiles.

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
  API map (`AWB-263-API-MAP.md`) is local-only, kept outside the repo.

**Known constraint:** on **.slnx** solutions the workbench's persisted session store has empty
thread lists (AWB bug), so the supported `AgentPromptLaunchers` push can't find the target thread.
Worked around by typing into the session's open chat-tab terminal (`AgentChatFileEditor.tab.sendText`,
reflectively) with the launcher push as fallback — this is the path that actually runs, and it works.

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
