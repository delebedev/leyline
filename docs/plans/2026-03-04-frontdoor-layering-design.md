# Front Door Layering — Design

Full decomposition of `FrontDoorService` monolith into layered bounded
context following `docs/principles-design.md`.

## Current state

`FrontDoorService` (640 LOC) is handler + dispatcher + service +
repository + response builder. Every CmdType is a `when` branch that
queries `PlayerDb` directly, constructs JSON inline, and writes to
Netty. No domain objects — decks are JSON blobs, preferences are
strings. Defensive null checks (`playerId != null && PlayerDb.isInitialized()`)
repeated in every handler.

## Target structure

```
frontdoor/
  FrontDoorHandler.kt          thin CmdType router, <200 LOC
  FrontDoorReplayStub.kt       replay alternative (unchanged)
  domain/
    Deck.kt                    Deck, DeckId, DeckCard, Format
    Player.kt                  Player, PlayerId, SessionId
    Preferences.kt             typed wrapper
  repo/
    DeckRepository.kt          interface
    PlayerRepository.kt        interface
    SqlitePlayerStore.kt       Exposed DSL, implements both
  service/
    DeckService.kt             deck CRUD + validation
    PlayerService.kt           auth + preferences
    MatchmakingService.kt      deck selection -> match creation
    LobbyStubs.kt              all the {} stubs
  wire/
    DeckWireBuilder.kt         5 deck wire shapes (V2, V3, StartHook, cards, update parse)
    StartHookBuilder.kt        full StartHook assembly
    PlayerWireBuilder.kt       auth + prefs wire shapes (incl. double-wrap guard)
    FdResponseWriter.kt        Netty channel write utilities
```

## Domain types

```kotlin
@JvmInline value class DeckId(val value: String)
@JvmInline value class PlayerId(val value: String)

enum class Format { Standard, Historic, Explorer, Timeless, Alchemy, Brawl }

data class DeckCard(val grpId: Int, val quantity: Int)

data class Deck(
    val id: DeckId,
    val playerId: PlayerId,
    val name: String,
    val format: Format,
    val tileId: Int,
    val mainDeck: List<DeckCard>,
    val sideboard: List<DeckCard>,
    val commandZone: List<DeckCard>,
    val companions: List<DeckCard>,
)
```

`Player` — session identity + screen name.
`Preferences` — typed wrapper around JSON blob (not parsed further yet).

`@Serializable` on domain types is fine for internal persistence.
Separate wire builders handle Arena protocol shapes.

## Repository

```kotlin
interface DeckRepository {
    fun findById(id: DeckId): Deck?
    fun findAllForPlayer(playerId: PlayerId): List<Deck>
    fun save(deck: Deck)
    fun delete(id: DeckId)
}

interface PlayerRepository {
    fun findPlayer(id: PlayerId): Player?
    fun getPreferences(id: PlayerId): Preferences?
    fun savePreferences(id: PlayerId, prefs: Preferences)
    fun ensurePlayer(id: PlayerId, screenName: String)
}
```

`SqlitePlayerStore` implements both using Exposed DSL over the existing
`player.db` schema. No DB migration. Table definitions are private to
the store. `PlayerDb` singleton gets deleted.

In-memory implementations for service-level tests (HashMap-backed).

## Services

`DeckService(decks: DeckRepository)` — CRUD, validation (absorbs
DeckValidator). Takes/returns domain types, no wire format knowledge.

`PlayerService(players: PlayerRepository)` — authenticate (generates
SessionId), preferences get/set.

`MatchmakingService(decks: DeckRepository, matchRegistry: MatchRegistry)`
— replaces the `onDeckSelected` volatile callback. Returns
`MatchInfo(matchId, host, port)`.

`LobbyStubs` — static/object. Returns empty/default responses for 20+
CmdTypes (rank, quests, cosmetics, store). No dependencies. Individual
stubs graduate to real services when implemented.

## Wire builders

`DeckWireBuilder` — `Deck` domain -> V2 summary, V3 summary, StartHook
entry, cards payload. Also `parseDeckUpdate(json, playerId): Deck?` for
inbound 406.

`StartHookBuilder` — assembles full StartHook from services. Replaces
100+ lines of inline JSON patching.

`PlayerWireBuilder` — auth response, preferences response. Double-wrap
guard for preferences lives here (wire format quirk).

`FdResponseWriter` — extracted Netty write utilities: sendJson,
sendEmpty, sendProto, sendRawProto, sendCtrlAck. FdDebugCollector
recording stays here.

## Handler pattern

Handlers that require JSON use routing-level null check:

```kotlin
private fun dispatch(ctx, cmdType, txId, json) {
    when (cmdType) {
        406  -> requireJson(json) { onUpdateDeck(ctx, txId, it) }
        1912 -> requireJson(json) { onSetPreferences(ctx, txId, it) }
        410  -> onDeckSummariesV3(ctx, txId)
        // ...
    }
}
```

Each handler: 3-5 lines. Parse wire input -> call service -> build wire
response -> write. No null checks, no business logic, no JSON
construction.

`playerId` is `PlayerId` on the handler constructor (never null).
`PlayerDb.isInitialized()` checks disappear — store is always ready.

## Dependencies

New Gradle deps:
- `org.jetbrains.exposed:exposed-core:1.1.1`
- `org.jetbrains.exposed:exposed-jdbc:1.1.1`

Existing: `org.xerial:sqlite-jdbc` (already present).

## Wiring

Constructor injection in `LeylineServer`:

```kotlin
val db = Database.connect("jdbc:sqlite:${dbFile.absolutePath}", "org.sqlite.JDBC")
val store = SqlitePlayerStore(db)
val deckService = DeckService(store)
val playerService = PlayerService(store)
val matchmakingService = MatchmakingService(store, matchRegistry)
val lobbyStubs = LobbyStubs
val responseWriter = FdResponseWriter()
val fdHandler = FrontDoorHandler(playerId, deckService, playerService, matchmakingService, lobbyStubs, responseWriter)
```

Match BC reads decks through `DeckRepository` interface. No circular dependency.

## Testing

`FdTag` added to test tags. `just test-fd` for fast iteration during
refactoring. Full `just test-gate` before commit.

Existing `FrontDoorServiceTest` wire tests are the safety net — they
verify client-facing behavior end-to-end. They don't change during
refactoring.

New tests (additive):
- `DeckWireBuilderTest` — V2/V3 shape correctness
- `DeckServiceTest` — validation, CRUD with in-memory repo
- `PlayerServiceTest` — auth, preferences with in-memory repo
- `SqlitePlayerStoreTest` — Exposed DSL queries against real SQLite

## Cross-BC dependency

```
match/ --reads--> frontdoor/repo/DeckRepository  (loadCards for game start)
frontdoor/service/MatchmakingService --creates--> match/MatchRegistry
infra/LeylineServer --wires--> frontdoor/FrontDoorHandler + match/MatchHandler
```

## Safety

- Tests pass before and after each extraction step
- Wire tests don't change — contract stays stable
- Extract to new file, rewire handler, verify tests, then delete old code
- One layer at a time, not big bang
