# Retro: Surveil Scoping (#66)

**Date:** 2026-03-08
**Issue:** #66 — surveil card doesn't move to graveyard in client UI
**Time spent debugging:** ~4 hours across 2 sessions
**Time the recording decode would have taken:** ~15 minutes

## What happened

Implemented surveil GroupReq/GroupResp exchange. The modal appeared, the engine handled the choice correctly, but the card never visibly moved to graveyard in the client.

### Debugging timeline

1. Added debug logging to MatchHandler, TargetingHandler, WebPlayerController — no output appeared
2. Discovered stale jar on classpath (`just build` ran `classes` not `jar`) — fixed, still broken
3. Found wrong card shown in surveil modal (Forest instead of Grizzly Bears) — engine had removed the card from library before we read it
4. Fixed card identity via `candidateRefs` + `Game.findById()` — correct card shown now
5. Card still didn't move to graveyard. Checked engine state — Forge moved it correctly
6. Finally decoded proxy recording to see what the real server sends
7. Discovered: `ObjectIdChanged` realloc, dual-object diff (old→Limbo, new→Graveyard), `category:"Surveil"` (not `"Mill"`)
8. Conformance subagent verified: `TransferCategory.Surveil` doesn't exist in our enum, `objectIdChanged` builder missing `affectorId`

### What the recording showed in 15 minutes

The full protocol for surveil-to-graveyard (recording index 247):

```
ObjectIdChanged   orig_id:172 → new_id:329   affectorId:328
ZoneTransfer      affectedIds:[329]  zone_src:Library zone_dest:Graveyard  category:"Surveil"
Objects:          172 → Limbo (Private+viewer), 329 → Graveyard (Public)
```

Every gap we discovered through debugging was visible in this diff.

## Root causes (process)

1. **Scoped from engine behavior, not wire protocol.** We asked "how does Forge do surveil?" instead of "what messages does the real server send for surveil?"

2. **No recording decode step in feature workflow.** The proxy recording existed before we started coding. Nobody decoded it.

3. **Assumed the diff pipeline would "just work"** for the new zone transfer. Didn't check whether `TransferCategory.Surveil` existed or what `inferCategory(Library→Graveyard)` returns.

## What we changed

1. **New skill: `recording-scope`** — decode proxy recordings before implementing any mechanic. Produces a wire spec with exact annotations, categories, instanceId lifecycle. Verified by conformance subagent.

2. **New step in feature workflow** — between "capture recording" and "write puzzle/implement": decode the recording, write wire spec, verify with subagent.

3. **Reflections doc** — `memory/reflections.md` captures the specific technical lessons (stale jars, candidateRefs routing, `Game.findById` for limbo cards, puzzle library ordering, auto-pass state fragility).

## Conformance subagent value

The subagent independently verified our claims and found things we missed:

- **Corrected root cause**: we said `detectZoneTransfers` can't see the card. Subagent showed it CAN (via zone-list tracking) — the real gap is the category label (`Mill` vs `Surveil`).
- **Found missing enum**: `TransferCategory.Surveil` doesn't exist — not mentioned in initial analysis.
- **Found builder gap**: `objectIdChanged` missing `affectorId` param.

This verification step caught an incorrect assumption that would have led to the wrong fix (rebuilding zone detection instead of adding a category variant).

## Applying to future mechanics

Every mechanic that involves zone transfers, targeting, or interactive prompts should go through `recording-scope` first:

- **Scry** — same GroupReq/GroupResp pattern, different category label
- **Discard** — Hand→Graveyard transfer, probably has its own category
- **Exile** — Battlefield→Exile, different zone IDs
- **Return to hand** — Graveyard→Hand or Battlefield→Hand
- **Sacrifice** — Battlefield→Graveyard with specific category
- **Counter (spell)** — Stack→Graveyard

The pattern: if the client needs to animate a card moving between zones, the diff must have the exact `ObjectIdChanged` + `ZoneTransfer` + dual-object pattern with the correct category label. Decode the recording first.
