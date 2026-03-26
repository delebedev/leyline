# Archfiend's Vessel — Card Spec

## Identity

- **Name:** Archfiend's Vessel
- **grpId:** 71870 (M21), 92949 (reprint)
- **Set:** M21 / CLU
- **Type:** Creature — Human Cleric
- **Cost:** {B}
- **P/T:** 1/1
- **Forge script:** `forge/forge-gui/res/cardsfolder/a/archfiends_vessel.txt`

## What it does

1. **Cast** ({B}): 1/1 creature with lifelink resolves to battlefield. Any combat damage it deals causes the controller to gain that much life.
2. **Conditional ETB trigger** (abilityGrpId 137887): "When CARDNAME enters, if it entered from your graveyard or you cast it from your graveyard, exile it. If you do, create a 5/5 black Demon creature token with flying." The condition is checked at trigger resolution — if not met, the trigger does nothing. If met: self-exile ZoneTransfer fires, then a 5/5 Demon token enters.
3. **Lifelink** (abilityGrpId 12): static keyword. ModifiedLife annotation fires on seat 1 with affectorId=vessel iid when combat damage resolves.

## Mechanics

| Mechanic | Forge DSL | Forge event | Catalog status |
|----------|-----------|-------------|----------------|
| Cast creature | `A:SP$ …` | ZoneTransfer CastSpell/Resolve | wired |
| Lifelink (static) | `K:Lifelink` | `ModifiedLife` affectorId=creature iid | wired |
| Conditional ETB trigger | `T:Mode$ ChangesZone … CheckSVar: …` | AbilityInstanceCreated + conditional check + ZoneTransfer Exile + TokenCreated | **unobserved** — condition not met in session |
| Self-exile on ETB | `DB$ ChangeZone \| Origin$ Battlefield \| Destination$ Exile` | ZoneTransfer category="Exile" | not confirmed |
| Demon token creation | `DB$ CreateTokens \| TokenScript$ demon_5_5_flying` | TokenCreated affectorId=ability iid | not confirmed; Demon token grpId unknown |

### Ability grpIds

| grpId | Description |
|-------|-------------|
| 12 | Lifelink (static keyword) |
| 137887 | Conditional ETB: "if entered from GY or cast from GY, exile self, create 5/5 Demon with flying" |

## Trace (session 2026-03-22_23-02-04, seat 1)

One copy played. Cast from hand on turn 15, attacked on turn 17, killed in combat by SBA_Damage. Conditional ETB did **not** fire (cast from hand, not GY).

iid lifecycle: 377 (Hand/Limbo) → 378 (Stack→Battlefield) → 400 (Graveyard)

### Cast and ETB (gsId 359 → 361)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 359 | 377→378 | Hand→Stack (31→27) | ObjectIdChanged 377→378; ZoneTransfer category=CastSpell zone_src=31 zone_dest=27; one Swamp tapped (iid 279) for {B}; ManaPaid affectorId=279 affectedIds=378 |
| 361 | 378 | Stack→Battlefield (27→28) | ResolutionStart/Complete grpId=71870; ZoneTransfer category=Resolve zone_src=27 zone_dest=28; hasSummoningSickness=true; EnteredZoneThisTurn pAnn affectorId=28 affectedIds=378 |

No AbilityInstanceCreated for grpId 137887 at gsId 361 — condition (entered from GY) not met, trigger did not fire.

### Combat death (gsId 426 → 428)

Turn 17, combat phase. Vessel (iid 378, 1/1) attacked, was blocked by opponent's Witch's Familiar (iid 360, 2/3).

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 428 | 378→400 | Battlefield→Graveyard (28→33) | DamageDealt affectorId=378 affectedIds=360 damage=1; DamageDealt affectorId=360 affectedIds=378 damage=2; ModifiedLife affectorId=378 affectedIds=1 life=1 (lifelink); ObjectIdChanged 378→400; ZoneTransfer category=SBA_Damage zone_src=28 zone_dest=33 |

## Annotations (confirmed findings)

### Lifelink — ModifiedLife wire (gsId 428)

```
ModifiedLife  affectorId=378  affectedIds=[1]  details={life: 1}
```

affectorId is the attacking creature iid (378), affectedIds is the controller's seat id (1). Fires in the same diff as combat damage and SBA death. Consistent with `docs/catalog.yaml` lifelink entry.

### Cast from hand — no conditional ETB (gsId 361)

ZoneTransfer Resolve fires with no AbilityInstanceCreated for grpId 137887. The conditional ETB trigger does not go on stack when the condition is not met at trigger time. This is consistent with how Forge evaluates "if" ETBs — the trigger fires but immediately checks the condition; if false, it produces no effect and no stack entry is emitted to the client.

## Gaps / unobserved mechanics

1. **Conditional ETB — not observed.** The Vessel was cast from hand in this session; the GY condition was never satisfied. The full wire for grpId 137887 resolution — including the AbilityInstanceCreated for the trigger, the self-exile ZoneTransfer, and the TokenCreated for the Demon — is unknown.
2. **Demon token grpId unknown.** The 5/5 black Demon creature token created by the ETB has no confirmed grpId. Cannot determine from this session. A session where the Vessel is returned from GY (e.g., via Raise Dead) and ETB fires is needed.
3. **GY-cast path unobserved.** The "cast from your graveyard" variant (requires Flashback, Unearth, or similar) was not exercised. Raise Dead was in the opponent's deck in this session but was used on Malakir Cullblade, not Vessel.
4. **Self-exile category.** If the ETB fires, the Vessel exiles itself. Whether that ZoneTransfer uses category="Exile" or a triggered-ability-specific category (e.g., "ETBExile") is not confirmed.

## Supporting evidence

- `docs/catalog.yaml` — `lifelink` entry: wired, LifeChanged annotation confirmed
- prior conformance research (SBA_Damage category) — SBA_Damage category shape; ObjectIdChanged+ZoneTransfer pattern confirmed
- prior conformance research (TokenCreated: no details, affectorId=ability instance) — TokenCreated shape (no details, affectorId=ability instance) expected for Demon token
- prior conformance research (Exile category, DisplayCardUnderCard) — Exile category="Exile" for self-exile ZoneTransfer expected
