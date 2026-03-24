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

## 2026-03-23 — deathtouch-kill.pzl

Score: 14/15 (Focus:3 Determinism:3 Signal:3 Minimality:2 Documentation:3)
Verdict: PASS (round 1)

No issues requiring revision. Minor note: libraries over-padded (5 vs minimum 1), kept for consistency with design guide recommendation.

Final card count: 2 non-land (Typhoid Rats, Juggernaut)
Mechanic tested: deathtouch — 1 damage from deathtouch creature kills any toughness

## 2026-03-24 — dfc-transform-activate.pzl

Score: 14/15 (Focus:3 Determinism:3 Signal:3 Minimality:3 Documentation:2)
Verdict: PASS (round 2)
Tokens: judge=23k+23k, rounds=2, total_agent=46k

Round 1 issues:
- Documentation: Protocol path didn't trace Revealing Eye's transform trigger (RevealHand/ChooseCard/Discard prompts)

Round 2 issues:
- Documentation: TIMEOUT sub-modes not reflected back in failure modes section (minor)

Resolution: Added full transform trigger protocol trace. Split TIMEOUT into TIMEOUT-A (activation) and TIMEOUT-B (trigger).

Final card count: 5 total, 1 non-land (Concealing Curtains)
Mechanic tested: activated-ability DFC transform — in-place grpId mutation (#191)
