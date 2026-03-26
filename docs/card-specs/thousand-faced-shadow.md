# Thousand-Faced Shadow — Card Spec

## Identity

- **Name:** Thousand-Faced Shadow
- **grpId:** 79511 (reprints: 79825, 87115, 87116)
- **Set:** WOE / multiple
- **Type:** Creature — Human Ninja
- **Cost:** {U} (base mana cost)
- **P/T:** 1/1
- **Keywords:** Flying, Ninjutsu {2}{U}{U}
- **Forge script:** `forge/forge-gui/res/cardsfolder/t/thousand_faced_shadow.txt`

## Mechanics

| Mechanic | Forge DSL | Forge event | Catalog status |
|----------|-----------|-------------|----------------|
| Cast {U} | `ManaCost:U` | ZoneTransfer CastSpell + ResolutionStart/Complete | wired |
| Flying | `K:Flying` | static, no wire events | wired |
| Ninjutsu {2}{U}{U} — activation (hand→BF attacking, return attacker) | `K:Ninjutsu:2 U U` | inactiveActions in ActionsAvailableReq (offered but unactivated); full activation wire unobserved | **unknown — never activated in session** |
| ETB trigger — create copy token of another attacking creature | `T:Mode$ ChangesZone … Execute$ TrigCopy` | SelectTargetsReq + TokenCreated | **unknown — ETB only fires via ninjutsu path; neither event observed** |

### Ability grpIds

| grpId | Description |
|-------|-------------|
| 79511 | Base card (cast + flying) |
| (ninjutsu abilityGrpId) | Not observed — inactiveActions have no `abilityGrpId` field visible in proto trace |
| (ETB copy trigger grpId) | Not observed |

## What it does

1. **Cast {U}**: enters the stack; resolves to battlefield with ResolutionStart/Complete + ZoneTransfer category=Resolve.
2. **Flying**: static keyword, no wire events.
3. **Ninjutsu {2}{U}{U}** (alternate play mode): during the declare-attackers window, the player may return an attacking creature they control to their hand and put the Shadow into play attacking from hand. This bypasses the normal cast — the Shadow enters the battlefield without ever being put on the stack as a spell.
4. **ETB copy trigger** (ninjutsu only): when the Shadow enters from hand while attacking, a SelectTargetsReq prompts the player to choose another attacking creature; a token copy of that creature is created tapped and attacking. The token's grpId matches the source creature's grpId (not a distinct token grpId), as observed for Three Steps Ahead's copy of the Shadow (token iid 412 had grpId=79511).

## Trace (session 2026-03-22_22-40-25, seat 1)

Both Shadow instances (iid 167 and 169) were **cast normally** for {U} on T4 and T8 respectively, not via ninjutsu. Ninjutsu was offered in `inactiveActions` throughout the game but never activated.

### Normal cast (gs=76, iid 167→304, T4 Main1)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 75 | 287→303 | Hand→BF | PlayLand (Island), setting up blue mana |
| 76 | 167→304 | Hand→Stack | ObjectIdChanged 167→304; ZoneTransfer zone_src=31 (Hand) zone_dest=27 (Stack) category=CastSpell; Island (288) tapped, ManaPaid color=2 (blue); UserActionTaken actionType=1 abilityGrpId=0 |
| 78 | 304 | Stack→BF | ResolutionStart grpid=79511; ResolutionComplete grpid=79511; ZoneTransfer zone_src=27 zone_dest=28 category=Resolve |

### Ninjutsu offer — inactiveActions (gs=139, T6 Combat/DeclareAttack)

ActionsAvailableReq at gs=139 (T6 Combat, after Shadow 304 was declared attacking):

```
gre.actionsAvailableReq.inactiveActions[0].instanceId = 169
gre.actionsAvailableReq.inactiveActions[0].facetId = 169
gre.actionsAvailableReq.inactiveActions[2].instanceId = 169
gre.actionsAvailableReq.inactiveActions[2].facetId = 169
```

Ninjutsu for Shadow (169) appears in `inactiveActions` (not `actions[]`). The action type and abilityGrpId fields are not visible in proto-trace for `inactiveActions` — the decoded JSONL omits this field entirely.

### Copy token via Three Steps Ahead (gs=383, T14 Main1)

Three Steps Ahead (iid 405, grpId=90421) resolved and created a Shadow token:

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 383 | 405 | Stack | ResolutionStart affectorId=405 grpid=90421 |
| 383 | 412 | →BF (28) | TokenCreated affectorId=405 affectedIds=[412]; no details (consistent with token-creation-wire.md) |
| 383 | 405→413 | Stack→GY | ObjectIdChanged 405→413; ZoneTransfer zone_src=27 zone_dest=33 category=Resolve |

Token iid 412: grpId=**79511** (matches source creature), type=Token, subtypes=Human/Ninja, 1/1, uniqueAbilityCount=3. **Copy tokens use the copied card's grpId, not a distinct token grpId.**

Note: this copy was created by Three Steps Ahead (copy a spell), not by the Shadow's own ETB trigger. The Shadow's ETB trigger via ninjutsu was **not observed** in this session.

## Annotations

### Normal cast (gs=76)

- `ObjectIdChanged` — affectorId absent, affectedIds=[167], details={orig_id:167, new_id:304}
- `ZoneTransfer` — affectorId absent, affectedIds=[304], details={zone_src:31, zone_dest:27, category:"CastSpell"}
- `AbilityInstanceCreated` — affectorId=288 (Island), affectedIds=[305], details={source_zone:28}
- `TappedUntappedPermanent` — affectorId=305, affectedIds=[288], details={tapped:1}
- `UserActionTaken` — affectorId=1, affectedIds=[305], details={actionType:4, abilityGrpId:1002}
- `ManaPaid` — affectorId=288, affectedIds=[304], details={id:57, color:2}
- `AbilityInstanceDeleted` — affectorId=288, affectedIds=[305]
- `UserActionTaken` — affectorId=1, affectedIds=[304], details={actionType:1, abilityGrpId:0}

### Resolution (gs=78)

- `ResolutionStart` — affectorId=304, affectedIds=[304], details={grpid:79511}
- `ResolutionComplete` — affectorId=304, affectedIds=[304], details={grpid:79511}
- `ZoneTransfer` — affectorId=1 (seat), affectedIds=[304], details={zone_src:27, zone_dest:28, category:"Resolve"}

No ETB trigger queued (enters not attacking — normal cast from hand does not meet "enters from your hand attacking" condition).

## Gaps for leyline

1. **Ninjutsu activation wire unobserved.** The full activation sequence (declare ninjutsu, return attacker to hand, Shadow enters BF attacking) was never triggered. Unknown: (a) action type in `inactiveActions` vs `actions[]` at the right combat window; (b) whether activation uses `CastSpell` category (as all Shadow hand→stack transfers did) or a distinct ninjutsu category; (c) abilityGrpId for the ninjutsu ability; (d) whether a PayCostsReq fires for {2}{U}{U}.
2. **inactiveActions not decoded.** The JSONL decoder drops the `inactiveActions` field from `ActionsAvailableReq`. Action type and `abilityGrpId` for ninjutsu in the inactive state are unknown. Flag for tooling: decode `inactiveActions` the same way as `actions`.
3. **ETB copy trigger unobserved.** SelectTargetsReq shape for "choose another attacking creature" and TokenCreated affectorId (should be the ETB trigger ability instance) are unknown.
4. **Copy token grpId confirmed.** Token grpId = source creature's grpId (79511). Observed via Three Steps Ahead, not via ninjutsu ETB. Assumed to hold for the ETB trigger path as well — needs verification.
5. **Ninjutsu catalog entry absent.** Ninjutsu mechanic not in `docs/catalog.yaml`.

## Supporting evidence

- `token-creation-wire.md` — TokenCreated affectorId=ability instance, no details field. Applies to Three Steps Ahead case above; expected same shape for ETB trigger.
- `mandatory-additional-cost-wire.md` — PayCostsReq/EffectCostResp sequence. Ninjutsu may use the same pattern for {2}{U}{U} cost, unconfirmed.
- `promptid-button-labels.md` — confirms ActionsAvailableReq promptId=2 throughout this session.
- `targeting-wire.md` / `targetspec-wire.md` — SelectTargetsReq shape for single-target prompts. Expected shape for ETB copy trigger.

**Single data point caveat:** ninjutsu activation and the ETB copy trigger were not observed in this session. All normal-cast wire data is confirmed (two identical casts observed). The copy token grpId finding (=source grpId) requires verification via the ninjutsu ETB path.
