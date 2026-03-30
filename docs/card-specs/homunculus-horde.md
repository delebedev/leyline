# Homunculus Horde — Card Spec

## Identity

- **Name:** Homunculus Horde
- **grpId:** 93754 (alt printing: 95217)
- **Set:** FDN (Foundations)
- **Type:** Creature — Homunculus
- **Cost:** {3}{U}
- **P/T:** 2/2
- **Forge script:** `forge/forge-gui/res/cardsfolder/h/homunculus_horde.txt`

## What it does

1. **Cast ({3}{U}):** Standard creature spell from hand.
2. **Triggered ability:** Whenever you draw your second card each turn, create a token that's a copy of Homunculus Horde.

The copy token has the same grpId, stats, and abilities as the original — including the triggered ability itself, so each copy can spawn further copies on subsequent turns.

## Mechanics

| Mechanic | Forge DSL | Forge event | Catalog status |
|----------|-----------|-------------|----------------|
| Cast creature | standard | ZoneTransfer CastSpell | works |
| "Draw your second card" trigger | `T:Mode$ Drawn \| Number$ 2` | AbilityInstanceCreated (source_zone=28) | **missing** — draw-count triggers not in catalog |
| CopyPermanent — token copy of self | `DB$ CopyPermanent \| Defined$ Self` | TokenCreated (affectorId=ability iid) | **missing** — CopyPermanent mechanic not wired |
| Token with full copy semantics | — | gameObject with `isCopy:true`, same grpId/overlayGrpId as source | wired (token infrastructure) |
| Token deletion on bounce/death | — | TokenDeleted after ZoneTransfer | wired |

### Unobserved mechanics

None — the trigger fired multiple times across T14 and T16. Both original and copy-spawned triggers observed. Token bounce and damage-kill also observed.

## Trace (game 2026-03-30_21-37-32, seat 1)

Seat 1 cast Homunculus Horde T14. Trigger fired T14 (creating 1 token) and T16 (creating 2 tokens — one from the original, one from the T14 copy). Two tokens were bounced by Run Away Together (TokenDeleted). One token was killed by Reduce to Ashes (SBA_Damage → exile → TokenDeleted).

### T14 — Cast + first trigger

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 320 | 162→411 | Hand→Stack | ObjectIdChanged; ZoneTransfer category=CastSpell; 4x ManaPaid (Islands) |
| 322 | 411 | Stack→Battlefield | ResolutionStart/Complete grpId=93754; ZoneTransfer category=Resolve |
| 328 | 411→[421] | Battlefield | AbilityInstanceCreated affectorId=411 affectedIds=[421] source_zone=28 (second-draw trigger goes on stack) |
| 333 | 421→[423] | →Battlefield | TokenCreated affectorId=421 affectedIds=[423]; AbilityInstanceDeleted for 421 |

### T16 — both copies trigger (original 411 + token 423)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 391 | 411→[450], 423→[451] | Battlefield | Two AbilityInstanceCreated — original (411→450) and copy (423→451) both trigger on second draw |
| 396 | 451→[453] | →Battlefield | TokenCreated from copy's trigger (451→453) |
| 398 | 450→[454] | →Battlefield | TokenCreated from original's trigger (450→454) |

### T16 — tokens bounced by Run Away Together

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 414 | 453→464 | Battlefield→Hand | ObjectIdChanged 453→464; ZoneTransfer category=Return zone_dest=31; TokenDeleted iid=464 |
| 425 | 454→474 | Battlefield→Hand | ObjectIdChanged 454→474; ZoneTransfer category=Return zone_dest=31; TokenDeleted iid=474 |

### T17 — token killed by damage

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 465 | 423→493 | Battlefield→Exile | DamageDealt 5 from Reduce to Ashes; ObjectIdChanged 423→493; ZoneTransfer category=SBA_Damage zone_dest=Exile; TokenDeleted iid=493 |

### Key findings

- **Token copy gets same grpId as original (93754).** `overlayGrpId` also 93754. `objectSourceGrpId` = 93754. `type` = `GameObjectType_Token`. `isCopy: true` flag set. This is the CopyPermanent pattern — the client renders the token identically to the original card.
- **Token inherits the triggered ability.** `uniqueAbilities: [{id: 216, grpId: 175791}]` — ability grpId 175791 resolves to the "draw second card" trigger text. Each copy independently fires AbilityInstanceCreated on subsequent turns.
- **Ability resolution grpId = 175791 (not 93754).** ResolutionStart/Complete on the trigger use grpId=175791, confirming the trigger is a distinct ability object from the card itself.
- **TokenCreated affectorId = ability instance, not source permanent.** affectorId=421 (the trigger ability on stack), not 411 (the permanent). Same pattern as Electroduplicate.
- **Token bounce = ZoneTransfer Return + immediate TokenDeleted.** The token gets ObjectIdChanged → ZoneTransfer (category=Return, zone_dest=31 Hand) → TokenDeleted. The token ceases to exist as a state-based action when it reaches hand.
- **Token SBA death = ZoneTransfer SBA_Damage + TokenDeleted.** Reduce to Ashes exiled the token via SBA_Damage (lethal damage). Token goes to exile (zone 29), then TokenDeleted fires.
- **parentId on token = ability instance iid.** Token 423 has parentId=421 (the trigger ability that created it). Not the source permanent.

## Gaps for leyline

1. **CopyPermanent token creation.** The copy token must clone grpId, overlayGrpId, objectSourceGrpId, power, toughness, color, cardTypes, subtypes, and uniqueAbilities from the source permanent. Must set `isCopy: true` and `type: GameObjectType_Token`. Currently tokens are created from AbilityIdToLinkedTokenGrpId mappings — CopyPermanent bypasses this entirely.
2. **"Draw your second card" trigger (Mode$ Drawn, Number$ 2).** Forge fires this trigger internally, but leyline needs to emit AbilityInstanceCreated when it fires. Draw-count tracking per turn per player is prerequisite infrastructure.
3. **Ability grpId 175791 resolution.** The trigger ability needs its own grpId (175791) in AbilityInstanceCreated and ResolutionStart/Complete. AbilityRegistry must map Homunculus Horde's triggered ability to this grpId.
4. **Self-replicating trigger on copies.** Each copy token carries the same triggered ability (grpId 175791). When the second draw happens, ALL Homunculus Horde permanents (original + copies) independently fire triggers. Leyline must register the trigger on each new copy token.
5. **parentId on copy tokens.** Token gameObject needs `parentId` = the ability instance iid that created it (not the source permanent). Current token builder may not set this correctly for triggered-ability-sourced tokens.

## Tooling feedback

### Commands used

| Command | Worked? | Notes |
|---------|---------|-------|
| `just card-grp "Homunculus Horde"` | Yes | Returned both printings (93754, 95217) |
| `just card-script "Homunculus Horde"` | Yes | Direct path to Forge script |
| `just scry-ts trace "Homunculus Horde" --game 2026-03-30_21-37` | Yes | Excellent — showed full lifecycle across 4 turns, 10 instanceIds |
| `just scry-ts trace --json --gsid <N>` | Yes | Raw annotations for deep inspection |
| `just scry-ts gsm show <N> --json` | Yes | Full diff including gameObjects — essential for inspecting token fields |
| `just scry-ts ability 175791` | Yes | Resolved trigger text immediately |
| `just scry-ts board --gsid 322` | Yes | Quick board context after cast |
| `just scry-ts game notes` | Yes | Human observations provided investigation direction |

### Missing/awkward

- **gameObject fields not in trace output.** `trace --json` shows annotations only. To see `isCopy`, `overlayGrpId`, `parentId`, `uniqueAbilities` on the token, had to use `gsm show --json` and manually find the object. A `trace --objects` or similar flag would save significant time.
- **No way to filter gsm show output to a specific instanceId.** Full GSM JSON is large; had to visually scan for the token's gameObject block.

### Wish list

- `trace --objects` — include gameObject diffs for affected instanceIds alongside annotations
- `gsm show <N> --instance <iid>` — filter gameObject output to a specific instanceId
- Card name resolution in trace output: **Yes, worked great** — all instanceIds annotated with card names
- `scry ability <id>`: **Yes, essential** — resolved 175791 to trigger text in one call
- Opponent zone labeling (ours/theirs): would have helped when reading bounce targets in gsm 414

## Supporting evidence needed

- [ ] Card spec for a CopyPermanent that copies an opponent's creature (different grpId source)
- [ ] Card spec for a CopyPermanent that copies a token (copy of a copy)
- [ ] Puzzle: Homunculus Horde + cantrip → verify second-draw trigger fires and token appears
- [ ] Cross-ref: Electroduplicate (`docs/card-specs/electroduplicate.md`) — same CopyPermanent mechanic but via sorcery, not ETB trigger; compare token shape
