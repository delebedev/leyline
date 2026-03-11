# Decision: Recording Strategy — Breadth for Discovery, Depth for Testing

**Status:** Agreed  
**Date:** 2026-03-11

## Principle

Simple recordings and diverse recordings serve different roles. Simple recordings give depth — isolatable, reproducible conformance tests. Diverse recordings give breadth — discovery fuel for the analysis layer. Both are needed; they're not in tension.

## Two recording types

| | Simple (starter/bot) | Diverse (ranked/draft/complex) |
|---|---|---|
| Purpose | Conformance test source | Discovery + analysis fuel |
| Deck pool | Starter decks, controlled boards | Any — planeswalkers, sagas, MDFCs, etc. |
| Segments | One mechanic per interaction | Multiple mechanics interleaved |
| Reproducibility | High — Sparky is predictable | Low — opponent varies |
| Per-segment value | High (clean, isolatable) | Medium (noisy, but novel) |
| Volume needed | 5–10 targeted sessions | 50–100+ across formats |

## How they feed the workflow

### Diverse recordings → analysis layer → gap discovery

Run corpus-wide tools across all recordings:

- `annotation-variance` — needs n>20 per annotation type to distinguish always-present from sometimes-present keys. More sessions = more reliable schema.
- `annotation-ids analyze` — needs n>10 per type for affectorId role classification. Rare types only appear in diverse games.
- `rec-mechanics` — builds manifest of observed mechanics. New deck archetypes surface mechanics starter decks never touch.
- Rare annotation types — some only trigger on specific cards (e.g., `Designation` only on Monarch/Initiative cards, `MiscContinuousEffect` on specific permanents). You need the card to show up in a game.

**Output:** "Here are 15 annotation types we've never seen." "ManaPaid has a `color` detail key in 98% of recordings but our builder doesn't emit it." "Qualification has two distinct affectorId patterns depending on source type."

### Gap discovery → targeted simple recording → conformance test

Once a gap is identified from the diverse corpus:

1. Record a simple bot match with a deck that exercises the specific mechanic
2. Mine the segment from that recording (clean, one-mechanic interaction)
3. Templatize, generate puzzle, build conformance test
4. Iterate until pipeline passes

Simple recordings are better test sources because:
- Controlled board state → reproducible puzzles
- Single mechanic per segment → clean templates
- Starter deck card pool is stable across sessions → same grpIds
- Sparky's behavior is predictable → deterministic AI-turn sequences

### The scale payoff is in analysis, not in per-segment testing

You don't conformance-test every segment in 100 recordings. You scan 100 recordings so analysis tools have enough data to surface patterns and gaps. Then you test the 10 segments that matter — from the simplest recording that demonstrates each gap.

## Strategy

- **Record broadly.** Play diverse games — different decks, formats, opponents. Quantity matters for the analysis layer. Low effort per session (just play normally with `just serve-proxy`).
- **Conformance-test narrowly.** Pick the simplest recording segment that exercises each mechanic. Starter deck bot matches are ideal. High effort per test (templatize, puzzle, iterate) but high value (permanent regression gate).
- **Use diverse recordings as discovery fuel.** Run analysis tools across the full corpus periodically. Find gaps. Then record a targeted simple game to build the conformance test for that gap.

## Anti-patterns

- Recording 100 complex games and trying to conformance-test every segment. Waste — most segments repeat mechanics already covered.
- Recording only starter deck bot matches. Misses rare mechanics, rare annotation types, interaction patterns that only appear in competitive play.
- Playtesting as discovery. Slow, non-reproducible, doesn't feed the analysis tools. Record the session instead — then you have both the playtest and the data.
