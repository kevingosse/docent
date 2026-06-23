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

- **Review side** — the live **Docent** talks to the UI. Two backends sit behind
  `ui/SectionConversationPanel`: the **workbench agent** reached through the in-IDE MCP server (the
  primary path), and a **self-spawned ACP agent** (`acp/AcpClient` + `acp/DocentAgentSession`) as the
  no-workbench fallback.
- **Capture side** — MCP tools (`docent_record_decision` / `docent_change_summary` /
  `docent_finalize_trail`) let the coding agent author a Trail as it works, written to
  `<repo>/.docent/trail.json`. There is **no default trail**: a review loads from an explicit path
  (the MCP handoff) or the **Load trail…** picker in the nav tool window.

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
- **Push, not poll** for IDE→agent messaging — long-polling an `await_event` tool is token-expensive
  against a coding agent's short HTTP MCP timeout, so the IDE pushes into the existing session instead.

## What's left

The optional critic and the surrounding polish:

- **Three comment sources, visually distinct** on the diff: author/Trail, critic, you (`CommentCard`
  already separates docent/you — add the critic style + relabel author).
- **Optional "Request code review"** → spawn the different-model critic → its inline comments land as
  the distinct critic layer.
- Surface agent **tool calls / thoughts** in the UI (trust via visible verification); make the agent
  command configurable.

### Smaller backlog

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
