# Token Fidelity

Umbrella plan for getting tokens right — grpId resolution, art, lifecycle annotations.
Will be expanded as we encounter more token patterns in recordings.

## Current State

- TokenCreated annotation fires (type 35) ✓
- Token objects appear on battlefield ✓
- grpId wrong (0 or source card's grpId instead of token's) ✗
- TokenDeleted annotation not emitted ✗
- Client shows broken gray placeholder for tokens ✗

## grpId Resolution

The client SQLite DB links cards to their tokens via `AbilityIdToLinkedTokenGrpId`:

```
-- Format: abilityId:tokenGrpId (comma-separated for multiple)
Resolute Reinforcements (93858): 99866:94161  → 1/1 Soldier
Cat Collector (93717):           175756:94156  → 2/2 Cat
```

Token grpIds are real cards in the DB with `IsToken=1`. They carry art, types, subtypes,
P/T — everything the client needs to render them correctly.

### Known token grpIds (from 2026-02-28 recording)

| Token | grpId | P/T | Source |
|-------|-------|-----|--------|
| Soldier | 94161 | 1/1 | Resolute Reinforcements |
| Cat | 94156 | 2/2 | Cat Collector (ability 175756) |
| Otter | 91865 | 1/1 | opponent deck |
| Treasure | 95104 | — | opponent deck |

### Implementation Path

1. Add `tokenGrpIds: Map<Int, Int>` (abilityGrpId → tokenGrpId) to `CardDb.CardData`
2. Parse `AbilityIdToLinkedTokenGrpId` column in `CardDb.query()`
3. When StateMapper encounters a token, look up source card's ability → token grpId
4. Set `grpId` and `overlayGrpId` on the token's `GameObjectInfo`

### Challenge: Forge → Arena ability mapping

Forge's `SpellAbility` doesn't carry an Arena `abilityGrpId`. We need a way to
identify which Forge ability corresponds to which Arena ability. Options:

- **Heuristic:** If a card has exactly one token-producing ability, use that mapping
- **Keyword match:** Match on ability text/type (ETB trigger, activated, etc.)
- **Direct lookup:** Register ability mappings when card enters game (from CardDb)

Most cards have a single token-producing ability, so the heuristic covers ~90%.

## TokenDeleted

Annotation type 41. Fires when a token leaves battlefield (sacrifice, destroy, exile, bounce).
Simple shape: `affectorId=instanceId, affectedIds=[instanceId]`.

Recording example (index 315): Treasure token sacrificed for mana →
TokenDeleted fires after ZoneTransfer(Sacrifice) + diffDeletedInstanceIds.

### Implementation Path

1. Detect token type on zone transfer out of battlefield
2. Emit TokenDeleted annotation after ZoneTransfer annotation
3. Affector and affected = token's post-realloc instanceId

## Future Work

- **Token copies** — tokens that copy other cards (Progenitor Mimic, etc.)
- **Token art variants** — some tokens have multiple art versions (different grpIds)
- **Emblems** — similar to tokens but in Command zone, no grpId lookup needed
- **Conjurations** — digital-only, `AbilityIdToLinkedConjurations` column (n/a for paper)
