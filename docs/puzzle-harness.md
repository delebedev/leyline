---
summary: "Puzzle harness strengths, limits, and when to reach states through setup actions instead of direct .pzl placement."
read_when:
  - "writing or debugging a puzzle fixture"
  - "a puzzle starts from exile, face-down, prepared, plotted, foretold, or another history-sensitive state"
  - "choosing between direct .pzl state and harness setup actions"
---
# Puzzle Harness

Puzzles are the fast acceptance path for focused gameplay seams. A `.pzl` file should usually describe a small board, deterministic libraries, and one behavior under test.

For puzzles that direct Forge AI can solve and the bridge must reproduce, use the scripted loop in [`ai-solved-acceptance.md`](ai-solved-acceptance.md).

## Good Direct State

Direct `.pzl` placement is a good fit when current game state is enough:

- Cards in hand, battlefield, graveyard, library, command, or sideboard.
- Deterministic top-of-library flows: draw, scry, cascade, discover, search.
- Static checks based on current zones, such as graveyard-count cost reduction.
- Counters and attachments that can be rebuilt from current engine state.
- Mana and board setup for a mechanic that will be exercised after the puzzle starts.

## Snapshot Limits

Puzzle setup is not a record of how the game reached the state. It applies a starting state, then leyline builds a Full GSM from the current engine state.

That means direct `.pzl` placement can be incomplete when the important fact is history-derived:

- A card is in exile because it was plotted, foretold, adventured, prepared, or exiled by a specific source.
- A face-down or special-visibility state must exist in addition to the zone.
- A cast/action rail depends on a Forge flag created by a prior action.
- A client-visible persistent annotation needs a source relationship, designation, or linkage not expressible as plain zone membership.
- A prompt is mid-resolution and depends on source binding from the action that opened it.

In these cases, "card in exile" is underspecified. The fixture needs the richer state: for example, "plotted card in exile" or "card exiled under this source."

## Reaching Rich State

Prefer a setup action path over direct state mutation when the state is history-sensitive:

1. Start with the card in a simple direct state, usually hand or battlefield.
2. Drive the setup action through the normal harness verbs.
3. Wait for a concrete checkpoint, such as zone membership, phase, or available action.
4. Begin the actual assertion from that checkpoint.

Example shape in a session test:

```kotlin
startPuzzleRaw(pzl, validating = true)

castSpellByName("Ratcatcher Trainee")
passUntil(maxPasses = 15) {
    human.exile.cards.any { it.name == "Ratcatcher Trainee" }
}

castFromExile("Ratcatcher Trainee")
```

If this pattern repeats across several fixtures, promote only that repeated setup to a named helper. Do not add a general scenario language before the repetition exists.

## Full GSM Boundary

A Full GSM should be a resync boundary for stable visible state. If a fact can be safely rebuilt from current engine state, prefer fixing the Full GSM projection over requiring every test to replay history.

Do not try to encode transient history in a Full GSM. Zone-transfer animations, object-id-change transitions, damage events, and cast/resolve brackets are event-stream facts. Full state can show the result; it should not pretend to replay the journey.

## Seeded State Today

Puzzle startup already seeds a few stable facts that plain setup does not emit as events:

- Instance and zone baselines for first-diff zone-transfer detection.
- Persistent attachment annotations for cards that start attached.
- Persistent counter annotations for players and permanents.

Add more seeders only for stable facts that can be read from current engine state without guessing history.

## Decision Rule

- Use direct `.pzl` state when the test is about current-state math or a future action.
- Use harness setup actions when the test is about a state normally created by prior gameplay.
- Add a Full GSM projector when current engine state already contains the needed fact.
- Add a seeder only for stable, snapshot-readable state.
- Avoid arbitrary mutation for impossible or under-specified states.
