# Status — current state & what's left

Read **`docs/DESIGN.md`** (the why / principles / architecture) and **`CLAUDE.md`** (conventions,
build/run) first. This file is the short "where we are and what's next" snapshot — the *how it was
built* lives in the code and its comments, not here.

> Last established: 2026-07-02, plugin **0.3.1**. Everything under "Where we are" is **click-tested
> in a live Rider**, and the full loop has been run end-to-end on a couple of **real reviews in
> separate projects** (dogfooding on *this* repo is awkward — the agent authoring the change is the
> one under review). `docs/ASSESSMENT.md` (2026-07-01) predates this pass and inherited stale
> "not click-tested" claims from the previous STATUS — its judgment stands, its facts don't all.

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

1. **Review the agent's response-changes — the roughest edge in real use.** After "Complete review"
   dispatches the queued *requested changes*, the agent edits the code — and that delta, the code
   the reviewer most explicitly wants to verify, lands as a raw working-tree diff with no Trail, no
   narration, no continuity with the remark that prompted it. Close the loop in the same surface:
   e.g. a resolved/changed state on the original requested-change card with its edit attached, or a
   follow-up mini-Trail for the delta.
2. **The critic** (the differentiator): "Request code review" → spawn the different-model critic
   over the retained ACP transport; its findings land as a **visually distinct third comment layer**
   (author / critic / you — `CommentCard` already separates two, and the kit reserves the color).
   Its brief includes the Trail's claims, explicitly prompted to verify them against the diff and
   flag divergence.
3. **Surface agent tool calls / thoughts** in the UI — "trust via visible verification" is a design
   pillar that's currently invisible.

### Smaller backlog

- Robustness items from `docs/ASSESSMENT.md` §3–4 that survive this status pass: review state
  persistence across tab close / section switch (F1), unread-reply signal (F4), anchor
  re-resolution at load (F3), tests for the interval/anchor math (T3), single-sourcing the Trail
  schema text (T4). **Done in 0.3.2 (built, not yet click-tested):** T1 — a ~2.5-min liveness
  timeout on every posted remark replaces the infinite spinner with a "Docent isn't responding"
  notice + a **Nudge** link (re-pushes the retained event into the agent's chat via the notifier;
  a late reply still lands); T2 — `awb/DocentSeamCheck` verifies every reflectively-reached AWB
  seam once per IDE run and raises a balloon (new `Code Review Docent` notification group) listing
  what a workbench update broke, and seam-installation failures in `DocentWorkbenchSetup` now
  notify instead of being swallowed.
- `CODEX_MCP_SERVER_NAME` is hardcoded to `rider` — must match the user's `~/.codex/config.toml`;
  make it a setting for non-Rider IDEs.
- Comments on unchanged/folded lines in unified sit inside a collapsed context block until expanded.
- Multi-line comment ranges, and pinning a comment to the **before** side.
- Junie / OpenCode providers (no confirmed MCP path + delivery mode yet).

## Reference

We mined JetBrains' **`agent-workbench`** plugin (IntelliJ Community; Apache-2.0; platform 262) for
patterns. Its AI review is ACP-backed and streams structured findings, but renders them as a flat,
non-interactive list in the Problems tool window — that's the open lane for us. Borrowed: a finding
model with a stable `id` + line *range* + `reasoning` separate from `message`; stream-partial then
reconcile; a read-only permission policy; the protocol layer isolated in its own module.
