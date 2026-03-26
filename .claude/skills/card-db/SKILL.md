---
name: card-db
description: Use when querying the Arena card database — card lookups, ability resolution, schema questions, or any grpId/abilityId investigation. Activates whenever you need card data.
user-invocable: false
---

# Arena Card Database

**Always use `just` tooling first.** Only fall back to raw sqlite3 when the recipes don't cover your query.

## Tooling (prefer these)

| Task | Command |
|------|---------|
| grpId → card name | `just card <grpId...>` (multi-id ok) |
| abilityId → name + text + owners | `just ability <abilityId>` |
| Card name → grpId (all printings) | `just card-grp "<name>"` |
| Card name → Forge script path | `just card-script "<name>"` |
| All cards in a set | `just cards-by-set <SET>` |
| All cards in a recording | `just cards-in-session <session>` |
| Validate puzzle card names | `just puzzle-check <file>` |

## Database Location

```
~/Library/Application Support/com.wizards.mtga/Downloads/Raw/Raw_CardDatabase_*.mtga
```

SQLite. Glob because filename includes a version hash.

## Schema (key tables)

**Cards** — one row per printing
- `GrpId` (PK), `TitleId` (→ Localizations), `IsToken`, `IsPrimaryCard`
- `Types`, `Subtypes`, `Supertypes` — comma-separated proto enum ints
- `Power`, `Toughness` (text — supports `*`, `X`)
- `Colors`, `ColorIdentity` — comma-separated CardColor enum ints
- `AbilityIds` — space-separated `abilityId:textLocId` pairs (e.g. `99866:914228`)
- `HiddenAbilityIds` — engine-only abilities not shown on card
- `AbilityIdToLinkedTokenGrpId` — `abilityId:tokenGrpId` (comma-sep if multiple)
- `AbilityIdToLinkedConjurations` — `abilityId:count:conjuredGrpId`
- `LinkedFaceGrpIds` — DFC/MDFC back face grpId
- `ExpansionCode`, `CollectorNumber`, `Rarity`

**Abilities** — ability definitions
- `Id` (PK), `TextId` (→ Localizations), `Category`, `SubCategory`
- `BaseId` — chains to keyword ability (e.g. specific flashback → BaseId=35=Flashback)
- `AbilityWord` — int enum (landfall=21, raid=27, threshold=33)
- `IsIntrinsicAbility`, `ModalChildIds`

**Localizations_enUS** — English text
- `LocId` (PK), `Loc` (text), `Formatted` (1 for display names)

**Enums** — proto enum value → display name
- `Type` (e.g. 'CardType', 'SubType', 'CardColor'), `Value` (int), `LocId`

Common CardType values: 1=Artifact, 2=Creature, 3=Enchantment, 4=Instant, 5=Land, 6=Planeswalker, 7=Sorcery.

## Common Raw Queries

```sql
-- Card by grpId
SELECT c.GrpId, l.Loc FROM Cards c
JOIN Localizations_enUS l ON c.TitleId = l.LocId
WHERE c.GrpId = 75515;

-- Card by name
SELECT c.GrpId, l.Loc FROM Cards c
JOIN Localizations_enUS l ON c.TitleId = l.LocId
WHERE l.Formatted = 1 AND l.Loc = 'Lightning Bolt';

-- Ability → owning card (when grpId is an abilityId on the stack)
SELECT c.GrpId, l.Loc FROM Cards c
JOIN Localizations_enUS l ON c.TitleId = l.LocId
WHERE c.AbilityIds LIKE '%<abilityId>:%';

-- Ability text
SELECT l.Loc FROM Abilities a
JOIN Localizations_enUS l ON a.TextId = l.LocId
WHERE a.Id = <abilityId>;

-- Token by subtype (64=Soldier)
SELECT GrpId, Power, Toughness FROM Cards
WHERE IsToken = 1 AND Subtypes LIKE '%64%';

-- Enum decode
SELECT e.Value, l.Loc FROM Enums e
JOIN Localizations_enUS l ON e.LocId = l.LocId
WHERE e.Type = 'SubType' ORDER BY Value;
```

## Zone IDs

27=Stack, 28=Battlefield, 29=Exile, 30=Limbo, 31=Hand, 32=Library, 33=Graveyard, 34=Revealed, 35=Library(hidden), 37=Command.

## Key Gotchas

- **grpId on stack ≠ card.** `ResolutionStart` grpId that doesn't match Cards is an abilityId — check the Abilities table, then find the owning card via `AbilityIds LIKE`.
- **AbilityIds format:** space-separated `id:locId` pairs, NOT comma-separated.
- **Name lookups:** use `l.Formatted = 1` to get display names, not raw loc strings.
- **IsPrimaryCard = 1** for canonical name→grpId (skip alt-art reprints).

## More Detail

- Schema: `docs/client-card-db.md`
- Full playbook: `docs/playbooks/card-lookup-playbook.md`
