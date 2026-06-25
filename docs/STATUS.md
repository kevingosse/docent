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

### Smaller backlog

- **Multi-agent provider support (currently Claude-only).** The workbench hosts several agents (Claude,
  Codex, Junie, …), but the whole live/connect path is hardwired to Claude: the launch contributor only
  injects the Docent protocol for `CLAUDE` (`awb/DocentLaunchContributor`, Codex is a deferred no-op),
  the push notifier launches with `provider = CLAUDE` (`awb/DocentEventNotifier`), the "Connect agent…"
  picker filters the session directory to Claude threads (`awb/WorkbenchSessionDirectory`), and
  "Start a new agent session" launches a Claude session (`awb/WorkbenchAgentLauncher`). Generalize:
  carry the provider through `AgentSessionInfo`, let the picker offer other providers, and confirm each
  one can reach the `docent_*` MCP tools (Codex MCP wiring was unconfirmed — see the launch contributor).
- **Comments on unchanged/folded lines in unified** sit inside a collapsed context block until expanded
  (`showWhenFolded = false`); seeded comments are on changed lines so they show today.
- **Multi-line comment ranges** (comments pin to a single line; the "+" keys off the caret line, not a
  selection) and pinning a comment to the **before** side (only the after side carries comments now).
- Polish: narration/card text wrapping, plan-rail headline wrapping when narrow, the trailhead's
  "instinctive scope" feel.

## Reference

We mined JetBrains' **`agent-workbench`** plugin (IntelliJ Community; Apache-2.0; platform 262) for
patterns. Its AI review is ACP-backed and streams structured findings, but renders them as a flat,
non-interactive list in the Problems tool window (no inline-on-diff, no conversation) — that's the open
lane for us. Borrowed: a finding model with a stable `id` + line *range* + `reasoning` kept separate
from `message`; stream-partial then reconcile; a read-only permission policy; the protocol layer
isolated in its own module.
