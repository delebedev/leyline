# Adventure Conformance Report — 2026-03-25_22-22-14

Session: `recordings/2026-03-25_22-22-14/`
Card: Ratcatcher Trainee / Pest Problem (grpId 86845 / 86846)
Seat 1, Turn 9 Main1. Adventure cast then creature cast from exile in the same turn.
Related issue: #173

---

## Limbo body (gap 7)

**Evidence: confirmed**

At gsId 194 (adventure cast):
- Limbo zone 30 objectIds include 338: `[338, 288, 176, 333, 322, 286, 328, …]`
- iid 338 NOT in `diffDeletedInstanceIds` (that list = `[339, 343, 344, 345]`)
- 339 IS in `diffDeletedInstanceIds` — 339 is the hand-resident Adventure proxy, deleted at cast time
- A separate GameObjectInfo for iid 338 is present in the diff objects list:
  ```
  iid=338 grpId=86845 zoneId=30 type=Card visibility=Private power=2 toughness=1 viewers=[1]
  ```
  The creature body retains its creature-grpId (86845) and is Private/viewer-gated while in Limbo.

**Correction to card-spec trace (section gsId 194):** The spec says "338 removed from hand zone" but does not mention that 339 (the hand Adventure proxy) is what ends up in `diffDeletedInstanceIds`, not 338. The creature body 338 simply migrates — it appears in the Limbo zone objectIds without being in the deleted list. This is the Limbo-body mechanism: zone update carries it, no delete event.

---

## Adventure proxy in hand (new finding — not in spec)

Before any cast, at gsId 190, iid 338 (grpId 86845, type=Card) and iid 339 (grpId 86846, type=Adventure) both appear together in the objects list with zoneId=31. However iid 339 is **not** in the Hand zone's `objectIds` list — the zone objectIds contain only `[338, 288, 170, 165]`.

This is the same phantom-object pattern seen for stack and exile proxies: the server emits a companion Adventure proxy that is not zone-tracked but exists as an object. The hand proxy (iid 339) is deleted (via `diffDeletedInstanceIds`) when the adventure is cast.

**Wire fact:** Every card with an adventure side gets a companion Adventure-type proxy from the moment it enters hand. The proxy sits alongside the Card object in hand but is invisible to the zone list. It is deleted at cast time (not zone-transferred).

---

## DisplayCardUnderCard (gap 9)

**Evidence: absent — spec prediction was wrong**

Exhaustive scan of all `persistentAnnotations` across the full recording found zero instances of `DisplayCardUnderCard`. No such annotation appears at gsId 196, in any subsequent GSM, or anywhere in the session.

The exiled creature body (iid 348) is accompanied by an Adventure proxy object (iid 349, type=Adventure, grpId=86846, zoneId=29) that is not in the Exile zone's `objectIds`. This proxy serves the same visual-duality purpose as DisplayCardUnderCard — the client sees both the creature and the adventure-side object in Exile — without requiring a separate annotation.

**Conclusion:** DisplayCardUnderCard is not part of the adventure protocol. The Adventure proxy object (type=Adventure, not in zone objectIds) is the mechanism for rendering the duality in every zone.

---

## Adventure proxy object (gap 2, 8)

### Stack proxies at gsId 194

Two objects on stack simultaneously:

| Field | iid 341 (castable spell) | iid 342 (companion proxy) |
|-------|--------------------------|---------------------------|
| type | Card | Adventure |
| grpId | 86846 | 86846 |
| zoneId | 27 (Stack) | 27 (Stack) |
| owner | 1 | 1 |
| controller | 1 | 1 |
| cardTypes | ["Instant"] | ["Instant"] |
| subtypes | ["Adventure"] | ["Adventure"] |
| uniqueAbilityCount | 1 | 1 |
| visibility | Public | Public |
| power/toughness | absent | absent |

Stack zone `objectIds` = `[341]` — only the Card-type object is zone-tracked. The Adventure proxy (342) is phantom.

Proxy 342 is deleted at gsId 196 (`diffDeletedInstanceIds=[342]`).

### Exile proxies at gsId 196

After resolution, Exile (zone 29) contains:

| Field | iid 348 (creature body) | iid 349 (exile proxy) |
|-------|-------------------------|-----------------------|
| type | Card | Adventure |
| grpId | 86845 | 86846 |
| zoneId | 29 | 29 |
| visibility | Public | Public |
| cardTypes | ["Creature"] | ["Instant"] |
| subtypes | ["Human","Peasant"] | ["Adventure"] |

Exile zone `objectIds` = `[348]` — only the Card object is zone-tracked.

Proxy 349 is deleted at gsId 197 (`diffDeletedInstanceIds=[349, 352, 353]`).

### Stack proxies at gsId 197 (cast from exile)

When the creature is cast from exile, the same pattern repeats:

| Field | iid 350 (creature on stack) | iid 351 (companion proxy) |
|-------|-----------------------------|-----------------------------|
| type | Card | Adventure |
| grpId | 86845 | 86846 |
| zoneId | 27 (Stack) | 27 (Stack) |
| power/toughness | 2/1 | absent |

Stack zone `objectIds` = `[350]` only.

### Battlefield proxy at gsId 199

When the creature resolves onto the battlefield, iid 351 (Adventure proxy) persists with the creature:
- iid 350 (grpId 86845, type=Card) in BF zone objectIds
- iid 351 (grpId 86846, type=Adventure) **not** in BF zone objectIds, but present as an object

iid 351 is deleted at gsId 230 via `diffDeletedInstanceIds=[351]` — when the creature moves to the graveyard (SBA_Damage kill). The Adventure proxy follows the creature through its entire lifetime on the battlefield.

**Pattern summary:** Adventure proxy objects accompany the card through every zone transition. They are never in zone `objectIds` but always present as companion objects. They are deleted when the card leaves the zone (for stack/exile) or when the card dies (for battlefield).

---

## Qualification pAnn (gap 3)

**Full wire shape at gsId 196:**

```json
{
  "id": 446,
  "types": ["Qualification"],
  "affectedIds": [348],
  "details": {
    "SourceParent": 0,
    "grpid": 196,
    "QualificationSubtype": 0,
    "QualificationType": 47
  }
}
```

- `affectorId`: **absent** (not set)
- `SourceParent`: 0 (not referencing any parent)
- `grpid`: 196 (the qualification ability grpId, matches card-spec)
- `QualificationType`: 47
- `QualificationSubtype`: 0

At gsId 197 (cast from exile), `diffDeletedPersistentAnnotationIds=[446, 6]`:
- 446 = Qualification pAnn (confirmed deleted)
- 6 = EnteredZoneThisTurn pAnn (affectorId=29, affectedIds=[348]) — also deleted at cast time

Both the Qualification and the Exile EnteredZoneThisTurn pAnn are co-deleted in the same diff when the creature is cast from exile.

---

## Cast-from-exile action shape (gap 4)

### ActionsAvailableReq at gsId 196 (post-resolve)

The server offers a `Cast` action (not `CastAdventure`) for the exiled card:
```json
{
  "type": "Cast",
  "instanceId": 348,
  "grpId": 86845,
  "shouldStop": true
}
```
`grpId` is the **creature** grpId (86845), not the adventure grpId. `instanceId` is the exiled card's current iid. No adventure-related action type — cast from exile looks like a regular Cast from the action layer.

### Transient annotations at gsId 197

```
ObjectIdChanged   id=449  affectedIds=[348]  details={orig_id:348, new_id:350}   affectorId=ABSENT
ZoneTransfer      id=450  affectedIds=[350]  details={zone_src:29, zone_dest:27, category:CastSpell}  affectorId=ABSENT
ManaPaid          id=454  affectorId=340     affectedIds=[350]  details={id:337, color:3}
ManaPaid          id=459  affectorId=300     affectedIds=[350]  details={id:338, color:4}
UserActionTaken   id=461  affectorId=1       affectedIds=[350]  details={actionType:1, abilityGrpId:0}
```

Key fields:
- `zone_src=29` (Exile) distinguishes this from a hand cast — the only difference
- `category=CastSpell` — identical to normal cast
- `actionType=1` in UserActionTaken — same as regular Cast, not 16 (CastAdventure)
- Both ObjectIdChanged and ZoneTransfer have **no affectorId** — same as the original adventure cast (gsId 194); contrast with the Resolve ZoneTransfer at gsId 196 which has affectorId=1

---

## Other adventure instances

### Picnic Ruiner / Stolen Goodies (grpId 86954 / 86955)

iid 169 (grpId 86954, type=Card) present in hand from gsId 2. CastAdventure action available for it in ActionsAvailableReq from gsId 6 through gsId 55 (grpId 86955 offered). Never chosen — the player never cast the adventure side. Card moves to Limbo at gsId 42. No CastAdventure UserActionTaken found for this card.

### Spellscorn Coven / Take It Back (grpId 86964 / 86965)

iid 362 (grpId 86964, type=Card) in hand from gsId 240. CastAdventure action (grpId 86965) available from gsId 240 through 246. Card moves to Limbo at gsId 243. Never cast. No CastAdventure UserActionTaken for this card either.

**Conclusion:** Only Ratcatcher Trainee / Pest Problem was actually cast as an adventure in this session. The other two adventure cards had the action available but the player chose not to use it.

---

## Additional wire facts from gsId 199 (creature resolves)

Annotation ordering at creature resolution:
1. `ResolutionStart` (affectorId=350, grpid=86845)
2. `ResolutionComplete` (affectorId=350, grpid=86845)
3. `LayeredEffectCreated` (affectorId=350, affectedIds=[7002])
4. `ZoneTransfer` (affectorId=1, zone_src=27, zone_dest=28, category=Resolve)

The persistent AddAbility+LayeredEffect pAnn:
```json
{
  "id": 464,
  "types": ["AddAbility", "LayeredEffect"],
  "affectorId": 350,
  "affectedIds": [350],
  "details": {
    "originalAbilityObjectZcid": 350,
    "UniqueAbilityId": 220,
    "grpid": 6,
    "effect_id": 7002
  }
}
```

`uniqueAbilityCount` on iid 350 at gsId 199 = 2 (vs 1 on stack at gsId 197) — the conditional First Strike static adds one ability to the count when the creature enters the battlefield.

---

## Summary — evidence table

| Gap | Description | Evidence level | Notes |
|-----|-------------|---------------|-------|
| 7 | Limbo body at cast | **confirmed** | iid 338 in zone 30 objectIds at gsId 194; 338 not in diffDeletedInstanceIds; separate GameObjectInfo present |
| 9 | DisplayCardUnderCard on exile | **absent** | No such annotation anywhere in recording; Adventure proxy object (iid 349, type=Adventure) in Exile is the mechanism instead |
| 2 | Adventure proxy object shape | **confirmed** | Stack: iid 341 type=Card + iid 342 type=Adventure; proxy not in zone objectIds; proxy deleted at resolution |
| 8 | Adventure proxy in all zones | **new finding** | Proxy persists through hand→stack→exile→stack→battlefield; same phantom-object pattern in every zone |
| 3 | Qualification pAnn post-exile | **confirmed** | id=446, affectedIds=[348], grpid=196, QualificationType=47, affectorId absent, SourceParent=0 |
| 4 | Cast-from-exile zone_src=29 | **confirmed** | ZoneTransfer category=CastSpell zone_src=29 zone_dest=27; actionType=1; grpId=86845 (creature) in ActionsAvailableReq |
| 5 | Conditional first-strike static | **confirmed** | AddAbility+LayeredEffect pAnn (grpid=6, effect_id=7002, UniqueAbilityId=220) at gsId 199 |
| 6 | Rat token grpId 87031 | **confirmed** | iid 346 and 347 both grpId=87031 type=Token at gsId 196 |
| — | Hand Adventure proxy | **new finding** | iid 339 grpId=86846 type=Adventure in objects at gsId 190 alongside 338; not in zone objectIds; deleted at cast time |
| — | BF proxy lifetime | **new finding** | iid 351 persists on BF from gsId 199 to gsId 230 (creature death); not in zone objectIds throughout |

## Spec corrections

1. **gap 9 (DisplayCardUnderCard):** Prediction wrong. No such annotation exists. Exile Adventure proxy (type=Adventure object, not in zone objectIds) is the server's mechanism.
2. **gsId 194 hand removal:** The spec says creature body 338 is "removed from hand." More precisely: 338 migrates to Limbo zone (appears in zone 30 objectIds), while the hand Adventure proxy 339 is deleted via `diffDeletedInstanceIds`. No delete event for 338.
3. **ObjectIdChanged + ZoneTransfer affectorId at gsId 194 and 197:** Both absent (no affectorId). Spec annotation trace shows this correctly but the prose description implied affectorId=1; that only applies to the Resolve ZoneTransfer at gsId 196.
