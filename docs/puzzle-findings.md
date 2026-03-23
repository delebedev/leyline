# Puzzle Findings Log

Quality telemetry from the puzzle writer-judge loop. Each entry records the judge's scoring, issues found, and resolutions applied.

## Recurring Patterns

(Updated periodically — common issues across puzzles)

---

## Calibration — 2026-03-23

Initial rubric calibration against 5 existing puzzles. All scores within expected ranges — no rubric tuning needed.

### legend-rule.pzl — 14/15 PASS

Score: 14/15 (Focus:3 Determinism:3 Signal:3 Minimality:2 Documentation:3)
Single win path through SBA. Every card has a job. Minimality dinged slightly for over-padded libraries.

### bolt-face.pzl — 10/15 NEEDS_REVISION

Score: 10/15 (Focus:2 Determinism:3 Signal:2 Minimality:2 Documentation:1)
AI creatures create alternative bolt targets (Focus). No narrative comments (Documentation). Missing libraries.

### fdn-keyword-combat.pzl — 7/15 REJECT

Score: 7/15 (Focus:1 Determinism:1 Signal:1 Minimality:2 Documentation:2)
Tests 6 mechanics simultaneously. No forcing function. WIN/LOSE undifferentiated across keywords.

### lands-only.pzl — 5/15 REJECT

Score: 5/15 (Focus:1 Determinism:2 Signal:1 Minimality:3 Documentation:1)
Goal:Win with no damage sources — structurally unwinnable. Useful only as infrastructure smoke test.

### prowess-buff.pzl — 7/15 REJECT

Score: 7/15 (Focus:2 Determinism:1 Signal:2 Minimality:3 Documentation:1)
Invalid Goal keyword (description text, not enum). Two spells offer multiple paths. No forcing function.
