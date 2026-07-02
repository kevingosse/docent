# Assessment — findings, recommendations, and plan

> **Date:** 2026-07-01 · **Scope:** full read of `docs/` and the entire plugin source (~6,200 lines).
> A point-in-time external review of the project: what's strong, what's at risk, and a concrete
> plan for the next weeks. Companion to `docs/STATUS.md` (which tracks build state, not judgment).

---

## 1. Verdict on the concept

**Strong and genuinely differentiated.** Every AI review tool on the market (CodeRabbit, Greptile,
Copilot review, JetBrains' own workbench review) competes on *defect detection* — a crowded lane
with diminishing returns as coding agents improve. Nobody is building for **comprehension
retention**. "Mental-model retention is the metric" is a clear-headed, contrarian, and correct bet:
the developer who rubber-stamps agent PRs for six months genuinely can't reason about their own
codebase anymore, and that cost compounds.

**The moat is authoring-time capture.** The "why" — alternatives rejected, constraints hit,
surprises — evaporates when the agent session ends. A post-hoc review bot structurally cannot
recover it; only the tool that was present during authoring has the story. "Trace during,
synthesize after" (DESIGN §7) is the right mechanism, and the implementation honestly enforces it
(e.g. the finalize warning when zero decisions were recorded).

**Execution matches the principles unusually well.** "Present 100%, hide nothing" is mechanical,
not aspirational (`appendOtherChangesSection` guarantees every changed line is reachable, including
leftover hunks of partially-narrated files). The real-editor substrate — dimming overlay, inline
cards, section-scoped F7, Ctrl-click in the unified diff — delivers "feels like the IDE" in a way a
webview never could.

## 2. The three biggest risks

### 2.1 Capture compliance is the whole ballgame
Everything downstream assumes the agent reliably calls `docent_record_decision` with calibrated
content, mid-task, across compactions, after being told once in an injected system prompt. Agents
are notoriously bad at sustained optional side-channel tool use, and the client-side tool-deferral
problem is already documented (`DocentMcpToolset.alwaysIncluded`). If capture rate is low, the
Trail degrades into post-hoc rationalization — exactly what §7 exists to prevent.

**Mitigation:** at finalize time, **mine the session transcript** (Claude Code's JSONL history is a
complete log of what actually happened) as a second source for synthesis. Recorded decisions stay
the curated signal; the transcript keeps the narrative honest when the agent forgot to record.
Compliance failures then degrade gracefully instead of silently corrupting the artifact.

### 2.2 Author-as-Docent softens critical distance until the critic exists
The pivot to "the Docent *is* the coding agent" was pragmatically right (authentic answers, native
feedback loop), but today's product is the author giving a guided tour of its own work — an author
that will instinctively defend its choices. The design's trust engine — calibrated opinions, the
audit mechanic, claims checked against ground truth — lives almost entirely in the **unbuilt
critic**. Right now the only audit is anchor validation in `docent_finalize_trail`. Until the
critic ships, this is a (very good) narrated demo, not yet a review.

### 2.3 The artifact is ephemeral and machine-local
The Trail lives in git-ignored `.idea/docent/`; comment threads live in
`DocentPanel.threadsByPath` (wiped when the review tab closes); section conversations are disposed
on section switch (`rebuildSection` → `disposeSection`), so visiting section 2 and returning to
section 1 loses its chat. Even solo that's fragile. For the team story — the Trail travels with the
PR and a reviewer who *didn't* command the agent gets the walkthrough — the Trail must become a
portable, durable artifact. No need to build the team story now, but don't design away from it.

## 3. Technical findings

| # | Finding | Where |
|---|---------|-------|
| T1 | **No liveness behind "● Connected".** `reviewActive` is set at arm time and never verified. A dead agent watch produces an infinite "Docent is replying…" spinner; the reviewer has no way to tell "thinking" from "gone". | `DocentReviewService`, `CommentCard.waitingRow`, `SectionConversationPanel` |
| T2 | **Reflective AWB seams break silently.** `@Internal` classes reached by reflection (`AgentChatVirtualFile` FQN match, `getProvider-<hash>` prefix scan, `sendText` accessibility bypass). Accepted risk, but a workbench update turns into "mysteriously nothing happens". | `DocentEventNotifier`, `WorkbenchSessionDirectory` |
| T3 | **Zero tests**, including for pure interval/anchor math that fails silently (a comment pinned one line off just looks like agent sloppiness). | `subtractRanges`/`splitOut`, `mergeSpans`, `resolveComment`, `stepAfter`/`stepBefore`, `ReviewEventEnvelope` |
| T4 | **Trail schema stated in three places** that will drift: the data classes, the `guidance` string in `docent_change_summary`, and the `docent_finalize_trail` description. | `trail/Trail.kt`, `mcp/DocentMcpToolset.kt` |
| T5 | **Protocol prompt is a tax on every session** (~600 words + a mandatory tool-search preamble injected into every launch, Docent-relevant or not). | `awb/DocentProtocolPrompt`, `awb/DocentLaunchContributor` |
| T6 | **HTML trust boundary.** `CommentCard` whitelists tags (good); `DocentPanel`/`SectionConversationPanel` feed agent-authored HTML straight into `JEditorPane`. Fine locally; a real issue the day Trails travel with PRs. | `ui/DocentPanel.htmlPane`, `ui/SectionConversationPanel.addDocentHtml` |
| T7 | Minor: watch command re-reads the log with `sed -n Np` each second (O(n²), fine at review scale); `CODEX_MCP_SERVER_NAME` hardcode (already TODO'd); `changedLineRanges` computed twice per file (controller + finalize) with no shared cache. | `EventLog.watchCommand`, `awb/DocentLaunchContributor`, `trail/GitChangeSet` |

Positives worth keeping as-is: the single-source event envelope (`ReviewEventEnvelope`), the
agent-actionable validation errors in finalize (schema self-correction loop), `anchorText`
resolution instead of trusting agent line numbers, the delivery-mode abstraction (Monitor/Await),
honest reachability in the session picker, and the comment discipline throughout the codebase.

## 4. Functional findings

| # | Finding |
|---|---------|
| F1 | **Review state doesn't survive.** Closing the review tab discards reviewer comment threads; switching sections discards that section's conversation. Both should live for the life of the review (service-level or a sidecar file next to the trail — the latter also makes reviews resumable across restarts with their discussion intact). |
| F2 | **No coverage nudge at synthesis.** Finalize warns about anchors on *unchanged* files; the inverse is missing — changed files no recorded decision touches. Surfacing that in `docent_change_summary` lets the agent consciously decide "mechanical → Other changes" vs "there was a why here I never recorded". |
| F3 | **Anchors go stale.** `anchorText` is resolved only at finalize; the working tree moves (especially after the agent implements queued changes). Re-resolve at trail load → self-healing anchors, and a step toward blob-hash durability. |
| F4 | **No unread-reply signal.** If the Docent answers a comment while the reviewer is in another section, nothing indicates it. |
| F5 | **Review-the-response gap** (already in STATUS): after "Complete review", the changes the reviewer *explicitly requested* — the ones they most want to verify — land as an unreviewable raw diff. Continuity break at the highest-trust moment. |
| F6 | Existing backlog confirmed as right: before-side comments, multi-line ranges, tool-call/thought visibility. Of these, **tool-call visibility ranks highest** — "trust via visible verification" is a design pillar that's currently invisible. |

## 5. UI recommendations

- **Trailhead as a scope map.** Render each section as a card with *derived* stats — files touched,
  +X/−Y lines, comment count — computed from git so the Trail file stays minimal (consistent with
  the standing triage-is-narration decision; nothing is authored, everything is derived). This is
  the book-ToC "instinctive scope" feel of DESIGN §9, nearly free.
- **Navigable code references in narration.** `<code>` spans that resolve to the section's anchors
  (click → open that file's diff at that line). The single biggest "feels magical" upgrade
  available; extends "feel like the IDE" into the prose itself.
- **Keyboard flow.** Global shortcuts for next/prev review step (they exist only as diff-toolbar
  buttons). Agency is a pillar; keyboards are agency.
- **Progress echo in the review pane.** The rail has ✓/○; the main pane should persistently show
  "Section 3 of 7" (the summary bar has it, the file diffs don't).
- **Consolidate the Swing wrapping machinery.** `DocentNavPanel` alone has three bespoke solutions
  to "make text wrap in a box" (`wrapHtml` width-baking, `WrappingMessageArea`,
  `cardContentWidth`). Extract a small shared kit (wrapped label, card, clickable row) before the
  next surface is built.

## 6. Plan for the next weeks

Ordering rationale: dogfooding first because the north-star metric can only be *felt* — everything
else gets re-prioritized by what a week of real use breaks. Then the critic, because it is the
differentiator, the trust engine, and the demo-maker. Then the trust/robustness work that real use
will almost certainly demand.

### Week 1 — Dogfood the full loop + stop-the-bleeding robustness
- Use the Docent end-to-end on every real change for a week (author → record → finalize → walk →
  discuss → queue → complete). Keep a running friction log; re-rank everything below against it.
- **T1: liveness.** Reply timeout on `postEvent` sinks (2–3 min → "The Docent isn't responding —
  nudge / reconnect" affordance replacing the spinner). Cheap heartbeat if it falls out naturally.
- **F1 (partial): stop losing state.** Move comment threads + section transcripts from panel-level
  to service-level so tab close / section switch no longer destroys them.
- Click-test the Codex path (STATUS: wired 0.2.3, never live-tested) — or consciously park it.

### Week 2–3 — The critic (the differentiator)
- "Request code review" → spawn the different-model critic over the retained ACP transport
  (`AcpClient` + `DocentAgentSession`, kept for exactly this).
- Read-only always; its findings land as a **visually distinct third comment layer**
  (author / critic / you) on the diff — `CommentCard` already separates two of the three.
- Seed the audit mechanic: the critic's brief includes the Trail's claims, and it is explicitly
  prompted to verify claims against the diff and flag divergence. The author-claim-vs-critic-finding
  gap is the product's signature moment; make sure the UI shows them side by side.
- Surface the critic's tool calls / verification steps in the UI (F6) — trust via visible
  verification, delivered where it matters first.

### Week 4 — Close the loop + capture honesty
- **F5: review-the-response.** Resolved/changed state on each queued-change card with its delta
  attached (minimal version), so the requested changes are reviewable in the same surface.
- **Capture backstop (risk 2.1):** prototype transcript mining at finalize — hand the synthesis
  pass the session transcript alongside the recorded decisions; measure how often it recovers
  decisions the agent never recorded.
- **F2: coverage nudge** in `docent_change_summary` (changed files with no covering decision).

### Ongoing / opportunistic (slot into gaps)
- **T3:** unit tests for the interval/anchor/step math (≈ a day, high regression value).
- **T2:** one-shot reflective-seam self-check when the AWB module loads; notify loudly on breakage.
- **T4:** single-source the Trail schema text.
- **F3:** re-resolve `anchorText` at trail load.
- UI: trailhead scope cards, navigable narration refs, keyboard step nav, progress echo.
- **T5:** slim the injected protocol to a pointer + ship the full protocol as a repo skill.
- **T6:** sanitize narration/reply HTML before the Trail ever travels beyond the local machine.

### Explicitly deferred (don't start yet)
- Team/PR portability of the Trail (design-compatible, not built).
- Junie / OpenCode providers.
- Activity-adaptive narration (DESIGN §9 stretch).
- Composed views (DESIGN §9) — revisit after the critic ships; it's the other "no diff tool can do
  this" primitive, but the critic buys more trust per week of work.

## 7. Bottom line

Right problem, right metric, and a moat (authoring-time capture) that post-hoc tools can't
replicate; engineering quality well above prototype norm. The two things standing between
"impressive prototype" and "product with a claim" are the **critic** (which makes it a review
instead of a tour) and **capture reliability** (which makes the Trail trustworthy instead of
decorative). Spend the next cycle on those two plus a week of honest dogfooding, and let the
polish backlog be re-ranked by whatever that week breaks.
