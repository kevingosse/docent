# Status — current state & what's left

Read **`docs/DESIGN.md`** (the why / principles / architecture) and **`CLAUDE.md`** (conventions,
build/run) first. This file is the short "where we are and what's next" snapshot — the *how it was
built* lives in the code and its comments, not here.

## Where we are

The v0 **review surface** is built and runs in Rider. It is **diff-centric**: a **Trail** (JSON)
drives a Docent walkthrough that opens as a main-pane editor tab. The plan rail lists sections and,
under the current section, the files it touches; selecting a file shows the IDE's own **diff viewer**
(side-by-side by default, unified switchable) with narration above it. Interactive **inline comment
cards** and a gutter **"+"** work directly on the diff, in both viewers. Sub-file focus dims
out-of-scope hunks and labels each dimmed hunk by its owning section.

Both halves of the loop exist:

- **Review side** — the live **Docent** talks to the UI through one backend, `McpLoopBackend` behind
  `ui/SectionConversationPanel`: the **workbench agent** reached through the in-IDE MCP server. The
  conversation and inline-comment affordances are **gated on a connected agent** — when none is
  connected the input is disabled (no self-spawned fallback). The `acp/AcpClient` +
  `acp/DocentAgentSession` ACP self-spawn transport is **retained, unwired**, as the foundation for
  the future different-model critic (see "What's left").
- **Capture side** — MCP tools (`docent_record_decision` / `docent_change_summary` /
  `docent_finalize_trail`) let the coding agent author a Trail as it works, written to
  `<repo>/.idea/docent/trail.json`. There is **no default trail**: a review loads from an explicit path
  (the MCP handoff) or the **Load trail…** picker in the nav tool window.
- **Resume an unfinished review** — both directions: from the agent, `docent_resume_review` reloads a
  trail by path and arms the loop; from the UI, **Connect agent…** lists the live workbench Claude
  sessions and links the loaded trail to one (pushing it a "resume" message). Either way the picked
  session becomes the connected Docent.

Workbench integration ships as **optional modules** gated on the `com.intellij.mcpServer` and
`agent-workbench` plugins; the core stays platform-clean and loads without either.

## Direction (standing decisions)

The live Docent runs **over ACP** (model-independent), integrated with the workbench **via MCP** (full
rationale in `docs/DESIGN.md` §6):

- **Hand-rolled minimal ACP client** (NDJSON JSON-RPC over stdio), not the official SDK — keeps us
  platform-clean with zero new deps. The SDK is the fallback.
- **Read-only posture during review:** auto-approve read/search/execute tool calls; reject
  edit/delete/move until the review is completed.
- **The Docent _is_ the coding agent**, talking to the UI directly: it guides each section and answers
  questions. Human remarks are classified as *questions* (replied in-thread) or *requested changes*
  (**queued**, read-only, until **"Complete review"** dispatches them). A code review is an *optional*
  pass by a **different-model critic** that drops distinct inline comments.
- **File-watch delivery, not a blocking MCP call** for IDE→agent messaging — a blocking `docent_await_event`
  dies at Claude Code's stack of undocumented MCP timeouts (60s request, ~5min SSE idle), which no env knob
  reliably lifts. So the IDE appends each reviewer action to a per-review NDJSON **`EventLog`** file
  (`<repo>/.idea/docent/events-*.ndjson`); the agent watches it with its background-monitor tool, so events arrive
  as chat notifications **outside any MCP tool-call budget** — the only MCP traffic left is the agent's short
  replies. The watch self-exits on `review_completed`. A blocking `docent_await_event` remains as the inbound
  path for a future provider with sane MCP timeouts (`DeliveryMode.AWAIT`). The one thing that still **pushes**
  into the agent's thread is the UI-initiated *resume* ("Connect agent…") — waking an idle agent is the only
  case a file watch can't cover; that push hands it the watch command.

## What's left

The optional critic and the surrounding polish:

- **Three comment sources, visually distinct** on the diff: author/Trail, critic, you (`CommentCard`
  already separates docent/you — add the critic style + relabel author).
- **Optional "Request code review"** → spawn the different-model critic → its inline comments land as
  the distinct critic layer.
- Surface agent **tool calls / thoughts** in the UI (trust via visible verification); make the agent
  command configurable.

- **Review the agent's response-changes.** After **"Complete review"** dispatches the queued *requested
  changes*, the agent edits the code — but there's no structured way to review *that* delta: you fall back
  to a raw working-tree diff with no Trail, no narration, no continuity with the remark that prompted it.
  Close the loop so the response edits are reviewable in the same surface (e.g. a follow-up mini-Trail for
  the delta, or a resolved/changed state on the original "requested change" card with its edit attached).

- **On-demand review mode — BUILT 0.2.15, partially click-tested.** The protocol no longer tells the agent to
  auto-finalize the moment a change is complete; it **keeps recording decisions** (`docent_record_decision`,
  now tagged with the agent's `sessionToken`) and only finalizes when the user explicitly asks — in chat, or
  by clicking a session in the **nav rail's no-trail surface**. That surface (what used to just say "No trail
  loaded") is now the on-demand trigger: it shows the recorded-decision count and lists the live workbench
  sessions as clickable rows, each annotated with its **pending-decision count** (a pick hint; any session is
  selectable). Clicking one pushes a `START_REVIEW` event to that session through the existing
  `AgentPromptLaunchers` path and shows a "preparing…" line; the agent then runs `docent_change_summary` →
  `docent_finalize_trail`, which arms/opens the review here as before. The list live-refreshes as decisions are
  recorded (`DecisionLog.onUpdated`). The accumulated decision log spans the whole iteration, so the eventual
  Trail covers the final design, not each intermediate step. **Survives restarts:** the decision log is
  persisted to `<repo>/.idea/docent/decisions.json` and reloaded on open (consumed/deleted by finalize/clear),
  and `DocentWorkbenchSetup` nudges the rail to re-list once the workbench seams install after a restart (so it
  no longer sticks on "Agent Workbench isn't available"). The no-trail surface **word-wraps** (HTML
  `JBLabel`s with width-baked HTML), and **"Complete review"** is hidden unless a review is active. The
  START_REVIEW message is delivered by **typing it into the session's open chat-tab terminal**
  (`AgentChatFileEditor.tab.sendText`, reflectively — the same path the workbench's own file-drop/initial-message
  dispatch use), with the supported `AgentPromptLaunchers` push as a fallback for idle/closed tabs. The launcher
  push alone fails on **.slnx** solutions: their persisted session store has empty thread lists (the
  `awb-slnx-session-listing-bug`), so `findPromptTargetThread` never finds the thread (`TARGET_THREAD_NOT_FOUND`)
  even though we can list it from the live open tab. The same terminal-send path also backs "Connect agent…".

### Smaller backlog

- **Multi-agent provider support — Claude + Codex wired (0.2.3); other providers still open.** The
  whole live/connect path is now provider-parameterized rather than hardwired to Claude. Codex differs
  from Claude in two ways, both handled at launch (`awb/DocentLaunchContributor`): it has no
  background-watch tool, so it uses **`DeliveryMode.AWAIT`** (blocks on `docent_await_event`) instead of
  watching the EventLog file; and it has no `--append-system-prompt`, so the Docent protocol is injected
  via **`-c developer_instructions=<protocol>`** (verified non-destructive) and the 60s MCP tool-call
  timeout that would kill a quiet await is lifted via **`-c mcp_servers.<name>.tool_timeout_sec=3600`**.
  The provider is carried through `agentProvider` on the service → the push notifier
  (`awb/DocentEventNotifier`), the "Connect agent…" picker (`awb/WorkbenchSessionDirectory` lists
  Claude+Codex), and "Start a new … session" (`awb/WorkbenchAgentLauncher`, per-provider entries).
  **Known constraints / TODO:** the Codex MCP server name is hardcoded to `rider`
  (`CODEX_MCP_SERVER_NAME`) — it must match the user's `~/.codex/config.toml` entry pointing at this IDE;
  make it a setting for non-Rider IDEs. Junie / OpenCode aren't wired (no confirmed MCP path + delivery
  mode). Built 0.2.3, **not yet click-tested with a live Codex session.**
- **Comments on unchanged/folded lines in unified** sit inside a collapsed context block until expanded
  (`showWhenFolded = false`); seeded comments are on changed lines so they show today.
- **Multi-line comment ranges** (comments pin to a single line; the "+" keys off the caret line, not a
  selection) and pinning a comment to the **before** side (only the after side carries comments now).
- **UI redesign — BUILT 0.3.0, not click-tested.** The decided direction lives in `docs/UI.md`; all six
  steps are implemented: a shared visual kit (`ui/DocentUi.kt` — one palette incl. a reserved critic
  color, the single markup renderer, rounded cards/chips, segmented progress, wrapping-text and
  empty/loading/thinking components); the trailhead as a title page (subject `h1`, derived scope line
  via `GitChangeSet.numstat`, route cards with visited ✓, default-styled start/continue button, re-laid
  on resize); a persistent trail header over both section views (progress strip + "Section N of M ·
  headline" + file-type chips with per-file ±, current chip filled); the section chat restyled (Docent
  messages on tinted rounded cards with its icon, streamed replies through the shared markup path — no
  more raw `<code>` tags — in-transcript "thinking" row fed by backend statuses, growing input, Send
  enabled only with text); rail polish (file-type icons + comment counts on file rows, visited ✓ icons,
  "n of m read" progress echo under the subject, inline warning notices replacing the modal info
  dialogs); and keyboard step nav (`Docent.NextStep`/`PrevStep`/`Overview` registered actions, Alt+. /
  Alt+,, reused by the diff toolbar so tooltips show the shortcuts). This addressed the trailhead's
  "instinctive scope" item and consolidated the three bespoke text-wrapping mechanisms into the kit.
  **0.3.1** finished the wrapping consolidation: the rail's baked-width `wrapHtml`/`contentWidth`
  machinery is gone — every row (subject, sections, files, session/connect rows, card titles) renders
  through `DocentUi.WrappingHtmlPane` / `DocentNavPanel.iconTextRow`, which read their wrap width from a
  *live* provider at each layout pass, and every wrapping container computes its max height dynamically.
  Also: "Load trail…" now opens in `.idea/docent/` (where trails are written).

## Reference

We mined JetBrains' **`agent-workbench`** plugin (IntelliJ Community; Apache-2.0; platform 262) for
patterns. Its AI review is ACP-backed and streams structured findings, but renders them as a flat,
non-interactive list in the Problems tool window (no inline-on-diff, no conversation) — that's the open
lane for us. Borrowed: a finding model with a stable `id` + line *range* + `reasoning` kept separate
from `message`; stream-partial then reconcile; a read-only permission policy; the protocol layer
isolated in its own module.
