# Front Door

Progressive enablement. Every FD handler starts with a minimal client-compatible response, then graduates to real logic when gameplay depends on it.

- **If a static response contains gameplay data** (decks, events, matchmaking, collection), prioritize replacing it with real logic.
- **If a stub is cosmetic** (store, carousel, rewards), it can stay simple longer.
- **New handlers:** start with a minimal response shape, then decide stub vs real.
- **Zero coupling to game engine.** Frontdoor depends on its own domain model and persistence. Never import from matchdoor.
- **Client is schema-sensitive — failure mode is black screen.** Missing keys, enums, or array shapes can fail silently. Graph definitions are a known case: `CampaignGraphManager` expects specific type enums, and graph state must keep matching node/milestone structure. If a graph change causes a black screen, bisect by restoring fields one at a time.

## Protocol Gotchas

### GetFormats (CmdType 6) is protobuf — field numbers are the contract

Served via `FdProtoBuilder.buildFormatsProto()`. Wrong wire type on a field = client silently gets zeros.

Key proto fields on `FormatConfigInfo`:
- **Field 14: `CommandZoneQuota`** — must be a Quota message (min/max), NOT a string. Controls `FormatIncludesCommandZone`, `MinCommandZoneCards`, `MaxCommandZoneCards` on the client's `DeckFormat`. Missing = commander formats broken.
- Field 12: `MainDeckQuota` (Quota message)
- Field 13: `SideboardQuota` (Quota message)

Reference: format-data-client-init research (local notes)

### DeckSelectFormat in ActiveEventsV2 must match format-metadata name

`EventUXInfo.DeckSelectFormat` → `FormatManager.GetSafeFormat(name)`. Mismatch = queue can't filter decks → they silently vanish. Use `"Brawl"` (not `"StandardBrawl"`) for Standard Brawl events.

### FormatLegalities drive queue deck visibility

Client checks `FormatLegalities[formatName]` per deck. Brawl decks: `Brawl=true`, all constructed=`false`. Mismatch = deck shows in wrong queue or disappears.

### Deck IDs must be UUIDs

Non-UUID deck IDs crash `StartHookResponseJsonConverter`. Always use `UUID.nameUUIDFromBytes()` or `UUID.randomUUID()`.

## Bootstrap Data: Formats & Sets

Two bootstrap files control what cards are legal and how sets appear in the client. Both require manual updates when the client card catalog changes.

### format-metadata.json — format definitions and card legality

```
setPools        → named pools of set codes (standard, historic, alchemy, explorer, timeless)
formats[]       → format definitions, each referencing a pool by name
formatGroups[]  → UI grouping (EvergreenFormats, ConstructedSortOrder, BannedFormats)
```

**How card legality works:** Each format has a `pool` field referencing a named set pool. The server sends the pool's set codes as `LegalSets` for that format. The client builds a `_legalSets: HashSet<string>` per format — cards from sets not in the pool are greyed out in the deck builder.

**Brawl inherits from constructed pools:** `Brawl` → `standard` pool, `HistoricBrawl` → `historic` pool. No separate brawl pool needed.

**Deck-level legality** (`FormatLegalities` in deck wire) is separate — that's computed by `DeckWireBuilder.formatLegalities()` based on deck size, not per-card set membership.

### set-metadata.json — set catalog and draft collations

```
sets[]          → per-set entries (code, collations, release date, availability)
setGroups[]     → UI filter groups (AllFilters lists all set codes for the collection filter dropdown)
```

**Set entry fields:**
- `code` — three-letter set code (must match the client card DB `ExpansionCode`)
- `collations[]` — pack definitions: `{code, id}` pairs. Multiple ID ranges exist:
  - `100XXX` — main set base collation (47 entries, sequential)
  - `500XXX` — mythic variant of main sets (36 entries)
  - `400XXX` — Alchemy/digital-only sets (16 entries, e.g. `Y26_ECL`)
  - `200XXX` — supplemental draft products (e.g. `AFR_DRAFT`)
  - Only needed for sets with draft/sealed/pack support. Can be omitted for sets that are only opened via collection.
- `digitalOnly` — availability enum: 0=eternal-only, 1=standard-not-alchemy, 2=available, 3=alchemy-not-standard, 4=historic-only
- `releaseTs` — unix epoch seconds. Use -1 for promo/gift sets.
- `active` — 1 = published and visible
- `currentRelease` — 1 for major sets. Multiple sets can have this flag simultaneously.

**Collation IDs** are sequential client/runtime identifiers. For sets that don't need draft support, the collation array can be omitted.

### Adding a new set — checklist

1. **format-metadata.json**: Add the set code to every `setPools` entry where it's legal (usually all 5: standard, alchemy, historic, explorer, timeless). Standard rotation may eventually drop older sets.
2. **set-metadata.json**: Add an entry to `sets[]` with `code`, `releaseTs`, `active: 1`. Add collations if draft support is needed. Add the code to `setGroups[].AllFilters`.
3. **Rebuild & restart** — `just build && just serve`. No code changes needed.
4. **Verify** — import a deck with cards from the new set, confirm they're not greyed out in the deck builder for the relevant formats.
