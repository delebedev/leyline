# Bushwhack Fight Mode — Wire Spec

Recording: `recordings/2026-03-21_23-56-57/md-frames.jsonl`
Turn 8, opponent (seat 2) casts Bushwhack (grpId 93928) choosing fight mode.
Fight target = Sovereign Okinec Ahau (grpId 87402, iid 295, seat 1) — enchanted by Sheltered by Ghosts (grpId 92090, iid 308), which grants Ward {2}.

**Outcome: countered. Bushwhack never resolved.**

No second Bushwhack cast in this session (only 288 total frames; confirmed absent after index 258).

---

## Instance Identity

| Role | instanceId | grpId | Description |
|---|---|---|---|
| Bushwhack in hand | 317 | 93928 | seat 2 hand, drawn turn 8 |
| Bushwhack on stack | 318 | 93928 | ObjectIdChanged 317→318 on cast |
| Bushwhack in GY | 324 | 93928 | ObjectIdChanged 318→324 on Countered |
| Fight target (seat 1 creature) | 295 | 87402 | Sovereign Okinec Ahau, enchanted by Sheltered by Ghosts |
| Fight target (seat 2 creature) | 291 | 93941 | Mild-Mannered Librarian |
| Ward trigger ability #1 | 319 | 141939 | Ward {2}, from iid 295 (source_zone 28) |
| Ward trigger ability #2 | 320 | 141939 | Ward {2}, second instance from iid 295 (source_zone 28) |

---

## Full Sequence

### 1. Cast (gsId 168 → 169)

Client: `PerformActionResp` `{type: Cast, instanceId: 317, grpId: 93928, shouldStop: true}`

Server GSM gsId=169 (`Send`):
- ObjectIdChanged: 317 → 318
- ZoneTransfer: 318, zone_src=35 (Hand), zone_dest=27 (Stack), category=`CastSpell`
- PA EnteredZoneThisTurn(id=4): affectorId=27, affectedIds=[318]
- Stack: [318]

### 2. Modal Selection (same gsId 169)

Immediately after cast GSM, same gsId=169:

```
CastingTimeOptionsReq {
  ctoId: 2
  type: Modal
  affectedId: 318
  affectorId: 318
  grpId: 93928
  isRequired: true
  modal: {
    abilityGrpId: 153364
    minSel: 1, maxSel: 1
    options: [
      { grpId: 27767 }   // mode 1: search library for basic land
      { grpId: 99356 }   // mode 2: fight
    ]
  }
}
```

Client: `CastingTimeOptionsResp` — empty payload (no selectedOptions field).

**Observation:** CastingTimeOptionsReq shape is identical to mode 1 (library search, session 22-05-00). Same ctoId=2, same two options, same empty client resp. The server must infer mode from the subsequent targeting.

### 3. PlayerSelectingTargets + SelectTargetsReq (gsId 170)

Server sends GSM gsId=170 with annotation:
```
{ id: 428, types: [PlayerSelectingTargets], affectorId: 2, affectedIds: [318] }
```

Immediately followed by `SelectTargetsReq` (promptId=10):

```
promptData: {
  sourceId: 318
  abilityGrpId: 93928       // NOTE: spell grpId, not mode ability grpId 99356
  targets: [
    {
      targetIdx: 1
      targetingAbilityGrpId: 99356   // fight mode ability
      targetingPlayer: 2
      minTargets: 1, maxTargets: 1
      prompt: { promptId: 152, parameters: [{parameterName: CardId, numberValue: 318}] }
      targets: [
        { targetInstanceId: 291, legalAction: Select_a1ad, highlight: Tepid }
      ]
    },
    {
      targetIdx: 2
      targetingAbilityGrpId: 99356
      targetingPlayer: 2
      minTargets: 1, maxTargets: 1
      prompt: { promptId: 1112, parameters: [{parameterName: CardId, numberValue: 318}] }
      targets: [
        { targetInstanceId: 295, legalAction: Select_a1ad, highlight: Tepid }
      ]
    }
  ]
}
```

**Two target groups simultaneously:** targetIdx 1 = "creature you control" (291, seat 2's Mild-Mannered Librarian), targetIdx 2 = "creature you don't control" (295, seat 1's Sovereign Okinec Ahau).

Both targets have `highlight: Tepid` and `legalAction: Select_a1ad`. Only one legal option in each group (no battlefield choices). `promptId 152` = "choose creature you control"; `promptId 1112` = "choose creature you don't control" (confirmed from ability text of grpId 99356).

**top-level `abilityGrpId: 93928`** — the spell's grpId, not the mode's. This differs from the per-target `targetingAbilityGrpId: 99356`.

### 4. Target Selection — Two-Round Handshake (gsId 170 → 171 → 172)

**Round 1 (gsId 170):**
Client `SelectTargetsResp`: `{ targetIdx: 1, targetInstanceIds: [291] }`
Server GSM gsId=171: empty diff (no annotations), actions unchanged.
Server re-sends `SelectTargetsReq` gsId=171: targetIdx 1 now shows `legalAction: Unselect, selectedTargets: 1`; targetIdx 2 still `legalAction: Select_a1ad, highlight: Tepid`.

**Round 2 (gsId 171):**
Client `SelectTargetsResp`: `{ targetIdx: 2, targetInstanceIds: [295] }`
Server GSM gsId=172: empty diff.
Server re-sends `SelectTargetsReq` gsId=172: both targets now `legalAction: Unselect, selectedTargets: 1`.

**Round 3 (gsId 172):**
Client sends `SubmitTargetsReq` — no payload. This is the "confirm" message; selection is complete.

**Pattern:** Server echoes back SelectTargetsReq after each partial selection with updated legalAction state, until client sends SubmitTargetsReq to finalize.

### 5. Targets Submitted → Ward Triggers (gsId 173)

GSM gsId=173 (`Send`), decisionPlayer=1 (seat 1 must decide):

**Transient annotations:**
- `PlayerSubmittedTargets` (id=431): affectorId=2, affectedIds=[318]
- `AbilityInstanceCreated` (id=432): affectorId=295, affectedIds=[319], source_zone=28
- `AbilityInstanceCreated` (id=434): affectorId=295, affectedIds=[320], source_zone=28
- `AbilityInstanceCreated` (id=436): affectorId=301, affectedIds=[321], source_zone=28
- `TappedUntappedPermanent` (id=437): affectorId=321, affectedIds=[301], tapped=1
- `UserActionTaken` (id=438): affectorId=2, affectedIds=[321], actionType=4, abilityGrpId=1005
- `ManaPaid` (id=439): affectorId=301, affectedIds=[318], id=507, color=5 (Green — Bushwhack mana cost)
- `AbilityInstanceDeleted` (id=440): affectorId=301, affectedIds=[321]
- `UserActionTaken` (id=441): affectorId=2, affectedIds=[318], actionType=1, abilityGrpId=0

**Persistent annotations:**
- `TargetSpec` (id=429): affectorId=318, affectedIds=[291], abilityGrpId=99356, index=1, promptId=152
- `TargetSpec` (id=430): affectorId=318, affectedIds=[295], abilityGrpId=99356, index=2, promptId=1112
- `TriggeringObject` (id=433): affectorId=319, affectedIds=[318, 295], source_zone=28
- `TriggeringObject` (id=435): affectorId=320, affectedIds=[318, 295], source_zone=28

**Zones:** Pending zone 25 now contains [320, 319] (two ward trigger ability instances).

**Objects:** iid 319 (grpId=141939, Ward {2}, owner=1, zoneId=25 Pending), iid 320 (same).

**Two ward triggers.** Sheltered by Ghosts grants ward to 295, and Sovereign Okinec Ahau may have its own native ward — two separate ward trigger instances 319 and 320 both fire from affectorId=295. Both TriggeringObject PAs reference affectedIds=[318, 295] (the targeting spell + the targeted creature).

**TargetSpec PAs** persist on the stack object 318 with per-target abilityGrpId=99356 and index field matching targetIdx.

### 6. Ward Triggers Move to Stack (gsId 174)

GSM gsId=174: Pending zone cleared, Stack = [320, 319, 318].
decisionPlayer=2 (opponent passes priority after both ward triggers land on stack).

ActionsAvailableReq gsId=174 (promptId=2, systemSeatIds=[2]):
- Seat 2 can still Cast/Activate with remaining mana — ward cost not yet paid.
- No ward-specific pay actions visible here.

### 7. Ward Trigger 320 Resolves — Pay Ward Cost (gsId 176 → 177)

GSM gsId=176: ResolutionStart, affectorId=320, grpId=141939.
`PayCostsReq` (promptId=11, systemSeatIds=[2]) — seat 2 must pay Ward {2}.
Client: `PerformAutoTapActionsResp` (empty).

GSM gsId=177: seat 2 auto-taps two lands (281 + 289, both grpId with abilityGrpId=1131 = Golgari dual), paying 2 mana:
- AbilityInstanceCreated 281→322, TappedUntappedPermanent 322 taps 281, ManaPaid(281→318, color=4 Black)
- AbilityInstanceCreated 289→323, TappedUntappedPermanent 323 taps 289, ManaPaid(289→318, color=4 Black)
- ResolutionComplete affectorId=320, grpId=141939
- AbilityInstanceDeleted 295→320

Stack after: [319, 318].

**Observation:** Ward cost (grpId 141939) payment flows through PayCostsReq/PerformAutoTapActionsResp. The ManaPaid annotations say affectedIds=[318] (the spell being countered if not paid), not [320] (the ward trigger). Ward {2} = 2 generic, paid with any color.

### 8. Second Ward Trigger 319 Resolves — Bushwhack Countered (gsId 179)

GSM gsId=178: priority passes to seat 1 (no action).
GSM gsId=179 (`SendAndRecord`):
- ResolutionStart: affectorId=319, grpId=141939
- ObjectIdChanged (affectorId=319): 318 → 324
- ZoneTransfer (affectorId=319): affectedIds=[324], zone_src=27, zone_dest=37, category=`Countered`
- ResolutionComplete: affectorId=319, grpId=141939
- AbilityInstanceDeleted: affectorId=295, affectedIds=[319]

PA: EnteredZoneThisTurn(id=16): affectorId=37, affectedIds=[324].
diffDeletedPersistentAnnotationIds: [433, 460, 4] — TargetSpec PAs and TriggeringObject PA cleaned up.
Stack: empty. GY seat 2: [324] (Bushwhack).

**Bushwhack countered by second ward trigger.** The first ward trigger (320) resolved and seat 2 paid — but there were two ward triggers. The second (319) resolved without payment and countered the spell.

---

## Key Wire Facts

### CastingTimeOptionsReq (fight mode)
- Identical shape to search mode (ctoId=2, isRequired=true, same options[])
- Client `CastingTimeOptionsResp` is always empty — no selectedOptions
- Server infers chosen mode from which targets are subsequently submitted

### SelectTargetsReq (fight)
- promptId=10 (same as other targeting prompts)
- Two simultaneous `targetIdx` groups (1 and 2), one per fight clause
- top-level `abilityGrpId` = spell grpId (93928), per-target `targetingAbilityGrpId` = mode ability grpId (99356)
- `highlight: Tepid` on legal but unselected targets (not `Hot`/`Warm` — only one option each here)
- `legalAction: Select_a1ad` → selected → `legalAction: Unselect`

### SelectTargetsResp / SubmitTargetsReq protocol
1. Client sends `SelectTargetsResp` with one `targetIdx` at a time
2. Server echoes `SelectTargetsReq` back reflecting new selection state
3. Repeat for each target group
4. Client sends `SubmitTargetsReq` (no payload) to finalize
- This is the same multi-round handshake as single-target spells; each partial selection generates a round-trip

### TargetSpec persistent annotations
- One PA per target, lives on the source spell (affectorId=318)
- Fields: `abilityGrpId` (mode ability), `index` (targetIdx), `promptParameters` (sourceId=318), `promptId`
- Cleaned up in `diffDeletedPersistentAnnotationIds` when spell leaves stack

### Ward trigger wire shape
- Two `AbilityInstanceCreated` fire simultaneously from affectorId=295 (the ward-bearing creature)
- Both go to Pending zone first, then move to Stack as a batch
- `TriggeringObject` PA: affectorId=abilityInstance, affectedIds=[targetedSpell, wardCreature]
- `PayCostsReq` (promptId=11) fires per ward trigger resolution
- Payment via `PerformAutoTapActionsResp` (auto-tap) or presumably `PerformActionResp` for manual
- `ManaPaid` affectedIds=[318] (the spell being countered), not the ward trigger instance
- On non-payment (or second ward trigger if only one was paid): ZoneTransfer category=`Countered`

### Two ward triggers
In this game 295 had two ward abilities (possibly Sheltered by Ghosts + native ward on Sovereign Okinec Ahau). Both triggered. Seat 2 paid for trigger 320 but trigger 319 also resolved and countered. This is correct MTG rules: each ward trigger is independent; paying one doesn't satisfy others.

---

## Gaps / Engine-Bridge Flags

1. **SelectTargetsReq two-group targeting** — fight requires two simultaneous targetIdx groups with sequential client resp rounds + SubmitTargetsReq. Verify engine sends both groups in one req.
2. **SubmitTargetsReq** — distinct message type to finalize multi-target selection. Needs handling.
3. **TargetSpec PAs** — two PAs on source spell (one per target) with index/abilityGrpId/promptId fields. Check whether engine emits these.
4. **Ward triggers: two instances from same creature** — if creature has multiple ward abilities, server fires one AbilityInstanceCreated per ward. Both go through independent PayCostsReq resolution.
5. **PayCostsReq → ManaPaid affectedIds** — affectedIds is the targeted spell (318), not the ward trigger (319/320). Confirm engine has this correct.
6. **CastingTimeOptionsResp carries no selection** — mode is inferred from targeting, not from the resp. If engine reads the resp to know which mode to run, it must look at subsequent target submissions instead.
