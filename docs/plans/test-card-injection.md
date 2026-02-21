# Test Card Injection for forge-nexus

**Status:** Plan  
**Date:** 2026-02-21  

## Problem

forge-nexus conformance tests can only use 4 cards (Forest, Llanowar Elves, Elvish Mystic, Giant Growth). GameBridge hardcodes a mono-green stompy deck. TestCardRegistry manually registers synthetic grpIds for these 4 cards. No way to test flying, trample, first strike, deathtouch, instants, auras, removal, multi-color, planeswalkers, tokens, etc.

## Core Tension

Two registries must stay in sync when a card enters a game:
1. **Forge engine** — `Card` object exists in a zone (via `Player.addCard()` / `Card.fromPaperCard()`)
2. **Nexus layer** — `CardDb` has grpId + CardData, `InstanceIdRegistry` has forgeCardId → instanceId mapping

forge-web's `Player.addCard()` only handles #1. If used in nexus tests, `StateMapper.buildFromGame()` falls back to `grpId=0` (blank card metadata) because CardDb has no entry.

## Design

### Two complementary mechanisms

**A. TestCardInjector** — post-start zone injection (scenario setup)

Utility that wraps `Player.addCard()` and additionally:
- Derives `CardDb.CardData` from Forge's `CardRules` (name, types, subtypes, P/T, colors, mana cost)
- Assigns a synthetic grpId and registers in `CardDb`
- Registers the forge cardId → instanceId mapping in `InstanceIdRegistry`

Result: injected card is indistinguishable from a "real" deck card in proto output.

**B. Custom deck lists** — pre-start deck composition

`GameBridge.start()` already accepts `deckList` param. `MatchFlowHarness` already accepts it too. `ConformanceTestBase.startGameAtMain1()` needs a `deckList` param. `TestCardRegistry.ensureRegistered()` needs to auto-register any card name it encounters (by deriving CardData from Forge).

### CardDataDeriver — the key bridge

New utility that converts Forge's `CardRules` → `CardDb.CardData`:

```kotlin
object CardDataDeriver {
    fun fromForgeCard(card: Card): CardDb.CardData
    fun fromCardRules(rules: CardRules, name: String): CardDb.CardData
}
```

Mapping logic:
- **CoreType → proto CardType**: `Artifact=1, Creature=2, Enchantment=3, Instant=4, Land=5, Phenomenon=6, Plane=7, Planeswalker=8, Scheme=9, Sorcery=10, Kindred=11, Vanguard=12, Dungeon=13, Battle=14`
- **Color → proto CardColor**: Forge ColorSet bitmask → `White=1, Blue=2, Black=3, Red=4, Green=5`
- **SuperType**: `Basic=1, Legendary=2, Ongoing=3, Snow=4, World=5`
- **SubType**: Requires a name→int map. Build from proto enum values in `messages.proto`. Start with the ~20 most common subtypes; extend on demand.
- **Power/Toughness**: Direct from `CardRules.getIntPower()` / `getIntToughness()`, empty string for non-creatures
- **ManaCost**: Parse `ManaCost` → `List<Pair<ManaColor, Int>>`
- **AbilityIds**: Assign synthetic sequential abilityGrpIds (10000+). Real grpIds don't matter for conformance — just need unique IDs per ability slot.
- **TitleId**: Assign synthetic sequential titleId (10000+). Only matters for client localization, not conformance.

### TestCardInjector API

```kotlin
object TestCardInjector {
    /** Inject a card by name into a player's zone. Registers in CardDb + InstanceIdRegistry. */
    fun inject(
        bridge: GameBridge,
        playerSeatId: Int,
        cardName: String,
        zone: ZoneType,
        tapped: Boolean = false,
        sick: Boolean = true,
    ): InjectedCard

    data class InjectedCard(
        val card: Card,           // Forge Card object
        val grpId: Int,           // Synthetic grpId in CardDb
        val instanceId: Int,      // Client instanceId in InstanceIdRegistry
        val forgeCardId: Int,     // Forge's Card.id
    )
}
```

Internally:
1. `Player.addCard(cardName, zone, tapped, sick)` — creates Card in Forge
2. `CardDataDeriver.fromForgeCard(card)` — derives CardData
3. `CardDb.registerData(cardData, cardName)` — idempotent if already registered
4. `bridge.ids.getOrAlloc(card.id)` — allocates instanceId
5. Returns `InjectedCard` with all IDs for test assertions

### Synthetic grpId allocation

`TestCardRegistry` already uses 70000–70005. New synthetic IDs start at 80000 and increment. The `CardDataDeriver` maintains a counter and a `name → grpId` cache so the same card name always gets the same grpId within a test run.

### SubType mapping

The hardest part. `messages.proto` defines ~200 SubType enum values. Forge's subtypes are strings. Need a `String → Int` mapping table.

Approach: Build a static map of the ~50 most-used subtypes (Angel, Beast, Bird, Cat, Demon, Dragon, Druid, Elf, Elemental, Goblin, Human, Knight, Merfolk, Soldier, Spirit, Vampire, Wizard, Zombie, Aura, Equipment, Forest, Island, Mountain, Plains, Swamp, etc.). Unknown subtypes get skipped (no proto value) — acceptable for conformance tests.

### Changes to ConformanceTestBase

```kotlin
protected fun startGameAtMain1(
    seed: Long = 42L,
    deckList: String? = null,
): Triple<GameBridge, Game, Int>
```

When `deckList` is provided, pass it to `GameBridge.start()`. Also auto-register CardData for every card name in the deck list via `CardDataDeriver`.

### Changes to TestCardRegistry

Add `ensureCardRegistered(cardName: String)` that:
1. If already in CardDb, return existing grpId
2. Otherwise, derive CardData from Forge's card database and register it

This makes TestCardRegistry a lazy auto-registering cache rather than a hardcoded list.

## Test Plan

### Test 1: Inject Serra Angel, assert proto fields

```kotlin
@Test(groups = ["integration"])
fun `injected card appears in GameStateMessage with correct metadata`() {
    val (b, game, gsId) = startGameAtMain1()
    val injected = TestCardInjector.inject(b, 1, "Serra Angel", ZoneType.Battlefield, sick = false)
    
    val gsm = StateMapper.buildFromGame(game, gsId + 1, "test", b, viewingSeatId = 1)
    val obj = gsm.gameObjectsList.first { it.instanceId == injected.instanceId }
    
    assert obj.grpId == injected.grpId
    assert obj.cardTypesList.contains(CardType.Creature)
    assert obj.power.value == 4
    assert obj.toughness.value == 4
    // Serra Angel has Flying + Vigilance
    assert obj.uniqueAbilitiesCount >= 2
    
    // InstanceIdRegistry consistency
    assert b.getForgeCardId(injected.instanceId) == injected.forgeCardId
    
    // CardDb consistency
    assert CardDb.lookup(injected.grpId) != null
    
    // ClientAccumulator consistency
    val acc = ClientAccumulator()
    acc.seedFull(gsm)
    acc.assertConsistent("after Serra Angel injection")
}
```

### Test 2: Inject to hand, cast through bridge, verify annotation pipeline

```kotlin
@Test(groups = ["integration"])
fun `injected card castable through full pipeline`() {
    // Use custom deck with Plains for white mana
    val (b, game, gsId) = startGameAtMain1(deckList = "30 Plains\n30 Forest")
    
    // Inject Serra Angel to hand
    val injected = TestCardInjector.inject(b, 1, "Serra Angel", ZoneType.Hand)
    
    // Play enough lands for 3WW
    // ... (play lands, pass turns)
    // Cast Serra Angel
    // Assert ZoneTransfer annotation (Hand → Stack)
    // Pass priority to resolve
    // Assert ZoneTransfer annotation (Stack → Battlefield)
    // Assert instanceId changed on zone transfer
    // acc.assertConsistent()
}
```

## File inventory

| File | Change |
|------|--------|
| `src/test/.../TestCardInjector.kt` | **New** — injection utility |
| `src/test/.../CardDataDeriver.kt` | **New** — CardRules → CardData conversion |
| `src/test/.../TestCardRegistry.kt` | Add `ensureCardRegistered()` auto-derive |
| `src/test/.../ConformanceTestBase.kt` | Add `deckList` param to `startGameAtMain1` |
| `src/test/.../CardInjectionTest.kt` | **New** — verification tests |

## Alternatives considered

### Why CardRules derivation is the only real option

| Approach | Problem |
|----------|---------|
| **Query real client SQLite** | Requires MTGA installed on test machine. CI doesn't have it. |
| **Ship a test SQLite snapshot** | Licensing issues, stale data, 100MB+ binary in repo. |
| **Hardcode CardData per card** | What TestCardRegistry does now for 4 cards. Doesn't scale. |
| **Derive from CardRules** (this plan) | Data already in memory after `GameBootstrap.initializeCardDatabase()`. Mapping is mechanical. |

The SubType string→proto-int table is the only annoying part — a one-time static map, extended on demand.

## Future: test scenario DSL

Not in scope for this plan. Revisit if test authoring friction becomes the bottleneck (right now the bottleneck is "can't test any card beyond 4 green ones").

A scenario builder could look like:

```kotlin
val test = nexusScenario(seed = 42) {
    human {
        battlefield("Serra Angel", "Plains", "Plains")
        hand("Giant Growth")
    }
    ai {
        battlefield("Llanowar Elves")
    }
}
// game at Main1, all cards registered, accumulator seeded
```

This is shorter syntax for the same TestCardInjector calls — cosmetic, not structural. The real value of this plan is CardDataDeriver (unlocking any of Forge's 32k cards for nexus tests). DSL is sugar on top; add later if warranted.

## Performance: CardDb SQLite kills test speed

**Finding (2026-02-21):** Integration tests take 8-10s each. The dominant
cost is `CardDb.lookupByName()` falling through to the 221MB Arena SQLite
(`Raw_CardDatabase_*.mtga`) for any card not in `TestCardRegistry`. The
default 60-card deck has ~5 registered cards; the other 55 (lands, dupes)
trigger the lazy SQLite load on first miss.

**Impact:** ~6-8s per test class (one-time load, then cached). Across 7+
integration test classes this adds 6-8s total (loaded once per JVM fork),
but each `lookupByName` query against SQLite also adds ~10-50ms per card.

**Fix path:** `CardDataDeriver.fromCardRules()` (this plan) eliminates the
SQLite dependency entirely. For a quick win before that: `TestCardRegistry`
could pre-register all cards in the default deck list by deriving from
Forge's in-memory `CardRules` at startup. Zero SQLite queries needed.

Alternatively, `CardDb.lookupByName` could short-circuit with `FALLBACK_GRPID`
when a test flag is set, skipping SQLite entirely. Tests don't need real
grpIds — only that proto fields are populated. This is a 5-line change.

## Non-goals

- Production card data derivation (production uses real SQLite DB)
- Complete SubType coverage (add on demand)
- Token creation (separate feature, different code path)
- Ability text/oracle text fidelity (conformance doesn't check localized strings)
