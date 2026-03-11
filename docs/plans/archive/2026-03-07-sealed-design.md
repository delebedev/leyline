# Sealed Implementation Design

**Date:** 2026-03-07
**Epic:** #24
**Recording:** `recordings/2026-03-07_11-49-05/`

## Summary

Add sealed format support to leyline. Course becomes a first-class persistent domain entity replacing the current static course stubs. Pack generation uses Forge via matchdoor. The course state machine drives the full sealed lifecycle: join → pool → deckbuild → match → W/L tracking → completion.

## Key Decisions

| Question | Decision | Rationale |
|----------|----------|-----------|
| Course scope | General-purpose — all events, not just sealed | Matches real server; avoids parallel systems |
| Match result flow | Matchdoor callback → CourseService updates W/L | Matches real server push model |
| Pack generation | Lambda wired in LeylineServer; Forge stays in matchdoor | Frontdoor keeps zero Forge deps |
| Persistence | Full course state in SQLite, single table | One source of truth, survives restarts |
| Set selection | Extract from event name, fallback to FDN | Real server pattern; FDN as known-good Forge set |
| Architecture | Course-centric (ADR 001) | Simplicity; revisit when draft/tournaments add complexity |

## Domain Model

### Course entity (`frontdoor/domain/`)

```kotlin
@JvmInline value class CourseId(val value: String)

enum class CourseModule {
    Join, Sealed, GrantCardPool, DeckSelect, CreateMatch,
    MatchResults, RankUpdate, Complete, ClaimPrize;
    fun wireName(): String = name
}

data class CollationPool(val collationId: Int, val cardPool: List<Int>)

data class Course(
    val id: CourseId,
    val playerId: PlayerId,
    val eventName: String,
    val module: CourseModule,
    val wins: Int = 0,
    val losses: Int = 0,
    val cardPool: List<Int> = emptyList(),
    val cardPoolByCollation: List<CollationPool> = emptyList(),
    val deck: CourseDeck? = null,
    val deckSummary: CourseDeckSummary? = null,
)

data class CourseDeck(
    val deckId: DeckId,
    val mainDeck: List<DeckCard>,
    val sideboard: List<DeckCard>,
)

data class CourseDeckSummary(
    val deckId: DeckId,
    val name: String,
    val tileId: Int,
    val format: String,
)
```

Constructed events get a Course with empty cardPool and module at CreateMatch or Complete. Sealed courses have the full pool + W/L lifecycle.

### Repository (`frontdoor/domain/`)

```kotlin
interface CourseRepository {
    fun findById(id: CourseId): Course?
    fun findByPlayer(playerId: PlayerId): List<Course>
    fun findByPlayerAndEvent(playerId: PlayerId, eventName: String): Course?
    fun save(course: Course)
    fun delete(id: CourseId)
}
```

### SQLite schema

```sql
CREATE TABLE IF NOT EXISTS courses (
    id           TEXT PRIMARY KEY,
    player_id    TEXT NOT NULL,
    event_name   TEXT NOT NULL,
    module       TEXT NOT NULL,
    wins         INTEGER NOT NULL DEFAULT 0,
    losses       INTEGER NOT NULL DEFAULT 0,
    card_pool    TEXT,
    card_pool_by_collation TEXT,
    deck         TEXT,
    deck_summary TEXT,
    created_at   TEXT NOT NULL DEFAULT (datetime('now')),
    UNIQUE(player_id, event_name)
)
```

## Service Layer

```kotlin
class CourseService(
    private val repo: CourseRepository,
    private val generatePool: (setCode: String) -> GeneratedPool,
) {
    fun join(playerId: PlayerId, eventName: String): Course
    fun setDeck(playerId: PlayerId, eventName: String, deck: CourseDeck, summary: CourseDeckSummary): Course
    fun enterPairing(playerId: PlayerId, eventName: String): Course
    fun recordMatchResult(playerId: PlayerId, eventName: String, won: Boolean): Course
    fun getCoursesForPlayer(playerId: PlayerId): List<Course>
    fun drop(playerId: PlayerId, eventName: String): Course
    fun ensureDefaultCourses(playerId: PlayerId)
}

data class GeneratedPool(
    val cards: List<Int>,
    val byCollation: List<CollationPool>,
    val collationId: Int,
)
```

### State machine (sealed)

```
Join → DeckSelect          (pool generated, stored in course)
DeckSelect → CreateMatch   (deck submitted and validated against pool)
CreateMatch → CreateMatch  (match played, W/L updated, under limits)
CreateMatch → Complete     (hit maxWins or maxLosses from EventDef)
Any → Complete             (drop/resign)
```

### Cross-module wiring

No new module dependencies. `LeylineServer` (composition root) wires:

- Pool generation lambda: closes over matchdoor's Forge access, called by CourseService
- Match result callback: matchdoor calls a `(playerId, eventName, won) -> Unit` lambda that routes to CourseService

Both are plain lambdas, no interfaces, no cross-module deps.

## Handler Changes

| CmdType | Current | New |
|---------|---------|-----|
| Event_Join (600) | Golden JSON | `courseService.join()` → EventWireBuilder |
| Event_SetDeckV2 (622) | Golden JSON, stores deckId in map | `courseService.setDeck()` → EventWireBuilder |
| Event_GetCoursesV2 (623) | Static defaultCourses | `courseService.getCoursesForPlayer()` → EventWireBuilder |
| Event_EnterPairing (603) | Starts match, no course awareness | `courseService.enterPairing()` → uses course deck |
| Event_GetMatchResultReport (608) | Golden JSON | Course state + inventory stub |
| Event_Drop (605) / Resign (606) | Golden/empty | `courseService.drop()` → EventWireBuilder |

## Wire Builder

`EventWireBuilder` additions:

- `buildCourseJson(course: Course): JsonObject` — replaces zeroed-out stub with real course data
- `buildJoinResponse(course: Course): String` — course + inventory wrapper
- `buildSetDeckResponse(course: Course): String` — course without CardPool

## EventRegistry

New fields on `EventDef`: `maxWins`, `maxLosses`, `entryFee` (unused but present for wire accuracy).

New event definition:
```kotlin
EventDef(
    "Sealed_FDN_20260307",
    "Sealed FDN",
    "Sealed",
    formatType = "Sealed",
    maxWins = 7,
    maxLosses = 3,
    bladeBehavior = null,
    eventTags = listOf("Limited"),
)
```

Set code extracted from event name (`Sealed_FDN_...` → `FDN`). Falls back to FDN when Forge doesn't have the set.

## Implementation Phases

### Phase 1: Domain + persistence
Domain model, repository interface, SQLite implementation, CourseService with unit tests.
**Gate:** service tests pass — create, state transitions, W/L tracking, validation.

### Phase 2: Golden wire validation
Extract golden payloads from recording. Wire handlers to return golden JSON for sealed flow. Register sealed event in EventRegistry.
**Gate:** `just serve` → client sees sealed event → joins → deckbuilder opens → submits → no crashes.

### Phase 3: Real logic
Replace golden stubs with CourseService calls. Wire pool generation lambda. Wire match result callback. Full lifecycle.
**Gate:** play through sealed end-to-end with `just serve`.

### Phase 4: Conformance
Diff our wire output against recording. Fix field ordering, missing fields, null vs empty.
**Gate:** response shapes match `just fd-raw` captures from proxy recording.

## Client Wire Reference

From Arena client decompilation:

**EModule** (wire enum, string-serialized): Join=0, Sealed=1, GrantCardPool=2, DeckSelect=3, CreateMatch=4, MatchResults=5, RankUpdate=6, Complete=7, ClaimPrize=8

**ClientPlayerCourseV2** fields: CourseId, InternalEventName, CurrentModule, ModulePayload, CourseDeckSummary, CourseDeck, CurrentWins, CurrentLosses, CardPool, CardPoolByCollation, CardStyles, JumpStart, DraftId, TournamentId, MadeChoice

**EventInfo** lifecycle fields: MaxWins, MaxLosses, MaxGames, CollationIds

## Recording Reference

Session `2026-03-07_11-49-05`, key sequences:

| Step | Seq | CmdType | Key Data |
|------|-----|---------|----------|
| Join | 225→227 | Event_Join | Pay 2000 gems, 84-card pool (6x14), module=DeckSelect |
| Submit deck | 243→244 | Event_SetDeckV2 | 40-card main + 58 sideboard, module→CreateMatch |
| Re-edit | 387→388 | Event_SetDeckV2 | Deck resubmission before queueing |
| Queue | 427→428 | Event_EnterPairing | module=CreateMatch, Payload="Success" |
| Match created | 435 | S2C push | MatchCreated + MatchInfoV3 |
| Match result | 634→635 | Event_GetMatchResultReport | CurrentModule=CreateMatch, inventory, questUpdates |
| Course state | 684 | Event_GetCoursesV2 | CurrentLosses=1, pool preserved, ready for next match |
