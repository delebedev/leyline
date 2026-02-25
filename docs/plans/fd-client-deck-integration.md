# Plan: Use Client-Selected Deck in Forge Match

## Status: Draft

## Problem

When the user picks a deck in the client UI and clicks Play (Bot Match), we ignore
the selected deck and use hardcoded playtest decks from `playtest.toml`. The user
sees their chosen deck art in the loading screen but plays with a different deck.

## Goal

The deck the user selects in the client's deck picker is the deck Forge uses in
the match. Bot opponent deck can remain configurable.

## What We Know

### Client sends deck ID in CmdType 612

```json
{"deckId":"59ecbc4a-52a2-4b1b-87e5-42d368d2ed35","botDeckId":"22047ee2-...","botMatchType":0}
```

### StartHook has full deck data

`start-hook.json` contains:

- `DeckSummariesV2`: 115 entries with `DeckId`, `Name`, format legalities
- `Decks`: keyed by deck GUID, each containing:
  ```json
  {
    "MainDeck": [{"cardId": 58445, "quantity": 24}, ...],
    "Sideboard": [...],
    "CommandZone": [...],
    "Companions": [...]
  }
  ```

`cardId` values are Arena grpIds. `CardDb.getCardName(grpId)` already resolves
these to Forge card names via the client's local SQLite.

### Existing infrastructure

| Piece | Location | Status |
|-------|----------|--------|
| `CardDb.getCardName(grpId)` | `game/CardDb.kt` | Working — SQLite lookup + cache |
| `CardDb.lookupByName(name)` | `game/CardDb.kt` | Working — reverse lookup |
| `GameBridge.deckAsGrpIds()` | `game/GameBridge.kt` | Working — Forge deck → grpIds (opposite direction) |
| `DeckValidator` | `config/DeckValidator.kt` | Validates .txt deck files |
| `PlaytestConfig.decks` | `config/PlaytestConfig.kt` | Current deck source (file paths) |
| FD stub 612 handler | `server/FrontDoorService.kt:151` | Parses JSON but ignores deckId |

## Implementation Steps

### 1. Parse deck data from StartHook at boot

Load `start-hook.json` → extract `Decks` map (guid → card list). Already loaded
as a string for the StartHook response; parse once into a lookup structure.

```kotlin
// deckId → list of (grpId, quantity)
val clientDecks: Map<String, List<DeckEntry>>
```

### 2. Build Forge Deck from grpIds

For each `{cardId, quantity}` in the MainDeck:
1. `CardDb.getCardName(cardId)` → card name (e.g. "Lightning Bolt")
2. Look up in Forge's card database: `StaticData.instance().commonCards.getCard(name)`
3. Add `quantity` copies to `Deck.main`

Same for Sideboard/CommandZone.

**Risk**: Some Arena-exclusive cards may not exist in Forge's card DB. Need a
fallback strategy — skip unknown cards with a warning, or refuse the deck.

### 3. Wire into match session

When 612 arrives:
1. Extract `deckId` from JSON payload
2. Look up in `clientDecks`
3. Build Forge `Deck`
4. Pass to `MatchHandler` / `GameBridge` as player 1's deck

If lookup fails (unknown deckId, missing cards), fall back to playtest.toml deck
with a log warning.

### 4. Bot deck handling

`botDeckId` is also a GUID from the client's deck list. Could use the same
pipeline to let the user pick the bot's deck. Or keep using a configurable
default from playtest.toml. Low priority.

## Unknowns / Risks

- **Card name mismatches**: Arena uses slightly different names for some cards
  (split cards, adventure cards, DFCs). `CardDb` handles the common cases but
  may miss edge cases. Existing `CardDb` tests would surface this.
- **Missing cards in Forge**: Arena has ~30k cards, Forge has ~28k. A few
  percent will fail lookup. Need graceful degradation.
- **Deck legality**: Forge doesn't validate Arena format legality. A Standard
  deck might include cards Forge doesn't have rules for. The match will still
  start but those cards won't function correctly.
- **CardDb SQLite availability**: `CardDb` reads from the client's installed
  SQLite (`Raw_CardDatabase_*.mtga`). In CI or without client installed, the DB
  is absent. The feature should degrade to playtest.toml decks when CardDb is
  unavailable.

## Effort Estimate

~1 day. Most infrastructure exists. Main work is:
- JSON parsing of deck data from StartHook (~30 lines)
- grpId → Forge Deck builder (~40 lines)  
- Wiring into 612 handler + MatchHandler (~20 lines)
- Error handling / fallback (~20 lines)

## Not In Scope

- Deck editing in the client (read-only)
- Sideboard swapping between games
- Format validation / deck legality checks
- Updating the golden StartHook with different account data
