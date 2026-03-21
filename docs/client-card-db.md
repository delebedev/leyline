# Client Card Database (SQLite)

The client ships a SQLite database with card definitions, abilities, localized text, and linkage metadata. Leyline reads it via `CardDb.kt`.

**Path:** `~/Library/Application Support/com.wizards.mtga/Downloads/Raw/Raw_CardDatabase_*.mtga`

## Tables

| Table | Purpose |
|-------|---------|
| `Cards` | One row per printing. grpId is PK. Types/subtypes/colors stored as comma-separated proto enum ints. |
| `Abilities` | Ability definitions. PK is `Id` (not GrpId). `Category`/`SubCategory` classify (activated, triggered, static, etc). |
| `Localizations_enUS` | `LocId` → `Loc` (English text). Cards link via `TitleId`, abilities via `TextId`. |
| `Enums` | `(Type, Value, LocId)` — maps proto enum values to display names (CardColor, CardType, SubType, etc). |
| `AltPrintings` | Alt-art/skin variants. `AltGrpId` → base grpId via `AltToBasePrintings`. |
| `Versions` | DB version (`Data`, `GRP`). |

## Key Card Columns

### Identity & display
- `GrpId` — unique card ID (proto `grpId` everywhere)
- `TitleId` — join to `Localizations_enUS.LocId` for card name
- `IsToken` — 1 for token cards
- `IsPrimaryCard` — 1 for the "main" printing (use for name→grpId lookups)
- `CollectorNumber`, `ExpansionCode` — set/number

### Type line
- `Types` — comma-separated `CardType` proto enum values (e.g. `2` = Creature)
- `Subtypes` — comma-separated `SubType` proto enum values (e.g. `64` = Soldier)
- `Supertypes` — comma-separated `SuperType` proto enum values

### Stats
- `Power`, `Toughness` — text (supports `*`, `X`)
- `Colors`, `ColorIdentity` — comma-separated `CardColor` proto enum values
- `OldSchoolManaText` — mana cost in `oWoU` format (`o` prefix per symbol, digits for generic)

### Abilities
- `AbilityIds` — space-separated `abilityId:textId` pairs (e.g. `99866:914228`)
- `HiddenAbilityIds` — abilities not shown on card but active in engine

### Linkage (tokens, conjurations, faces)

**`AbilityIdToLinkedTokenGrpId`** — maps abilities to the tokens they create.
Format: `abilityId:tokenGrpId` (comma-separated if multiple).

```
-- Resolute Reinforcements → 1/1 Soldier token
93858|99866:94161

-- Cat Collector → two different token types
93717|136211:94177,175756:94156
```

**`AbilityIdToLinkedConjurations`** — maps abilities to conjured cards (draft-specific).
Format: `abilityId:count:conjuredGrpId` (comma-separated).

```
-- Giant Secrets → multiple conjurable options
90144|171060:1:90157,171060:1:90153,...
```

**`LinkedFaceGrpIds`** — DFC/MDFC back face grpId.
**`LinkedFaceType`** — face relationship type (1 = transform, etc).

## Useful Queries

```sql
-- Card by name
SELECT c.* FROM Cards c
JOIN Localizations_enUS l ON c.TitleId = l.LocId
WHERE l.Loc = 'Resolute Reinforcements';

-- Token grpId for a card's ability
SELECT c.GrpId, c.AbilityIdToLinkedTokenGrpId, l.Loc
FROM Cards c JOIN Localizations_enUS l ON c.TitleId = l.LocId
WHERE c.AbilityIdToLinkedTokenGrpId != '' AND l.Loc LIKE '%Cat Collector%';

-- All tokens of a subtype (64 = Soldier)
SELECT GrpId, Power, Toughness, Subtypes FROM Cards
WHERE IsToken = 1 AND Subtypes LIKE '%64%';

-- Ability details
SELECT Id, TextId, Category, SubCategory FROM Abilities WHERE Id = 99866;

-- Enum value → name
SELECT Value, l.Loc FROM Enums e
JOIN Localizations_enUS l ON e.LocId = l.LocId
WHERE e.Type = 'SubType' ORDER BY Value;
```

## Proto Enum Reference

Look up values: `SELECT e.Type, e.Value, l.Loc FROM Enums e JOIN Localizations_enUS l ON e.LocId = l.LocId WHERE e.Type = '<EnumName>';`

Common CardType values: 1=Artifact, 2=Creature, 3=Enchantment, 4=Instant, 5=Land, 6=Planeswalker, 7=Sorcery.
