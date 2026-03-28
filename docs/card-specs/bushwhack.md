# Bushwhack — Card Spec

## Identity

- **Name:** Bushwhack
- **grpId:** 93928
- **Set:** FDN (Foundations)
- **Type:** Sorcery
- **Cost:** {G}
- **Ability grpId:** 153364 (category 4 — "Choose one")
- **Forge script:** `forge/forge-gui/res/cardsfolder/b/bushwhack.txt`

## What it does

Modal sorcery — choose one on cast:

1. **Search mode:** Search your library for a basic land card, reveal it, put it into your hand, then shuffle.
2. **Fight mode:** Target creature you control fights target creature you don't control. (Each deals damage equal to its power to the other.)

## Forge script decomposition

```
A:SP$ Charm | Choices$ FetchBasic,Fight
```

Top-level `Charm` ability with two SVars:

**FetchBasic:**
```
DB$ ChangeZone | Origin$ Library | Destination$ Hand | ChangeType$ Land.Basic
  | ChangeTypeDesc$ basic land
```
- `ChangeZone` from Library to Hand, filtered to `Land.Basic`
- Forge internally calls `searchLibrary()` → `chooseSingleCard()` → shuffle

**Fight:**
```
DB$ Pump | AILogic$ Fight | ValidTgts$ Creature.YouCtrl
  | TgtPrompt$ Choose target creature you control
  | SubAbility$ DBFight
```
- First half: target creature you control (no actual pump — `Pump` with `AILogic$ Fight` is Forge's idiom for "select first fight participant")
- `SubAbility$ DBFight`:
```
DB$ Fight | Defined$ ParentTarget | ValidTgts$ Creature.YouDontCtrl
  | TgtPrompt$ Choose target creature you don't control
```
- `ParentTarget` = the creature selected in the Pump step
- Two separate targets required (your creature + opponent's creature)

## Mechanics

| Mechanic | Forge DSL | Wire protocol | Catalog status |
|----------|-----------|---------------|----------------|
| Modal spell (choose one) | `SP$ Charm \| Choices$` | CastingTimeOptionsReq type=Modal, abilityGrpId=153364, options grpIds [27767, 99356] | **wired** (modal-etb, PR #84) |
| Library search to hand | `DB$ ChangeZone \| Origin$ Library \| Destination$ Hand` | SearchReq (type 44) / SearchResp handshake + ZoneTransfer category=Put + RevealedCardCreated + InstanceRevealedToOpponent (type 75) + Shuffle with OldIds/NewIds | **missing** (catalog: library-search) |
| Reveal searched card | implicit in "reveal it" | RevealedCard proxy (type=RevealedCard) in zone 19 + InstanceRevealedToOpponent persistent annotation | **missing** |
| Shuffle | implicit after search | Shuffle annotation with OldIds/NewIds detail arrays | **suppressed** (OldIds/NewIds not populated) |
| Fight (mutual damage) | `DB$ Fight \| Defined$ ParentTarget` | Two-target SelectTargetsReq + DamageDealt annotations (×2, mutual) | **not in catalog** — fight mechanic not cataloged, DamageDealt is wired but fight-specific targeting/resolution unverified |
| Two-target spell | `ValidTgts$ Creature.YouCtrl` + `ValidTgts$ Creature.YouDontCtrl` | SelectTargetsReq with targetIdx 1 and 2, sequential selection | **wired** (targeting infra) |

### Ability/mode grpIds

| grpId | Description |
|-------|-------------|
| 153364 | "Choose one" — the modal ability itself |
| 27767 | Fight mode option |
| 99356 | Search mode option |

## Trace — search mode (session 2026-03-21_22-05-00, seat 2)

Bushwhack drawn gsId 45, cast gsId 49 (search mode selected). Detailed wire spec in `docs/plans/2026-03-21-library-search-wire-spec.md`.

### Cast + modal selection (gsId 49)

| gsId | instanceId | What happened |
|------|-----------|---------------|
| 49 | 288→290 | ObjectIdChanged + ZoneTransfer Hand→Stack category=CastSpell. CastingTimeOptionsReq immediately follows: type=Modal, isRequired=true, options=[{grpId:27767}, {grpId:99356}], abilityGrpId=153364, minSel=1, maxSel=1. Client responds with CastingTimeOptionsResp (no echoed selection). |
| 50 | 290 | Forest 281 tapped, ManaPaid color=5. |

### Resolution — search + reveal + shuffle (gsId 52–54)

| gsId | instanceId | What happened |
|------|-----------|---------------|
| 52 | 290 | ResolutionStart(grpId=93928). Library contents revealed as private objects (51 cards). **SearchReq** (GRE type 44) sent — no payload fields (empty minFind/maxFind/zonesToSearch). Client responds with **SearchResp** — also empty payload. Pure UI handshake. |
| 53 | 228→293 | ObjectIdChanged(228→293, affectorId=290). RevealedCardCreated(affectorId=294, self-ref). ZoneTransfer Library→Hand **category=Put** (not Draw). RevealedCard proxy (iid=294, type=RevealedCard) at zoneId=35. Zone 19 (Revealed) updated with [294]. **InstanceRevealedToOpponent** persistent annotation (type 75) on iid 293. |
| 54 | 290→346 | **Shuffle** annotation: affectorId=290, affectedIds=[2], OldIds=[51 iids], NewIds=[51 fresh iids]. All library cards get new instanceIds. ResolutionComplete(grpId=93928). Bushwhack ObjectIdChanged(290→346) + ZoneTransfer Stack→GY category=Resolve, affectorId=2 (seatId). |

## Trace — fight mode (session 2026-03-21_23-56-57, seat 2)

Bushwhack cast gsId 169 (fight mode selected). **Fight never resolved — Bushwhack was countered due to illegal targets.**

### Cast + modal + targeting (gsId 169–173)

| gsId | instanceId | What happened |
|------|-----------|---------------|
| 169 | 317→318 | Cast: ObjectIdChanged + ZoneTransfer Hand→Stack. CastingTimeOptionsReq (same shape as search — options [27767, 99356]). Fight mode (grpId 27767? or 99356?) selected via CastingTimeOptionsResp. |
| 170 | 318 | PlayerSelectingTargets. **SelectTargetsReq** with two targetIdx entries: targetIdx=1 (creature you control, promptId=152) and targetIdx=2 (creature you don't control, promptId=1112). Both use targetingAbilityGrpId=99356, sourceId=318, abilityGrpId=93928. Player selects targetIdx=1 → iid 291. |
| 171 | 318 | Second SelectTargetsReq: targetIdx=1 shows iid 291 with legalAction=Unselect (already selected). targetIdx=2 available. Player selects targetIdx=2 → iid 295. |
| 172 | 318 | Confirmation: both targets selected (Unselect on both). SubmitTargetsReq submitted. |
| 173 | 318 | PlayerSubmittedTargets. TargetSpec persistent annotations: affectorId=318, target 291 (index=1, abilityGrpId=99356) and target 295 (index=2, abilityGrpId=99356). Mana paid (2× lands for {G}). Two AbilityInstanceCreated for iid 295's triggered abilities (grpId 141939). |

### Resolution — countered (gsId 176–179)

| gsId | instanceId | What happened |
|------|-----------|---------------|
| 176–177 | 320 | ResolutionStart for ability 320 (grpId=141939, opponent's creature trigger). PayCostsReq + mana payment. ResolutionComplete for 141939. AbilityInstanceDeleted. |
| 178–179 | 319 | ResolutionStart for ability 319 (grpId=141939). **Bushwhack (318→324) moved to GY with category=Countered** — both targets became illegal (opponent's creature 295 triggered abilities resolved first, and the stack-triggered effects made at least one target invalid). ZoneTransfer Stack→GY affectorId=319. |

**Fight damage was NOT observed.** The fight mode resolved to "Countered" because the target(s) became illegal by resolution time. No DamageDealt annotations fired for fight.

### Key targeting findings (fight mode)

- **Sequential two-target selection.** SelectTargetsReq fires once per targeting step. Player selects targetIdx=1 first, then targetIdx=2. Each re-send shows the prior selection with legalAction=Unselect.
- **Both targets use targetingAbilityGrpId=99356.** This is the fight mode's grpId, not the search mode (27767). The `abilityGrpId` on the outer SelectTargetsReq is 93928 (the card itself).
- **Two TargetSpec persistent annotations.** One per target (index=1, index=2). Both have affectorId=318 (the spell), abilityGrpId=99356.
- **Countered category.** When a spell's targets all become illegal by resolution, the wire uses ZoneTransfer category="Countered" (Stack→GY). The affectorId on the counter is the last resolving ability instance (319), not the spell itself.

## Gaps for leyline

### Mode 1 — Library search

All gaps documented in `docs/plans/2026-03-21-library-search-wire-spec.md`. Summary:

1. **SearchReq/SearchResp handshake (GRE type 44).** Not implemented. Required to open the client's library search UI. For basic-land-to-hand, the messages are empty payloads — pure handshake.
2. **ZoneTransfer category=Put for search-to-hand.** Current Library→Hand heuristic maps to `Draw` (category 10). Search-to-hand requires `Put` (category 19). Needs a flag to distinguish search from draw.
3. **RevealedCard proxy + zone 19.** Must synthesize a `type: RevealedCard` proxy object alongside the real card. Zone 19 (Revealed) must list the proxy instanceId. `RevealedCardCreated` annotation fires on the proxy.
4. **InstanceRevealedToOpponent (type 75).** Persistent annotation on the searched card marking it visible to opponent. Currently missing from leyline.
5. **Shuffle OldIds/NewIds.** Shuffle annotation exists but without the complete pre/post instanceId arrays. Wire requires all library cards' old and new instanceIds.

### Mode 2 — Fight

6. **Fight mechanic not cataloged.** No `fight` entry in `docs/catalog.yaml`. Fight = "each creature deals damage equal to its power to the other." Engine resolves via `GameEventFight` → `DamageDealt` annotations (×2). The fight-specific wire is **unverified** — no successful fight resolution captured in recordings.
7. **DamageDealt annotation shape for fight.** Combat DamageDealt is wired, but fight damage may differ (non-combat damage source, different affectorId semantics). Needs a recording where fight resolves successfully.
8. **Lethal fight → creature dies.** If a creature takes lethal damage from fight, SBA destroys it. The SBA_Damage ZoneTransfer should fire. The interaction between fight resolution and SBA is unverified for leyline.

### Both modes

9. **Charm sub-ability resolution.** Forge `SP$ Charm` creates sub-ability instances (grpId 141939 observed for the fight mode's sub-abilities). The real server resolves these as separate AbilityInstanceCreated/ResolutionStart/ResolutionComplete cycles. Verify leyline's charm handling produces the correct sub-ability lifecycle annotations.
10. **Counter on all-targets-illegal.** When a modal spell's chosen mode requires targets and all become illegal, the spell is countered (category="Countered"). Verify leyline handles this edge case for targeted modal spells.

## Supporting evidence needed

- [ ] **Fight recording.** Record a game where Bushwhack fight mode resolves successfully (both targets still legal). Needed to capture: DamageDealt annotation shape, lethal-fight → SBA sequence, fight-specific affectorId patterns.
- [ ] Cross-reference with other fight cards in Forge: `Prey Upon`, `Bite Down`, `Ancient Animus` — same `DB$ Fight` mechanism.
- [ ] Catalog update: add `fight` entry under `mechanics` with status `missing` (or `unknown` pending recording evidence).
- [ ] Verify charm sub-ability grpId 141939 is consistent across sessions or card-specific.
