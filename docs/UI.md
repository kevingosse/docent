# UI redesign — decisions

> **Date:** 2026-07-02 · **Scope:** the review surfaces (`ui/`), assessed against DESIGN §9.
> This is a *decided direction*, not an options menu. Companion to `docs/ASSESSMENT.md` §5
> (which listed UI ideas; this ranks, commits, and adds the visual-identity layer it lacked).

---

## 1. Diagnosis

The engineering under the UI is strong — real diff viewers, dimming, inline cards, native nav.
The *presentation* undersells it. Concretely:

- **The product has a great metaphor and no visual identity.** "Docent", "Trail", "trailhead" —
  a museum tour — but the screen renders it as gray `JBLabel`s, stock `JButton`s, and ad-hoc
  colors. The Docent blue and the reviewer green are copy-pasted constants in *two* files
  (`SectionConversationPanel`, `CommentCard`); the "go" green appears again in `DocentNavPanel`
  under two names. There is no icon, no typography scale, no shared card/chip vocabulary.
- **The first screen is the weakest screen.** The trailhead (`DocentPanel.showOverview`) is a
  default Swing button in a `FlowLayout` corner and a wall of body-size text. DESIGN §9 calls the
  trailhead "instinctive scope — the gut feel of a book's table of contents"; today it conveys
  neither scope nor plan (the section list isn't even on it).
- **Orientation lives in the wrong place.** Inside a file diff there is nothing telling you which
  section you're in, how far along you are, or what the section claimed — the narration is a
  separate stop you've already left. The only "Section N of M" is a gray label in the summary bar.
- **The conversation looks like a debug console.** Author name in bold over plain text, a bare
  `JBTextArea` + "Send" button. Worse, it's *inconsistent*: the narration renders HTML, but live
  replies go through `Bubble` (plain `JBTextArea`) — a Docent reply containing `<code>` shows raw
  tags. `CommentCard` already has the mini-markup renderer; the chat doesn't use it.
- **Stock-widget tells everywhere.** File chips are default `JButton`s; "Loading diff…" is a
  gray label flash; the empty pane is top-left prose; list glyphs are text characters (✓ ○ ▸)
  instead of icons; file rows have no file-type icons even though the IDE hands them out free.

None of this is fit-and-finish only. The three product pillars that live purely in the UI —
instinctive scope, agency/pacing, "feel like the IDE" — are all under-delivered by presentation,
not by machinery.

## 2. Design thesis

Make three moments memorable, in this order:

1. **The trailhead is a title page.** Opening a review should feel like opening a well-made book:
   title, one-paragraph thesis, and a route map whose weight you can *feel*.
2. **You always know where you are on the trail.** Progress and position are ambient — visible in
   the rail, the summary, and inside every file diff — never something you navigate away to check.
3. **The Docent is a character.** One accent color, one icon, one voice treatment, everywhere it
   speaks: narration, chat replies, inline comment cards. (And the palette reserves a slot for the
   future critic — a *third* voice that must be instantly distinguishable, per DESIGN §9
   "Author's voice, Docent's layer".)

## 3. The kit (build first — everything else sits on it)

New `ui/DocentUi.kt`, the single source of visual truth:

- **Palette:** `DOCENT` blue (0x3574F0 / 0x4A88FF), `REVIEWER` green (0x4C9A4E / 0x62B266),
  `CRITIC` purple (reserved, e.g. 0x8E5BD0 / 0xA47BE0), `GO` = reviewer green, plus the hover and
  code-chip backgrounds. Delete the four scattered copies.
- **Type scale:** `JBFont.h1/h2/h3` for page/section titles, label for body, `smallFont` for meta.
  Prose panes get a max text width (~72 chars) so the thesis doesn't stretch across a 4K pane.
- **Components:** `card(...)` (rounded border via `IdeBorderFactory.createRoundedBorder`, padding,
  optional accent), `chip(icon, text, onClick)` (pill-style, hover, for file chips), `wrappedLabel`
  / `wrappedProse` (consolidating the three bespoke wrapping mechanisms in `DocentNavPanel` —
  `wrapHtml` width-baking, `WrappingMessageArea`, `cardContentWidth` — the known recurring trap),
  `emptyState(icon, title, body)` centered à la `JBPanelWithEmptyText`.
- **Identity:** a 16px Docent SVG icon (light/dark). Used as the tool-window icon, the avatar next
  to every Docent utterance, and the review tab icon.

This is a refactor with a visible payoff and it's the prerequisite for the critic's third layer.

## 4. The trailhead (biggest single win)

Replace `DocentPanel.showOverview` with a real page:

- **Title block:** subject as `h1`; under it a meta line derived from git — *"7 sections · 23
  files · +412 −168 · base a1b2c3"*. Derived, never authored (consistent with the standing
  triage-is-narration decision; `GitChangeSet` already computes changed ranges).
- **Thesis** as measured prose (max-width), not full-pane text.
- **The route:** one card per section — number, headline, and derived stats (*3 files · +120 −40*),
  visited ✓ state, clickable. This *is* the "book ToC" scope feel, and it finally puts the plan on
  the opening screen instead of only in the rail.
- **One primary action:** "Start the walkthrough" as a default-styled (blue) button — the page's
  single loud element. (Same railroading philosophy the rail's no-trail surface already adopted.)

## 5. Orientation (ambient position, everywhere)

- **A slim trail header** across the top of the review pane, in *both* summary and file views:
  `Section 3 of 7 · <headline>` + a segmented progress strip (one segment per section, filled when
  visited, current accented). In file view add `· file 2 of 4`. Cheap to paint, always on, and it
  fixes "the diff fills the pane and you're lost".
- **File chips, properly:** replace the summary bar's default `JButton`s with kit chips — file-type
  icon (from `FileTypeManager`) + name + tiny `+N −M`, current file filled with the accent. Same
  chips reused in the trail header's file position so summary and diff agree.
- **Keyboard flow:** register next/prev-step and back-to-overview as real actions with shortcuts in
  plugin.xml (they exist only as diff-toolbar buttons today). Agency is a pillar; keyboards are
  agency. Show the shortcut in the button tooltips.

## 6. The conversation (make the Docent's voice real)

- **One rendering path.** Docent output always goes through the markup renderer (`CommentCard`'s
  `appendMarkup`, promoted into the kit) — narration and streamed replies alike. Kills the
  raw-`<code>`-tags bug class and makes every Docent utterance typographically identical.
- **Voice treatment:** Docent messages sit on a subtle tinted card (the existing 0xF4F7FE/0x2B3040)
  with the Docent icon + name; reviewer messages stay plain with the green name. No timestamp noise.
- **Typing indicator in the transcript:** replace the status `JBLabel` above the input with an
  in-flow row (`AnimatedIcon.Default` + "Docent is thinking…") that appears where the reply will
  land. Same component replaces `CommentCard.waitingRow` so waiting looks identical everywhere.
- **Input polish:** one rounded container holding the text area and a send icon-button; grows with
  content up to ~5 rows (no hard 52px `preferredSize`); Enter/Shift+Enter hint moves into the
  placeholder only (it's already there — drop the separate status line's double duty).

## 7. The rail (small strokes, big legibility)

- File rows get **file-type icons** (drop the `▸` text glyph) and a comment-count badge when a file
  has cards; section rows swap ✓/○ text for real icons and dim visited rows slightly.
- Under the subject header, a one-line **progress echo** ("3 of 7 sections read") mirroring the
  trail header's segments.
- **Unread-reply badge** (ASSESSMENT F4): when the Docent answers while the reviewer is in another
  section, the owning section row shows a small blue dot until visited.

## 8. Micro-polish (do opportunistically, in any order)

- "Loading diff…" → centered spinner (`JBLoadingPanel` or `AnimatedIcon.Default`) in the diff host.
- Empty review pane → kit `emptyState` with the Docent icon, centered, instead of top-left prose.
- `Messages.showInfoMessage` fallbacks in the rail → inline notes in the rail itself (the rail
  already committed to "everything inline; no popups" — the error paths didn't get the memo).
- Consistent middle-ellipsis titles and relative times (already good — move into the kit).

## 9. Order and effort

| # | Work | Size |
|---|------|------|
| 1 | Kit (`DocentUi.kt`) + icon + wrapping consolidation | ~½ day |
| 2 | Trailhead page | ~1 day |
| 3 | Trail header + file chips | ~1 day |
| 4 | Conversation restyle (shared renderer, typing row, input) | ~1 day |
| 5 | Rail polish (icons, badges, progress echo) | ~½ day |
| 6 | Micro-polish sweep | ~½ day |

Sequenced so every step ships a visible improvement and nothing later reworks anything earlier.
The critic's third voice (ASSESSMENT week 2–3) lands on top of the kit with a one-line palette use.
