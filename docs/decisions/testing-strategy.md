# Decision: Testing Strategy — Pipeline Over Playtesting

**Status:** Agreed  
**Date:** 2026-03-11

## Principle

Playtesting compensates for pipeline blindspots. As the conformance pipeline covers more protocol dimensions, playtesting shrinks from validation gate to spot-check.

## Testing layers

| Layer | What it catches | Cost | When |
|---|---|---|---|
| Unit/conformance tests | Annotation shapes, field values, category mapping | Seconds | Every change |
| Integration tests | Full engine flow, message sequences, state transitions | ~2 min | Every matchdoor change |
| Conformance pipeline | Our output vs recording ground truth, field-by-field | ~5 min | After mechanic additions |
| Playtesting | Client rendering, UX, interaction feel, emergent bugs | 15–30 min | Batched, at interaction pattern boundaries |

## When to playtest

**Playtest when shipping a new interaction pattern the client hasn't seen from our server before:**
- First combat
- First planeswalker activation
- First enchantment aura
- First counterspell
- New UI prompts (GroupReq for a new modal choice type)
- Animation sequencing for a new annotation combination
- Priority flow changes (auto-pass behavior, phase stop handling)

**Skip playtesting for changes that are purely protocol-internal:**
- Adding a missing detail key to an annotation
- Fixing affectorId on an existing annotation type
- Adding a persistent annotation type the client already renders
- Category mapping fixes
- Annotation ordering changes

## Batch boundaries

Don't playtest after every tiny mechanic addition. Batch by interaction pattern:

1. **Implement mechanic** → unit test + conformance pipeline diff against recording. Validates protocol output matches real server. Minutes, every time.
2. **Accumulate** protocol-internal fixes (detail keys, affectorIds, persistent annotations, ordering). No playtest needed — pipeline covers it.
3. **Playtest** when the batch includes a new client-visible interaction pattern. Pick a deck that exercises the new patterns. One session covers the whole batch.

## The pipeline is the lever

The conformance pipeline's coverage directly determines how much playtesting is needed:

| Pipeline dimension | Covered today | When covered, removes need to playtest for |
|---|---|---|
| Annotation shapes (single frame) | Yes | Field values, detail keys, affectorId/affectedIds |
| Message-flow conformance (sequence) | No (lever #1) | Wrong message at wrong time, spurious prompts (#92/#93) |
| Seat 2 / AI turn messages | No (lever #3) | Opponent turn visuals, GamePlayback bugs |
| Priority flow | No (lever #1) | Auto-pass behavior, phase stops, button labels |
| Persistent annotation lifecycle | Partial | Ongoing effects (auras, counters, buffs) rendering |

As each dimension gets pipeline coverage, the corresponding playtest burden drops. Target state: playtesting only validates client rendering and emergent interaction — not protocol correctness.

## Anti-pattern

Implementing a mechanic, writing no conformance test, then spending 30 minutes playtesting to see if it works. The 30 minutes doesn't produce a regression gate — next code change can silently break it. The conformance test does.
