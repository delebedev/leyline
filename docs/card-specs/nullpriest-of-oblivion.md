# Nullpriest of Oblivion — Card Spec

## Identity

- **Name:** Nullpriest of Oblivion
- **grpId:** 73316
- **Set:** ZNR
- **Type:** Creature — Vampire Cleric
- **Cost:** {1}{B}
- **Kicker:** {3}{B}
- **Base P/T:** 2/1
- **Forge script:** `forge/forge-gui/res/cardsfolder/n/nullpriest_of_oblivion.txt`

## What it does

1. **Menace** — can only be blocked by two or more creatures.
2. **Lifelink** — combat damage this creature deals is also gained as life.
3. **Kicker {3}{B}** — optional additional cost paid at cast time. The caster is prompted via CastingTimeOptionsReq (type=Kicker, grpId=10662). If chosen, the spell costs an extra {3}{B} and the kicked flag is set.
4. **ETB trigger (kicked only)** — when Nullpriest enters the battlefield, if it was kicked, its triggered ability (grpId 138454) fires. The controller chooses a target creature card in their graveyard; that card returns to the battlefield. Targeting uses SelectTargetsReq against graveyard objects, not SelectNReq.

## Mechanics

| Mechanic | Forge DSL | Forge event | Catalog status |
|----------|-----------|-------------|----------------|
| Menace | `K:Menace` | Qualification pAnn (QualificationType=40, grpid=142) on ETB | wired |
| Lifelink | `K:Lifelink` | ModifiedLife on combat damage | wired |
| Kicker prompt | `K:Kicker:3 B` | CastingTimeOptionsReq (type=Kicker, ctoId varies, grpId=10662) | wired |
| Kicked ETB: GY→BF recursion | `T:Mode$ ChangesZone … ValidCard$ Card.Self+kicked … DB$ ChangeZone Origin$ Graveyard Destination$ Battlefield` | AbilityInstanceCreated grpId=138454 → SelectTargetsReq (GY targets) → ZoneTransfer category=? | **untested — kicked ETB never triggered in session** |

### Ability grpIds

| grpId | Description |
|-------|-------------|
| 10662 | Kicker cost (CastingTimeOptions) |
| 12 | Lifelink |
| 142 | Menace |
| 138454 | ETB trigger (if kicked: return creature from GY to BF) |

## Trace (session 2026-03-25_22-31-50, seat 1)

Seat 1 cast Nullpriest of Oblivion twice. Neither cast paid the kicker. The first copy (iid 292) was later bounced to hand by Unsummon (grpId 75477) and never reached the graveyard. The kicked ETB ability was not observed in this session.

### First cast (gsId 58→62, iid 171→292)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 58 | 171 | Hand (31) | Cast action selected (PerformActionResp) |
| 59 | 171→292 | Hand→Stack | ObjectIdChanged + ZoneTransfer category=CastSpell; CastingTimeOptionsReq sent with ctoId=2 (Kicker, grpId=10662) and ctoId=0 (Done) |
| 59 | — | — | CastingTimeOptionsResp (C-S, empty — kicker not chosen) |
| 60 | 291, 287 | BF | Mana ability instances created and deleted (mana payment) |
| 62 | 292 | Stack→BF | ResolutionStart/Complete grpId=73316; ZoneTransfer category=Resolve; Qualification pAnn fires (Menace, QualificationType=40, grpid=142) |

### Second cast (gsId 252→256, iid 350→357)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 252 | 350 | Hand (31) | Cast action selected |
| 253 | 350→357 | Hand→Stack | ObjectIdChanged + ZoneTransfer category=CastSpell; CastingTimeOptionsReq ctoId=4 (Kicker) + ctoId=0 (Done) |
| 253 | — | — | CastingTimeOptionsResp (C-S, empty — kicker not chosen) |
| 254 | 291, 342 | BF | Mana ability instances created and deleted |
| 256 | 357 | Stack→BF | ResolutionStart/Complete grpId=73316; ZoneTransfer category=Resolve; Qualification pAnn (Menace) |

Note: ctoId for Kicker differs between casts (2 vs 4). The ctoId is not a stable constant — it is assigned per-spell during cast setup.

### Annotations

**Cast → stack (gsId 59, file 000000041_MD_S-C_MATCH_DATA.bin):**
- `ObjectIdChanged` — affectedIds=171, orig_id=171, new_id=292
- `ZoneTransfer` — affectedIds=292, zone_src=31 (Hand), zone_dest=27 (Stack), category="CastSpell"
- persistent `EnteredZoneThisTurn` — affectorId=27 (Stack zone), affectedIds=[292]

**Resolution to BF (gsId 62, file 000000044_MD_S-C_MATCH_DATA.bin):**
- `ResolutionStart` — affectorId=292, affectedIds=292, grpid=73316
- `ResolutionComplete` — affectorId=292, affectedIds=292, grpid=73316
- `ZoneTransfer` — affectorId=1 (seat), affectedIds=292, zone_src=27 (Stack), zone_dest=28 (BF), category="Resolve"
- persistent `Qualification` — affectorId=292, affectedIds=292, SourceParent=292, grpid=142, QualificationSubtype=0, QualificationType=40

**CastingTimeOptionsReq (gsId 59, file 000000041_MD_S-C_MATCH_DATA.bin):**
```json
{
  "castingTimeOptions": [
    {"ctoId": 2, "type": "Kicker", "affectedId": 292, "affectorId": 292, "grpId": 10662},
    {"ctoId": 0, "type": "Done", "isRequired": true}
  ],
  "promptId": 23
}
```

## Gaps

1. **Kicked ETB never observed** — `grpId 138454` (ETB trigger) did not fire in this session. Both casts declined the kicker. The triggered ability wire shape — AbilityInstanceCreated affectorId, SelectTargetsReq targeting shape, ZoneTransfer category for GY→BF return — is completely unknown. Needs a dedicated puzzle: put a creature in GY, cast kicked Nullpriest, observe full ETB flow.

2. **GY→BF transfer category unknown** — Forge DSL uses `DB$ ChangeZone Origin$ Graveyard Destination$ Battlefield`. Real server category string for this is unconfirmed. Candidates: "Return", "Resolve", or something custom. Cross-reference prior conformance research (Exile category, DisplayCardUnderCard) (return-from-exile also unobserved) once kicked ETB is captured.

3. **SelectTargetsReq vs SelectNReq for GY targeting** — per Forge DSL (`ValidTgts$ Creature.YouOwn`, `TgtPrompt$ Select target creature in your graveyard`), this should use SelectTargetsReq. Expected shape: targetIdx=1, one target group listing GY creature instanceIds. Not yet confirmed on wire.

4. **ctoId instability** — ctoId for the Kicker option was 2 on the first cast and 4 on the second cast. Leyline must not hardcode ctoId. The "Done" option always has ctoId=0. Verify the kicker-selection path correctly reads the server-assigned ctoId from the req and echoes it back.

5. **Lifelink modifier annotation shape** — Lifelink grants +life on combat damage, but no `Qualification` for Lifelink (grpId=12) was observed in either cast. Lifelink pAnn shape is unconfirmed for this card.

## Supporting evidence

- `docs/rosetta.md` — zone IDs (Hand=31, Stack=27, BF=28, GY=33)
- `docs/card-specs/mardu-outrider.md` — PayCostsReq for mandatory costs (distinct from CastingTimeOptionsReq for optional kicker)
- prior conformance research (Exile category, DisplayCardUnderCard) — GY return-from-exile unobserved; same gap as kicked ETB
- prior conformance research (TargetSpec pAnn) — TargetSpec in persistentAnnotations (expected once kick-ETB is triggered)
