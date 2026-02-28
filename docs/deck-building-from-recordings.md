# Building Decks from Recordings

How to extract interesting cards from a proxy recording and build a playtest deck.

## 1. Decode the recording

```bash
just proto-decode-recording recordings/<session>  # auto-discovers capture/payloads/
```

## 2. Extract card grpIds by seat

```bash
# Seat 1 (human) non-token cards
grep -o '"grpId":[0-9]*[^}]*"owner":1' recordings/<session>/md-frames.jsonl \
  | grep -v Token | grep -o '"grpId":[0-9]*' | sort -u | sed 's/"grpId"://'
```

## 3. Resolve grpIds to card names

Query the client SQLite DB:

```bash
DB="$HOME/Library/Application Support/com.wizards.mtga/Downloads/Raw/Raw_CardDatabase_*.mtga"

sqlite3 "$DB" "SELECT c.GrpId, l.Loc, c.Power, c.Toughness, c.OldSchoolManaText
  FROM Cards c JOIN Localizations_enUS l ON c.TitleId = l.LocId
  WHERE c.GrpId IN (93715, 93848, 93856, ...)"
```

See `docs/client-card-db.md` for full schema reference.

## 4. Check card exists in Forge

```bash
grep -rl "Name:Ajani's Pridemate" /path/to/forge/forge-gui/res/cardsfolder/
```

Cards not in Forge's cardsfolder can't be used — skip them or find alternatives.

## 5. Build the deck

Edit `decks/<name>.txt`. Format:

```
Deck
4 Card Name
2 Another Card
24 Plains
```

60 cards total. Set in `playtest.toml` → `[decks] seat1 = "name"`.

## Picking interesting cards

Prioritize cards that exercise mechanics we're working on or testing:

- **Token producers** — tests token grpId resolution, TokenCreated annotation
- **Modal ETBs** — tests CastingTimeOptionsReq flow (Charming Prince, Brutal Cathar)
- **Counter cards** — tests CounterAdded, P/T modification (Ajani's Pridemate)
- **Auras/equipment** — tests attachment annotations (Pacifism, Banishing Light)
- **Lifegain triggers** — tests trigger → counter interaction chains
- **Activated abilities** — tests Activate action type

Check what mechanics appeared in the recording:

```bash
# Annotation types used
grep -o '"types":\["[^]]*"\]' md-frames.jsonl | sort | uniq -c | sort -rn

# Token types
grep -o '"type":"Token"[^}]*"subtypes":\[[^]]*\]' md-frames.jsonl | sort -u

# Action types
grep -o '"type":"[A-Z][a-z]*"' md-frames.jsonl | sort | uniq -c | sort -rn
```

## Future improvements

- Script that takes a recording dir and outputs a Forge-compatible deck file
- Cross-reference with CardDb to auto-filter to Forge-available cards
- `just deck-from-recording <session> <seat>` target
