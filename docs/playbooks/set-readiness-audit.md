# Set Readiness Audit

How to assess whether a set is ready for leyline. Repeatable for any set.

## 1. Forge card script coverage

Does Forge have scripts for every card in the set?

**Get card names from Arena DB:**
```sql
-- DB: ~/Library/Application Support/com.wizards.mtga/Downloads/Raw/Raw_CardDatabase_*.mtga
-- (SQLite despite .mtga extension)
SELECT DISTINCT REPLACE(REPLACE(l.Loc, '<nobr>', ''), '</nobr>', '') AS name
FROM Cards c
JOIN Localizations_enUS l ON c.TitleId = l.LocId AND l.Formatted = 1
WHERE c.ExpansionCode = '<SET_CODE>'
  AND c.IsToken = 0 AND c.IsPrimaryCard = 1 AND c.IsRebalanced = 0
ORDER BY 1;
```

Note: `Formatted = 1` has the display text (with `<nobr>` HTML tags to strip).

**Build Forge card name index:**
```bash
grep -rh "^Name:" forge/forge-gui/res/cardsfolder/ | sed 's/^Name://' | sed 's/^[[:space:]]*//' | sort -u
```

**Cross-reference:** case-insensitive match. For sets with MDFCs/split cards, also check `LinkedFaceType != 0` and verify both face names exist in Forge.

**Output:** total cards, matched, missing. Missing cards = candidates for pool filtering or blockers.

## 2. Mechanic surface area

What mechanics does this set use? Extract from Forge scripts — easier than joining Arena ability tables.

**From the matched card scripts, extract:**
```bash
# Keywords
grep -h "^K:" <matched_scripts> | tr ',' '\n' | sort | uniq -c | sort -rn

# Triggered abilities
grep -h "^T:" <matched_scripts> | head -50

# Activated abilities
grep -h "^A:" <matched_scripts> | head -50

# Static abilities
grep -h "^S:" <matched_scripts> | head -50

# SVars (mechanic implementations)
grep -h "^SVar:" <matched_scripts> | sed 's/SVar:[^:]*://' | sort | uniq -c | sort -rn | head -30
```

**Categorize into:**
- Evergreen keywords (flying, trample, etc.)
- Named mechanics (Raid, Landfall, Kicker, etc.)
- Triggered ability patterns (ETB, dies, attacks, etc.)
- Activated ability types (mana, tap effects, etc.)
- Static ability patterns (anthems, cost reduction, etc.)

## 3. Cross-reference with catalog

Compare extracted mechanics against `docs/catalog.yaml`:

| Catalog status | Meaning for set readiness |
|---|---|
| `works` | Good — verified end-to-end |
| `wired` | Probably works, needs playtesting |
| `partial` | Some cases work, others don't — flag for testing |
| `broken` | Known broken — will hit players |
| `missing` | Not implemented — cards using this won't work |
| Not in catalog | Unknown — must playtest |

**Don't fully trust the catalog.** It may be stale. Use it as a starting point, playtesting as the real test.

## 4. Booster template check

Can Forge generate boosters for this set? Required for Sealed and Draft.

```bash
grep '<SET_CODE>' forge/forge-gui/res/blockdata/boosters.txt forge/forge-gui/res/blockdata/boosters-special.txt
grep '<SET_CODE>' forge/forge-gui/res/blockdata/blocks.txt
```

If no booster template exists, Sealed/Draft will fall back to FDN.

## 5. Special card patterns

Check for card patterns that need specific bridge support:

```sql
-- MDFCs / DFCs
SELECT COUNT(*) FROM Cards
WHERE ExpansionCode = '<SET_CODE>' AND LinkedFaceType != 0
  AND IsToken = 0 AND IsPrimaryCard = 1;

-- Adventures
-- (check Forge scripts for AlternateMode:Adventure)
grep -rl "AlternateMode:Adventure" <matched_scripts> | wc -l

-- Split cards
grep -rl "AlternateMode:Split" <matched_scripts> | wc -l

-- Sagas
grep -rl "Type:.*Saga" <matched_scripts> | wc -l
```

Cross-reference counts with catalog status for `cast-mdfc`, `cast-adventure`, `cast-split`, `saga`. If the set has these and they're `missing` in the catalog, those cards won't work.

## 6. Decision: filter or ship

Based on the audit:

- **100% Forge coverage + all mechanics wired/works:** ship the full set, no filtering needed.
- **Forge gaps:** cards without scripts must be filtered from the pool (Sealed/Draft won't generate them, but Constructed deck import could reference them).
- **Mechanic gaps:** cards with unsupported mechanics technically exist but will behave wrong. Options:
  - Filter them from pool (safe but reduces set)
  - Ship with known limitations documented
  - Implement the missing mechanic (scope decision)

## 7. Playtest validation

No audit replaces playing games. After the paper audit:

1. Build a deck heavy on the set's named mechanics
2. Play 3–5 games against Sparky
3. Log every card that behaves wrong or looks wrong
4. Each broken card maps to either a mechanic gap or a card-specific bug
5. Update catalog with findings

## Appendix: FDN audit results (2026-03-28)

- 517 unique non-token cards, **100% Forge coverage**
- No MDFCs, split cards, or DFCs — all single-faced
- 15 evergreen keywords, 13 named mechanics
- Heaviest categories: ETB triggers (123), flying (67), mana abilities (53)
- Decision: ship full set, no filtering needed
