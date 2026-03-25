# Novice Inspector — Card Spec

## Identity

- **Name:** Novice Inspector
- **grpId:** 88949
- **Set:** MKM
- **Type:** Creature — Human Detective
- **Cost:** {W}
- **P/T:** 1/2
- **Forge script:** `forge/forge-gui/res/cardsfolder/n/novice_inspector.txt`

## What it does

1. **Cast** ({W}): creature resolves to battlefield. No hand interaction.
2. **ETB trigger** (abilityGrpId 86969): "When CARDNAME enters, investigate." Ability instance goes on stack. On resolution: TokenCreated (Clue artifact token, grpId 89236) enters battlefield.
3. **Clue ability** (abilityGrpId 152): "{2}, Sacrifice this artifact: Draw a card." Activated, cost includes two generic mana (paid inline) plus sacrifice of the Clue itself. On resolution: ZoneTransfer(Draw) moves one card from library to hand.

## Mechanics

| Mechanic | Forge DSL | Forge event | Catalog status |
|----------|-----------|-------------|----------------|
| Cast creature | `A:SP$ …` | ZoneTransfer CastSpell/Resolve | wired |
| ETB trigger → Investigate | `T:Mode$ ChangesZone … Execute$ TrigInvestigate` / `SVar:TrigInvestigate:DB$ Investigate` | AbilityInstanceCreated + TokenCreated | wired (see tokens entry) |
| Clue token creation | `DB$ Investigate` → `InvestigateEffect` | TokenCreated affectorId=ETB ability iid | wired; grpId 89236 confirmed |
| Clue sac-for-draw | `A:AB$ Draw \| Cost$ 2 Sac<1/Clue>` | ZoneTransfer Sacrifice + ZoneTransfer Draw | wired (sac-for-draw pattern) |

### Ability grpIds

| grpId | Description |
|-------|-------------|
| 86969 | ETB trigger: "When CARDNAME enters, investigate." |
| 152 | Clue activated: "{2}, Sacrifice this artifact: Draw a card." |
| 89236 | Clue token grpId |

## Trace (session 2026-03-21_21-22-14, seat 1)

Two Novice Inspectors were cast by seat 1 (iids 280 and 313). The first Clue (iid 283) was later sacrificed for a draw.

### First cast and ETB (gsId 8 → 12)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 8 | 165→280 | Hand→Stack (31→27) | Cast: ObjectIdChanged 165→280, ZoneTransfer Hand→Stack category=CastSpell; 1 Plains tapped for {W} |
| 10 | 280 | Stack→BF (27→28) | Resolve: ResolutionStart/Complete grpId=88949; ZoneTransfer category=Resolve; AbilityInstanceCreated affectorId=280 → ability iid 282 (grpId 86969) on stack; TriggeringObject pAnn on 282 |
| 12 | 282, 283 | — | ETB trigger resolves: ResolutionStart grpId=86969; TokenCreated affectorId=282 affectedIds=[283]; Clue token iid 283 grpId=89236 enters BF zone=28 type=Token subtypes=["Clue"]; ResolutionComplete; AbilityInstanceDeleted |

### Clue sacrifice for draw (gsId 144 → 146)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 140–143 | — | — | ActionsAvailable: `Activate instanceId=283 abilityGrpId=152` offered each turn until used |
| 144 | 283, 308 | BF→Exile (28→33) | User activates: AbilityInstanceCreated affectorId=283 → ability iid 308 (grpId 152) on stack; two Plains tapped inline for {2}; ObjectIdChanged 283→311; ZoneTransfer category=Sacrifice zone 28→33; TokenDeleted affectorId=311; UserActionTaken actionType=2 abilityGrpId=152 |
| 146 | 308 | — | Draw resolves: ResolutionStart grpId=152; ObjectIdChanged (library card →312); ZoneTransfer category=Draw zone 32→31; ResolutionComplete; AbilityInstanceDeleted affectorId=283 affectedIds=[308] |

### Second cast (gsId 147 → 151)

Identical pattern: iid 312→313, ability 315 (grpId 86969), Clue iid 316 (grpId 89236). Second Clue was not sacrificed before the session ended.

## Annotations (novel findings)

### Investigate ETB trigger wire (gsId 10)

```
AbilityInstanceCreated  affectorId=280 (the permanent), affectedIds=[282], source_zone=28
TriggeringObject pAnn   affectorId=282 (ability iid), affectedIds=[280], source_zone=28
```

The ETB trigger fires with `TriggeringObject` persistent annotation pointing back to the source permanent. Pattern is shared by other ETB triggered abilities (see `a-most-helpful-weaver-wire.md`).

### Clue token — no LinkInfo

The Clue token (iid 283 grpId 89236) carries **no** LinkInfo persistent annotation at creation. This contrasts with the Map token from Journey On (grpId 87484), which does carry LinkInfo `{LinkType: 2}`. LinkInfo appears to be saga-specific.

A `LinkInfo` pAnn does appear at gsId=144 (`affectorId=308 affectedIds=[283]`), but this is the Clue's own activated-ability instance linking back to the token being sacrificed — not a creation-time annotation.

### Clue sacrifice sequencing (gsId 144)

All cost and movement events fire in a single diff before the ability resolves:

```
AbilityInstanceCreated  affectorId=283, affectedIds=[308] (ability on stack)
ManaPaid ×2             two Plains tapped inline
ObjectIdChanged         283→311
ZoneTransfer            category=Sacrifice, zone 28→33
UserActionTaken         actionType=2 (Activate), abilityGrpId=152
TokenDeleted            affectorId=311, affectedIds=[311]
```

Resolution fires in the *next* diff (gsId 146): Draw ZoneTransfer + AbilityInstanceDeleted. The affectorId on AbilityInstanceDeleted is the **original** Clue iid (283), not the post-ObjectIdChanged iid (311).

### TokenDeleted fires same diff as Sacrifice ZoneTransfer

TokenDeleted does not wait for the ability to resolve. It fires in the same diff as the Sacrifice ZoneTransfer (gsId 144), while the draw ability is still on the stack. This is consistent with `token-creation-wire.md` observations.

## Gaps for leyline

1. **Clue token grpId mapping.** `InvestigateEffect` currently uses an empty fallback (per-player loop). The real grpId is **89236** — `AbilityIdToLinkedTokenGrpId` must map abilityGrpId 86969 → 89236, or the CardDb token lookup needs to resolve Clue tokens by subtype.
2. **TriggeringObject pAnn on ETB triggers.** The `TriggeringObject` persistent annotation must fire when an ETB triggered ability goes on stack (`affectorId=ability instance, affectedIds=[source permanent], source_zone=28`). Not confirmed wired for Investigate specifically — verify in InvestigateEffect.
3. **TokenDeleted affectorId.** TokenDeleted must use the post-ObjectIdChanged iid (311), not the original Clue iid (283). Verify `TokenDeletedEmitter` uses the renamed id.
4. **AbilityInstanceDeleted affectorId after sac.** `AbilityInstanceDeleted` at draw-resolution uses the *pre-ObjectIdChanged* (original) Clue iid as affectorId (283), not 311. Confirm this is what leyline emits.

## Supporting evidence

- `token-creation-wire.md` — TokenCreated shape (no details, affectorId=ability instance)
- `a-most-helpful-weaver-wire.md` — TriggeringObject pAnn pattern on ETB triggers
- `docs/catalog.yaml` — `tokens` entry: InvestigateEffect uses empty fallback, confirmed gap
