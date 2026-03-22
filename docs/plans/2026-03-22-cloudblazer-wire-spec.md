# Cloudblazer / Chart a Course Wire Spec
Recording: `recordings/2026-03-22_00-07-34`
Human: seat 1

---

## Cloudblazer (grpId 94081)

**Result: never cast in this recording.**

Two copies were in hand:
- iid 164 — in opening hand, survived to turn 13 end
- iid 356 — drawn turn 13 (Draw step, ObjectIdChanged orig_id=174→new_id=356, ZoneTransfer Library→Hand category="Draw")

Both remained available to cast through turn 13 Main2 (frame index 422, gsId=340). The game ended during opponent's turn 14 combat with seat 1 at `PendingLoss`. Neither copy ever appeared on the stack or battlefield.

No ETB trigger data is available from this recording. Cloudblazer ETB annotation sequence (AbilityInstanceCreated, ModifiedLife, ZoneTransfer×2 for draws) cannot be confirmed from this session.

---

## Chart a Course (grpId 94014, iid 293→295→300)

Cast and resolved turn 5 Main2. Seat 1 had attacked that turn (iid 285 declared attacking), so the discard condition was NOT met — no discard occurred.

### Cast (gsId=99, frame index=127)

```
ObjectIdChanged: orig_id=293 → new_id=295
ZoneTransfer:    affectedIds=[295]  zone_src=31(Hand) → zone_dest=27(Stack)  category="CastSpell"
[mana payment sequence omitted — W + U via iids 284, 279]
UserActionTaken: affectorId=1 affectedIds=[295] actionType=1
```

No `affectorId` on the ZoneTransfer CastSpell annotation (consistent with other cast observations).

### Resolution (gsId=101, frame index=129)

Single GSM diff containing the full resolution sequence:

```
ResolutionStart:   affectorId=295  affectedIds=[295]  details={grpid:94014}

ObjectIdChanged:   affectorId=295  affectedIds=[169]  orig_id=169 → new_id=298
ZoneTransfer:      affectorId=295  affectedIds=[298]  zone_src=32(Library)→zone_dest=31(Hand)  category="Draw"

ObjectIdChanged:   affectorId=295  affectedIds=[170]  orig_id=170 → new_id=299
ZoneTransfer:      affectorId=295  affectedIds=[299]  zone_src=32(Library)→zone_dest=31(Hand)  category="Draw"

ResolutionComplete: affectorId=295 affectedIds=[295]  details={grpid:94014}

ObjectIdChanged:   affectorId=1    affectedIds=[295]  orig_id=295 → new_id=300
ZoneTransfer:      affectorId=1    affectedIds=[300]  zone_src=27(Stack)→zone_dest=33(GY)  category="Resolve"
```

### Key observations

1. **Two draws are individual, sequential** — separate ObjectIdChanged + ZoneTransfer pairs per card, not batched. Same pattern as other multi-draw spells.

2. **affectorId=spellIid (295) on draw annotations** — both ObjectIdChanged and ZoneTransfer for each drawn card carry affectorId=295 (the resolving spell instance). This is consistent across both draws.

3. **No discard annotation** — seat 1 attacked this turn, so "if you didn't attack" condition was false. The entire resolution is just two draws with no SelectNReq/discard branch visible.

4. **Graveyard transition** — after ResolutionComplete, the spell (now iid 300) moves Stack→GY with category="Resolve" and affectorId=1 (seatId). Matches sorcery GY pattern.

5. **All annotations in one diff** — ResolutionStart, both draws, ResolutionComplete, and Resolve-to-GY all appear in the same GSM diff (gsId=101). No intermediate priority windows.

### What's missing: discard branch

The "attacked=false" discard path of Chart a Course was not exercised. To capture the discard annotation sequence, need a recording where seat 1 casts Chart a Course on a turn without attacking. Expected: SelectNReq for "choose a card to discard" or a Discard ZoneTransfer annotation after ResolutionComplete.

---

## Annotation ordering reference (draw 2 spells)

From this session: `ResolutionStart → [ObjectIdChanged + ZoneTransfer] × N → ResolutionComplete → [ObjectIdChanged + ZoneTransfer to GY]`

Each card draw is one ObjectIdChanged + one ZoneTransfer, paired. Order within the pair: ObjectIdChanged always precedes its ZoneTransfer. This matches observations from previous recordings (Inspiration from Beyond, etc.).
