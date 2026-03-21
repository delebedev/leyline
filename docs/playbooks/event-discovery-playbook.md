# Event Format Discovery Playbook

How to use Sonnet subagents to discover screen flows for a new MTGA event format.

## When to use

When adding a new event format (Sealed, Draft, Brawl, etc.) to the arena state machine. The subagent explores the format end-to-end, documenting every screen, transition, and coord.

## Two-phase approach

### Phase 1: Discovery run

Launch a `general` subagent with the discovery prompt. The subagent navigates the format step-by-step using raw `arena` commands (no `navigate` — it has known bugs and the point is to discover new screens).

**Template prompt:**

```
You are an arena automation explorer. Your job is to navigate MTGA through
the {FORMAT_NAME} format flow, documenting every screen you encounter.

## Context
- Working dir: (repo root)
- MTGA is running, you're on {STARTING_SCREEN}
- `just arena ocr --fmt` for screen text + coords
- `just arena click <x>,<y>` for coord clicks, `just arena click "text" --retry 3` for text
- `just arena scene` for Player.log scene
- Action buttons: ~866,533 (Play), ~888,504 (in-game), 940,42 (cog), 210,482 (dismiss)

## Your task
1. From {STARTING_SCREEN}, navigate to the {FORMAT_NAME} event
2. Enter the event and go through every screen (pack opening, deck building, etc.)
3. Start a game and concede immediately
4. Document the return path after defeat
5. Navigate back to Home

## Rules
- DO NOT modify any files. Read-only exploration.
- DO NOT use detect_screen() or navigate command.
- After EVERY click, run `just arena ocr --fmt` to see the result.
- Step by step — one command, check, next.

## Output format
Return a structured log:
  Step N: <action>
    OCR: <key text + coords>
    Scene: <arena scene output>
    Notes: <what this screen is>

Then summarize:
- Distinct screens (name, scene value, OCR anchors)
- Transitions (from → to, action, wait condition)
- Errors or unexpected states
- Whether full flow completed
```

### Phase 2: Speed run

After discovery, launch a second subagent to do the format fast — testing the happy path at speed. This catches timing issues and confirms the flow works without careful deliberation.

**Template prompt:**

```
You are an arena automation speed runner. Complete a {FORMAT_NAME} event
run as fast as possible.

## Context
[same as above]

## Current state
{DESCRIBE_CURRENT_STATE — e.g. "Sealed event in progress with 1 loss"}

## Flow
{PASTE_THE_FLOW_FROM_PHASE_1}

## Speed rules
- For deck building: click card grid positions rapidly in a sweep pattern.
  Rows at y=200,300,400; cols at x=100,200,300,400,500. No card reading needed.
- For in-game: concede immediately (940,42 → Concede)
- After each action, brief OCR check. Don't over-analyze.
- Play until event completion or 2 games, whichever first.

## Output
- Games played + timing per game
- Defeat dismiss flow — what coords worked?
- Any screens that differed from discovery run
- Final event state
```

## After both runs

Distill the logs into:

1. **New SCREENS entries** for `tools/arena/screens.py`
   - Screen name, scene value, OCR anchors, reject lists
   - Every screen the subagent documented

2. **New TRANSITIONS entries**
   - From → To, steps (prefer coords over text for action buttons), wait condition
   - Use coords for: action buttons (866,533), dismiss (210,482 or format-specific)
   - Use text for: tab names, menu items, format-specific labels

3. **Defeat dismiss behavior**
   - Bot match: 210,482 × 3 with 2s gaps → Home
   - Event formats: typically "[Click to Continue]" at ~478,551 → EventLobby
   - Note any format-specific differences

4. **Update docs/SESSION.md** with the new screens

## Event formats to discover

| Format | Status | Notes |
|---|---|---|
| Bot Match | Done | RecentlyPlayed → InGame, Result → Home |
| Sealed | Done | SealedBoosterOpen, EventLanding/EventLobby split, EventResult |
| Quick Draft | TODO | DraftPick screen exists but flow untested |
| Premier Draft | TODO | |
| Standard Brawl | TODO | May be simpler (no deck building) |
| Jump In | TODO | Pack selection instead of deck building |

## Key learnings

- **Action buttons = coords, not text.** "Play" appears in multiple places. Use 866,533.
- **Defeat dismiss is format-dependent.** Bot match ≠ event format.
- **EventLanding has 2 states:** pre-deck (Start) vs post-deck (Play/Resign + loss counter). Split into EventLanding + EventLobby.
- **Scene values are reliable** for screens that have unique scenes (DeckBuilder, EventLanding, SealedBoosterOpen). Lobby sub-screens sharing scene "Home" need OCR discrimination.
- **Subagents click one card at a time.** For deck building, give explicit grid coords for a sweep pattern.
