# QueuedGameStateMessage: Recording Analysis Findings

**Date:** 2026-03-22
**Source:** 177 QueuedGSMs across 30 proxy recordings (all 1vAI vs Sparky)

## Rule

**QueuedGameStateMessage is only sent to the non-caster seat for targeted spells.**

The caster always receives a regular `GameStateMessage`. The opponent's copy is deferred (queued) until the caster finishes targeting/kicker/modal choices.

## Evidence

| Metric | Value |
|--------|-------|
| Caster receives own CastSpell as QueuedGSM | **0 / 375 (never)** |
| Caster receives own CastSpell as regular GSM | **375 / 375 (always)** |
| Non-caster receives QueuedGSM | 61 instances |
| P1 casts → P2 gets QueuedGSM | 25 |
| P2 casts → P1 gets QueuedGSM | 36 |

## Trigger conditions

**Primary (89%):** Targeted spell. `SelectTargetsReq` appears at the same gsId. Non-caster's copy is held until `SubmitTargetsReq`.

**Secondary (8%):** Kicker/modal interaction (`CastingTimeOptionsReq`). Same deferral pattern — opponent waits for caster's choice.

**Edge (2%):** Flash during opponent's action, alternate-cost paths.

## Delivery sequence (targeted spell)

```
Caster seat:   GameStateMessage[CastSpell, updateType=Send]  ← immediate
               SelectTargetsReq
               ... caster picks targets ...
               SubmitTargetsReq

Non-caster:    QueuedGameStateMessage[CastSpell, updateType=Send]  ← deferred
               QueuedGameStateMessage[empty checkpoint]
               GameStateMessage[mana bracket + cost details]
```

## Implication for leyline

- **1vAI (current):** Never emit QueuedGSM for the human's casts. The human is always the caster for their own spells, and Sparky's casts go through `remoteActionDiff` (different code path).
- **PvP (future):** When the opponent casts a targeted spell, the human player's `GamePlayback` copy should be wrapped in QueuedGSM and deferred until targeting resolves.
- **The split infrastructure is correct** but needs to be gated on `casterSeat != recipientSeat`.

## Recordings analyzed

All sessions in `recordings/` — proxy captures sitting as seat 1 vs Sparky.
High-QueuedGSM sessions: TUTORIAL (20), 2026-03-10_08-23-48 (34), 2026-03-11_16-13-23 (39).
