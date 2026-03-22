# Ward Counter Wire Spec
Session: `recordings/2026-03-21_23-56-57/`
Cards: Sheltered by Ghosts (grpId 87402, instanceId 295, seat 1) — ward 2; Bushwhack (grpId 93928, instanceId 318→324, seat 2, fight mode)

---

## Object Identity

| instanceId | grpId  | zone      | notes                          |
|------------|--------|-----------|--------------------------------|
| 295        | 87402  | 28 (BF)   | Sheltered by Ghosts (seat 1)   |
| 318→324    | 93928  | 27 (Stack)| Bushwhack cast by seat 2       |
| 319        | 141939 | 25→27     | Ward trigger #1 (the one that counters) |
| 320        | 141939 | 25→27     | Ward trigger #2 (resolved — paid)       |

grpId 141939 = the ward ability on Sheltered by Ghosts. Both ward triggers fire because Bushwhack targets both 291 AND 295 (fight mode = two targets).

---

## Full Message Sequence (gsId chain)

### Phase 1: Bushwhack Cast + Target Selection (gsId 169–172)

**gsId=169** — `GameStateMessage` Diff/Send
Bushwhack (318) moves from Hand (35) → Stack (27). Annotations:
- `ObjectIdChanged` id=426: affectedIds=[317] orig_id=317, new_id=318
- `ZoneTransfer` id=427: affectedIds=[318] zone_src=35, zone_dest=27, category=**CastSpell**
- pAnn created: `EnteredZoneThisTurn` id=4 affectorId=27 affectedIds=[318]

Immediately followed by `CastingTimeOptionsReq` (msgId=227, promptId=23):
- ctoId=2, type=Modal, affectedId=318 — Bushwhack mode selection (fight vs direct damage)
- Options: grpId 27767, grpId 99356

**gsId=170** — `GameStateMessage` Diff/Send
Annotation: `PlayerSelectingTargets` id=428 affectorId=2 (seat 2) affectedIds=[318]

Immediately followed by `SelectTargetsReq` (msgId=229, promptId=10):
- Two target slots (Bushwhack fight mode needs two targets)
- targetIdx=1: targetInstanceId=291, targetingAbilityGrpId=99356, targetingPlayer=2
- targetIdx=2: targetInstanceId=295 (Sheltered by Ghosts), targetingAbilityGrpId=99356, targetingPlayer=2

Client response (SelectTargetsResp): selects 291 for targetIdx=1

**gsId=171** — `SelectTargetsReq` (msgId=231) — iterative confirm, 291 now shows `Unselect`, 295 still available
Client selects 295 for targetIdx=2.

**gsId=172** — `SelectTargetsReq` (msgId=233) — both 291 and 295 show `Unselect` (all selected, confirm)
No client response shown (implicit submit).

---

### Phase 2: Ward Triggers Fire (gsId=173)

**gsId=173** — `GameStateMessage` Diff/Send, updateType=**Send**, prevGsId=172
`decisionPlayer=1` (seat 1 — the defending player gets the decision window)

Key annotations (transient):
- `PlayerSubmittedTargets` id=431: affectorId=2 affectedIds=[318]
- `AbilityInstanceCreated` id=432: affectorId=295 affectedIds=[**319**], details.source_zone=28
- `AbilityInstanceCreated` id=434: affectorId=295 affectedIds=[**320**], details.source_zone=28
- (mana ability created/deleted 436/440 for Forest tap — Bushwhack cost payment)
- `UserActionTaken` id=441: affectorId=2 affectedIds=[318], actionType=1 (CastCard), abilityGrpId=0

Key persistent annotations (added this GSM):
- `TargetSpec` id=429: affectorId=318 affectedIds=[291], abilityGrpId=99356, index=1, promptId=152
- `TargetSpec` id=430: affectorId=318 affectedIds=[**295**], abilityGrpId=99356, index=2, promptId=1112
- `TriggeringObject` id=433: affectorId=**319** affectedIds=[318, 295], source_zone=28
- `TriggeringObject` id=435: affectorId=**320** affectedIds=[318, 295], source_zone=28

Zone state: Pending (25) now contains [320, 319] — both ward triggers waiting.
Objects 319 and 320: type=Ability, grpId=141939, owner=1, controller=1 (seat 1 owns the triggers).

`TimerStateMessage` follows (both seats).

---

### Phase 3: Ward Triggers Onto Stack (gsId=174)

**gsId=174** — `GameStateMessage` Diff/SendAndRecord, prevGsId=173
Stack (27) now contains [320, 319, 318] — triggers above Bushwhack.
Pending zone (25) cleared.
`turnInfo.priorityPlayer=2, decisionPlayer=2` — opponent has priority now.

Followed by `ActionsAvailableReq` (promptId=2) to seat 2:
- Cast spells, ActivateMana, Pass, FloatMana — no pay-ward option offered to seat 2 here

Client (seat 2) sends `PerformActionResp` → Pass.

---

### Phase 4: Ward Trigger 320 Resolves — Opponent PAYS (gsId=175–177)

**gsId=175** — Diff/SendHiFi, priorityPlayer=1 (seat 1 gets priority)

**gsId=176** — Diff/Send, prevGsId=175
Annotation: `ResolutionStart` id=443: affectorId=**320** affectedIds=[320], grpid=141939
`decisionPlayer=2`

Followed by `PayCostsReq` (msgId=241, promptId=11) → systemSeatIds=[2]
Client (seat 2) responds `PerformAutoTapActionsResp` (auto-taps two dual lands).

**gsId=177** — Diff/SendHiFi, prevGsId=176
Two lands (281, 289 = dual lands, grpId 94124 and 93978) tapped for {U}{U} (ward cost {2}).
Annotations:
- `AbilityInstanceCreated`/`TappedUntappedPermanent`/`UserActionTaken`/`ManaPaid` × 2 (281→322, 289→323)
- ManaPaid id=448: affectorId=281 affectedIds=[**318**], color=4 (Blue)
- ManaPaid id=454: affectorId=289 affectedIds=[**318**], color=4 (Blue)
- `ResolutionComplete` id=457: affectorId=**320** affectedIds=[320], grpid=141939
- `AbilityInstanceDeleted` id=458: affectorId=295 affectedIds=[320]

Deleted pAnns: [435, 446, 452, 456] — TriggeringObject for 320 gone; 446/452/456 are ManaPaid-related pAnns (never appeared in accumulator in decoded frames — likely server-internal bookkeeping)

Stack after: [319, 318] — trigger 320 consumed.

---

### Phase 5: Ward Trigger 319 Resolves — Opponent DECLINES / Does Not Pay (gsId=178–179)

**gsId=178** — Diff/SendHiFi, prevGsId=177, priorityPlayer=1 → 1 has priority again

**gsId=179** — Diff/SendAndRecord, prevGsId=178, updateType=**SendAndRecord**
This is the COUNTER GSM.

Annotations (transient):
- `ResolutionStart` id=459: affectorId=**319** affectedIds=[319], grpid=141939
- `ObjectIdChanged` id=461: affectorId=**319** affectedIds=[318], orig_id=318, new_id=**324**
- `ZoneTransfer` id=462: affectorId=**319** affectedIds=[**324**], zone_src=27, zone_dest=37, category=**Countered**
- `ResolutionComplete` id=463: affectorId=**319** affectedIds=[319], grpid=141939
- `AbilityInstanceDeleted` id=464: affectorId=295 affectedIds=[319]

Persistent annotations added: `EnteredZoneThisTurn` id=16 affectorId=37 affectedIds=[324]
Deleted pAnns: [433, 460, 4]
- 433 = TriggeringObject for 319 — makes sense, trigger resolved
- 4 = EnteredZoneThisTurn for 318 on Stack — Bushwhack gone from stack now
- **460 = ghost deletion** — never appears in any GSM persistentAnnotations. Server sends a delete for a pAnn ID that was never established in captured frames. Likely a server-internal tracking artifact that gets cleaned up even though the client never had it.

Stack cleared to []. Limbo gets 318 (old Bushwhack object). GY (37) gets 324 (new id for countered Bushwhack).

---

## Key Findings

### 1. Ward counter trigger: NO SelectNReq
No `SelectNReq` for ward cost payment. The mechanism is **`PayCostsReq`** (promptId=11) sent to the OPPONENT (seat 2, systemSeatIds=[2]), and the opponent responds with `PerformAutoTapActionsResp`. This is the same PayCostsReq path used for any cost payment — the server decides whether the cost was paid and acts accordingly.

There is no explicit "do you want to pay?" prompt separate from the PayCostsReq. The client auto-taps when it can afford it; when it cannot or declines, the server counters the spell.

### 2. Two ward triggers, two independent PayCostsReq cycles
Bushwhack targets both 291 and 295 in fight mode. Two separate ward triggers (319, 320) fire, each with grpId=141939. They resolve independently:
- Trigger 320 resolves first (top of stack) → PayCostsReq → paid → ResolutionComplete → trigger consumed
- Trigger 319 resolves second → ResolutionStart → **no PayCostsReq observed** → immediate Countered ZoneTransfer

The absence of PayCostsReq for trigger 319 resolution means the server either:
(a) checked and found insufficient mana/declined, and processed the counter without asking again, or
(b) the opponent's client had already passed priority on the second trigger

Both triggers are `owner=1, controller=1` (seat 1's ward ability) but payment is requested from seat 2 (opponent). The ManaPaid annotations correctly reference 318 as affectedIds (the spell being warded, not the trigger).

### 3. TriggeringObject pAnn structure
Each ward trigger establishes a `TriggeringObject` pAnn:
- `TriggeringObject` id=433: affectorId=319, affectedIds=[318, 295], source_zone=28
- `TriggeringObject` id=435: affectorId=320, affectedIds=[318, 295], source_zone=28

Both triggers share the same affectedIds: [318=Bushwhack, 295=Sheltered by Ghosts]. The source_zone=28 (battlefield) is the ward source zone.

### 4. Countered annotation anatomy
```
ResolutionStart  affectorId=319  affectedIds=[319]    grpid=141939
ObjectIdChanged  affectorId=319  affectedIds=[318]    orig_id=318 new_id=324
ZoneTransfer     affectorId=319  affectedIds=[324]    zone_src=27 zone_dest=37  category="Countered"
ResolutionComplete affectorId=319 affectedIds=[319]   grpid=141939
AbilityInstanceDeleted affectorId=295 affectedIds=[319]
```
The ObjectIdChanged fires for the SPELL being countered (318→324), not the trigger (319). affectorId on all these annotations = 319 (the ward trigger ability instance). The spell gets a new instanceId on GY entry (standard pattern).

### 5. First live "Countered" category confirmation
This is the first ward-triggered counter observed. The category string "Countered" (zone_src=27, zone_dest=37) matches what `TransferCategory.Countered` already emits. The affectorId=wardTriggerInstanceId pattern is new data — our code should set affectorId on the ZoneTransfer and ObjectIdChanged to the triggering ability's instance ID, not 0.

---

## Code Gap Analysis

### What works
- `TransferCategory.Countered("Countered")` — string matches wire
- `SpellCountered` GameEvent → `Countered` category mapping in `AnnotationBuilder`
- `ResolutionStart` / `ResolutionComplete` — builders exist (lines 202-208, 324)
- `TriggeringObject` — builder exists (lines 660-667) but missing `affectorId` field

### Gaps — flag for engine-bridge agent

**Gap 1: TriggeringObject missing affectorId**
Wire: `TriggeringObject` has `affectorId=<triggerInstanceId>`. Our `triggeringObject()` builder (AnnotationBuilder.kt:662) calls `.addAffectedIds(instanceId)` but does NOT set `.setAffectorId(...)`. The affectorId should be the trigger's own instanceId (same as affectedIds[0]).

**Gap 2: ZoneTransfer/ObjectIdChanged for ward counter missing affectorId**
Wire: `ObjectIdChanged` id=461 has `affectorId=319` (the ward trigger). Wire: `ZoneTransfer` id=462 has `affectorId=319`. Our `objectIdChanged()` builder (line 231) takes optional `affectorId=0` default. When countered by a ward trigger, the triggering ability's instanceId must be passed as affectorId.

**Gap 3: Ward trigger double-fire + independent PayCostsReq routing**
When Bushwhack targets two permanents with ward on the same creature (fight mode), two independent triggers fire and each needs its own PayCostsReq/resolution cycle. Our engine presumably handles this through Forge's trigger stack, but the client-facing PayCostsReq must be routed to seat 2 (the spell's controller), not seat 1. Confirm `BundleBuilder.payCostsBundle()` uses the correct seatId.

**Gap 4: Ghost pAnn 460 deletion**
gsId=179 deletes pAnn 460 which never appears in any GSM. This is fine to ignore — our implementation should not crash if a pAnn deletion references an unknown ID. Likely a server-internal bookkeeping artifact. No action required.

**Gap 5: PayCostsReq for second ward trigger absent**
Only trigger 320 got a `PayCostsReq`. Trigger 319 (the one that countered) went directly from ResolutionStart to ZoneTransfer(Countered) with no `PayCostsReq` in between. This may mean the second trigger resolves without asking for payment when it counters, or the recording skipped that exchange (unlikely — all frames are captured). Worth confirming in a longer recording where the opponent has mana.

---

## Ward Catalog Status

Current: `status: partial, notes: "Trigger fires, counter-tax applied by engine. Visual feedback may be incomplete."`

The wire data confirms:
- Two triggers fire correctly per targeted creature with ward
- `PayCostsReq` routes to opponent (correct seat)
- `category="Countered"` string correct
- `TriggeringObject` pAnns fire with correct structure (minus affectorId bug)
- `TargetSpec` pAnns persist across the resolution sequence

Recommended catalog update: keep `partial`, update notes to reflect the affectorId gap on TriggeringObject/ZoneTransfer annotations. The functional counter path works but the animation affector chain is incomplete.
