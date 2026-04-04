---
summary: "Upstream Forge codebase reference: module map, rules engine, game modes, AI, player controllers, and reusable helpers."
read_when:
  - "onboarding to or diving back into Forge engine code"
  - "finding the right Forge module for a feature"
  - "understanding Forge's PlayerController or AI architecture"
---
# Forge Engine Internals

Reference for the upstream Forge codebase — module structure, rules engine,
game modes, AI, player controllers, and reusable helpers.
Re-read this when onboarding or diving back into core Forge.

## Module Map

```
                    ┌─────────────────┐
                    │   forge-core    │
                    │    ~26k LOC     │
                    └────────┬────────┘
                             │
                    ┌────────▼────────┐
                    │   forge-game    │
                    │   ~124k LOC     │
                    │  (rules engine) │
                    └────────┬────────┘
                             │
                    ┌────────▼────────┐
                    │    forge-ai     │
                    │    ~57k LOC     │
                    └────────┬────────┘
                             │
                    ┌────────▼────────┐
                    │    forge-gui    │
                    │    ~66k LOC     │
                    │ (shared UI logic)│
                    └────────┬────────┘
                             │
              ┌──────────────┼──────────────┐
              │              │              │
     ┌────────▼────────┐    ...    ┌───────▼────────┐
     │forge-gui-desktop│           │forge-gui-android│
     │    ~96k LOC     │           │    ~4k LOC      │
     └─────────────────┘           └─────────────────┘
```

| Module | LOC | Description |
|--------|-----|-------------|
| forge-core | ~26k | Card definitions, mana, collections |
| forge-game | ~124k | Rules engine, game state, stack, combat |
| forge-ai | ~57k | AI decision logic, simulation |
| forge-gui | ~66k | Shared GUI logic, screens |
| forge-gui-desktop | ~96k | Desktop Swing/libGDX UI |
| Card scripts | ~288k lines | DSL text files (data, not code — parsed as-is) |

## Game Modes

### Adventure Mode (Shandalar-style RPG)
Overworld exploration with MTG battles. Worlds: Shandalar, Innistrad,
Amonkhet, Crystal Kingdoms, Shandalar Old Border.
Resources: `forge-gui/res/adventure/<world>/`, docs in `docs/Adventure/`.

### Quest Mode
Quick progression without overworld: challenges, duels, precons, bazaar.
Resources: `forge-gui/res/quest/`.

### Puzzle Mode
300+ "Win this turn" scenarios. Format: `.pzl` files with INI-style metadata
+ board state. Resources: `forge-gui/res/puzzle/*.pzl`.

### Constructed Formats
Sanctioned: Standard, Modern, Pioneer, Legacy, Vintage, Pauper, Historic.
Casual: Commander, Brawl, Oathbreaker, Conspiracy, Premodern.
Resources: `forge-gui/res/formats/`.

### Limited
Sealed, Draft, Cube. Resources: `forge-gui/res/sealed/`, `draft/`, `cube/`.

### Planar Conquest
Strategic conquest across 27 MTG planes.
Resources: `forge-gui/res/conquest/planes/`.

## Rules Engine (forge-game)

~124k LOC — the heart of Forge.

### Package Breakdown

| Package | LOC | Description |
|---------|-----|-------------|
| ability/ | 32.5k | Effect implementations (200+ effects) |
| card/ | 22.7k | Card representation, state, properties |
| trigger/ | 11.7k | Triggered abilities (~130 trigger types) |
| (root) | 11.3k | Game, GameAction, Match |
| spellability/ | 8k | Spell/ability framework |
| cost/ | 7.8k | Mana/cost payment system |
| player/ | 6.5k | Player state, actions |
| staticability/ | 5.4k | Continuous effects |
| replacement/ | 3.7k | Replacement effects |
| combat/ | 3.2k | Combat system |
| keyword/ | 2.3k | Keyword abilities |
| phase/ | 2.1k | Turn structure, phases |
| zone/ | 1.8k | Game zones (hand, library, etc.) |
| mana/ | 1.4k | Mana pool |
| event/ | 1.2k | Game events |

### Core Classes

**`Game.java`** (~49k bytes) — top-level game state:
- `allPlayers` / `ingamePlayers` — player collections
- `stack` (`MagicStack`) — the stack
- `phaseHandler` — turn/phase management
- `triggerHandler`, `replacementHandler`, `staticEffects` — effect layers
- Phase hooks: `untap`, `upkeep`, `beginOfCombat`, `endOfCombat`, `endOfTurn`, `cleanup`

**`GameAction.java`** (~128k bytes) — monolithic orchestrator for moving cards
between zones, resolving effects, state-based actions, combat resolution.

**`ApiType.java`** — central enum mapping ~200 effect types to implementations:
```java
public enum ApiType {
    Draw(DrawEffect.class),
    DealDamage(DamageDealEffect.class),
    Destroy(DestroyEffect.class),
    ChangeZone(ChangeZoneEffect.class),
    // ... 200+ more
}
```

### Effect Pattern

Each effect extends `SpellAbilityEffect`:
```java
public class DrawEffect extends SpellAbilityEffect {
    @Override public void resolve(SpellAbility sa) {
        int numCards = AbilityUtils.calculateAmount(...);
        for (Player p : targets) { p.drawCards(numCards, sa, params); }
    }
}
```
Effects read params from card DSL: `sa.getParam("NumCards")` ← `NumCards$ 2`.

### Trigger System
~130 types in `trigger/`: `TriggerChangesZone` (ETB/LTB), `TriggerDamageDone`,
`TriggerSpellAbilityCastOrCopy`, `TriggerDrawn`, etc.
`TriggerHandler` manages registration and firing.

### Replacement Effects
`ReplacementHandler` intercepts events before they happen: damage prevention,
zone change replacement ("if would die, exile instead"), token replacement.

### Static Abilities
`StaticEffects` manages continuous effects: P/T mods, ability granting/removal,
cost modifications. Uses the MTG layer system (comp rules).

### Phase Control
- `PhaseHandler.mainLoopStep()` advances the game loop, invokes player controllers
- `PhaseHandler.devModeSet(...)` jumps phases/turns (deterministic testing)
- Active player: `PhaseHandler.getPlayerTurn()`

### Stack Freeze Mechanism
`MagicStack.freezeStack()` / `addAndUnfreeze()` prevents triggers from
interrupting cost payment. Known hazard: if `payComputerCosts()` fails after
freeze, stack stays frozen — defensive `unfreezeStack()` needed in callers.

### Hand-Zone Activation (Channel, Cycling, Transmute)

Some abilities activate from hand instead of battlefield. Forge handles these
generically — no special keyword code for Channel.

**Card DSL pattern:**
```
A:AB$ DealDamage | Cost$ 1 R Discard<1/CARDNAME> | ActivationZone$ Hand | PrecostDesc$ Channel —
```

**Zone restriction flow:**
1. `SpellAbilityRestriction.setRestrictions()` parses `ActivationZone` → stores as `ZoneType`
2. `SpellAbilityVariables.zone` defaults to `ZoneType.Battlefield`
3. `checkZoneRestrictions()` compares card's current zone against the ability's required zone
4. `AbilityActivated.canPlay()` → `getRestrictions().canPlay()` → `checkZoneRestrictions()`

**Discard-as-cost:**
- `CostDiscard.doPayment()` → `Player.discard()` → `game.getAction().moveToGraveyard()`
- Fires `GameEventCardChangeZone` (standard zone-change event) + `TriggerType.Discarded`
- `Discard<1/CARDNAME>` means discard the source card itself (`CostPart.payCostFromSource()`)
- Cost payment order: `CostDiscard.paymentOrder()` returns 10

**Not a keyword.** Channel is a combination of properties:
- `ActivationZone$ Hand` — restrict to hand zone
- `Discard<1/CARDNAME>` — self-discard cost
- `PrecostDesc$ Channel —` — display text

Same pattern used by Cycling (`Discard<1/CARDNAME>` + `ActivationZone$ Hand` + `DB$ Draw`)
and Transmute (`Discard<1/CARDNAME>` + `ActivationZone$ Hand` + `DB$ ChangeZone`).

## Player Controller Architecture

Two separate paths for interactive decisions:

### Path 1: IGuiBase (GUI-level)
```
Engine → PlayerControllerHuman → IGuiBase.getChoices() / showOptionDialog() / order()
```
Generic UI prompts — engine asks GUI to show a dialog.

### Path 2: PlayerController (game-level)
```
Engine → Player.getController() → PlayerController.arrangeForScry() / choosePermanentsToSacrifice() / etc.
```
~60 abstract methods. Game-specific decisions called directly by the engine.
Desktop: `PlayerControllerHuman` (blocks on Swing). AI: `PlayerControllerAi` (heuristics).

Some decisions have **both** paths — engine may call either depending on code path.

### Key PlayerController Methods

**High-frequency:** `confirmAction`, `confirmTrigger`, `chooseBinary`,
`chooseSingleEntityForEffect`, `chooseCardsForEffect`, `chooseModeForAbility`,
`arrangeForScry`, `choosePermanentsToSacrifice`, `chooseCardsToDiscardFrom`.

**Medium:** `chooseColor`, `chooseNumber`, `orderMoveToZoneList`,
`assignCombatDamage`, `arrangeForSurveil`.

**Rare:** `chooseStartingPlayer`, `sideboard`, `specifyManaCombo`,
`chooseContraptionsToCrank`, `chooseSector`, `vote`.

## AI Controller (forge-ai)

### Two-Layer Split
```
PlayerController (abstract)
  └─ PlayerControllerAi  ───→ AiController ("brains")
       (engine adapter)          (strategy & evaluation)
```

`PlayerControllerAi` — implements engine callbacks, thin delegate to `brains`.
`AiController` — owns strategy: spell evaluation, combat prediction, card memory.

### `chooseSpellAbilityToPlay()` — Decision Chain

**Non-empty stack (responding):**
1. Own spell on top? → pass (let resolve). Exception: `mustRespond` flag.
2. Direct counterspells → `chooseCounterSpell(getPlayableCounters())`.
3. ETB counter creatures → `getPossibleETBCounters()`.
4. Other instant-speed responses → general spell list.

**Empty stack (own turn):**
1. Pre-land-drop spells (`PlayBeforeLandDrop` SVar).
2. Land drop via `chooseBestLandToPlay()` — scores color fixing, utility.
3. Regular spells sorted by `ComputerUtilAbility.saEvaluator`.

### Counter Evaluation
Scores via `ComputerUtil.counterSpellRestriction()`: activated ability (+40),
re-target (+35), unless cost vs opponent's mana, restricted targets (+10).
Higher score = more specific = preferred (saves generic counters).

Profile-based CMC thresholds in `AiProps`: `MIN_SPELL_CMC_TO_COUNTER`,
`ALWAYS_COUNTER_*` flags, `CHANCE_TO_COUNTER_CMC_1/2/3`.

### Spell-Play Pipeline (`handlePlayingSpellAbility`)
```
1. setSplitStateToPlayAbility(sa)
2. addSpliceEffects(sa)              // Splice onto Arcane
3. moveToStack(source, sa)
4. addExtraKeywordCost(sa)           // Convoke, Casualty
5. CharmEffect.makeChoices(sa)       // Modal selection
6. freezeStack(sa)                   // Prevent trigger interruption
7. payComputerCosts(...)
   ├─ success → addAndUnfreeze(sa)
   └─ failure → recovery path
```

## Reusable Engine Helpers

### Draft & Limited (forge-gui)
- **`CardRanker`** — synergy-aware pack ranking (pool + color commitment)
- **`DeckColors`** — color-lane tracking during draft
- **`BoosterDeckBuilder`** (`LimitedDeckBuilder`) — auto-build 40-card deck
- **`LimitedDeckEvaluator`** — deck quality score

### Combat & Mana (forge-ai)
- **`ComputerUtilCombat`** — `lifeInDanger()`, `attackerWouldBeDestroyed()`, `lifeThatWouldRemain()`
- **`ComputerUtilMana.canPayManaCost(sa, player, 0, false)`** — full mana affordability check
- **`ComputerUtilCost`** — `getMaxXValue()`, `getAvailableManaColors()`
- **`ComputerUtilAbility`** — `getOriginalAndAltCostAbilities()`

### Parsing & Validation (forge-core)
- **`DeckRecognizer`** — multi-format decklist parser (MTGO/Arena/Moxfield)
- **`DeckFormat.Limited`** — deck validation beyond `size >= 40`
- **`CardPredicates`** / **`CardLists`** — filter/query helpers

### Mana Affordability Caveat
`CostPartMana.canPlay()` always returns `true` — the engine's
`SpellAbility.canPlay()` does NOT check mana affordability. Desktop works
around this by checking on-click. Use `ComputerUtilMana.canPayManaCost()`
for pre-highlighting playable cards.

## Desktop UI Structure

### Layout Regions
| Region | Content |
|--------|---------|
| Top Bar | Game tab, navigation |
| Left Panel | Stack, Combat, Log tabs; turn info; prompt area |
| Center Top | Opponent's battlefield |
| Center Bottom | Player's battlefield |
| Right Panel | Card detail + card image |
| Bottom | Player's hand |

### Battlefield Grouping (PlayArea.java)
Row collectors: `collectAllLands`, `collectAllCreatures`, `collectAllTokens`,
`collectAllOthers`, `collectAllContraptions`. Stack-size caps: lands 5,
creatures 4, tokens 5, others 4. `mirror` flag flips row order for opponent.

### Key UI Files
- Match controller: `forge-gui-desktop/.../screens/match/CMatchUI.java`
- Battlefield: `forge-gui-desktop/.../view/arcane/PlayArea.java`
- Hand: `forge-gui-desktop/.../view/arcane/HandArea.java`
- Stack view: `forge-gui-desktop/.../screens/match/views/VStack.java`

### Update Flow
Zone updates → `CMatchUI.updateZones()` → `VField.getTabletop().update()` / `VHand.updateHand()`.
Stack updates → `CMatchUI.updateStack()` → `CStack.update()` → `VStack.updateStack()`.

## Card Loading Pipeline

### Startup Flow
```
FModel.initialize()
  → CardStorageReader(cardDataDir, loadCardsLazily=false)
  → StaticData(cardReader, ...)
    → cardReader.loadCards()           # 25-thread parallel parse
    → CardDb.initialize()              # build lookup indices
    → TokenDb, editions, variants...
```

- **32K unique card definitions** in `forge-gui/res/cardsfolder/` (a-z subdirs)
- **~100K+ PaperCard instances** (each card × ~3 printings avg)
- **~100-150 MB RAM**, **5-10s** startup on modern hardware

### Data Model

| Object | Size | Scope |
|--------|------|-------|
| **CardRules** | ~2KB | Shared across all printings — name, type, oracle text, keywords (raw strings, parsed at game runtime) |
| **PaperCard** | ~250B | One per set printing — edition, art index, collector number, rarity |
| **CardDb indices** | ~10-15 MB | `allCardsByName`, `uniqueCardsByName`, `rulesByName`, `normalizedNames` |

### File Organization
Card scripts are alphabetical (`cardsfolder/a/`, `/b/`, ...), not by set.
Set→card mapping lives in `CardEdition` data (`forge-gui/res/editions/`).

### Lazy Loading Infrastructure
Built, tested, disabled. `loadCardsLazily` flag in `CardStorageReader` (set `false` in `FModel.java:209`).
- `StaticData.attemptToLoadCard(name)` — single-card on-demand load
- `CardStorageReader.transformName()` — name→file path mapping
- Test: `CardDbLazyCardLoadingCardMockTestCase`

### Key Files
| File | Role |
|------|------|
| `forge-core/.../CardStorageReader.java` | File/ZIP loading, lazy flag |
| `forge-core/.../StaticData.java` | Singleton, attemptToLoadCard |
| `forge-core/.../card/CardDb.java` | Indices, loadCard, initialize |
| `forge-core/.../card/CardRules.java` | Card definition + parser |
| `forge-core/.../item/PaperCard.java` | Card instance (per printing) |
| `forge-gui/.../model/FModel.java` | Startup, loadCardsLazily=false |

## Persistence

### Quick Reference

| Data | Format | Location |
|------|--------|----------|
| Decks | `.dck` text | `~/.forge/decks/{type}/` |
| Quest saves | GZIP XML (XStream, v13 schema) | `~/.forge/quest/saves/` |
| Gauntlet/Tournament | GZIP XML | `~/.forge/gauntlet/`, `tournament/` |
| Conquest | Plain XML + `.dck` files | `~/.forge/conquest/saves/` |
| Game state dump | key=value text (dev mode only) | `~/.forge/games/` |

### Deck Format (`.dck`)
Human-readable: `[metadata]` header, then `[Main]`, `[Sideboard]`, `[Commander]` sections.
Each card line: `4 Lightning Bolt|M20|1` (count, name, edition, art index).
Storage: `StorageImmediatelySerialized<T>` auto-persists on add/delete.
Key classes: `DeckSerializer`, `DeckStorage`, `Deck`, `CardPool`.

### Game State (dev mode)
`GameState.java` — full serialize/deserialize of game state as `key=value` text.
Captures: turn, phase, life, all zones with per-card state (tapped, counters, attachments, flags).
Methods: `initFromGame()`, `toString()`, `parse()`, `applyToGame()`.
No user-facing "save game" — dev panel only.

### Draft/Sealed — No Mid-Process Persistence
Desktop cannot pause/resume a draft. Only completed drafts saved as `DeckGroup` directories
(`human.dck` + `ai-1.dck`..`ai-7.dck`). `BoosterDraft` state is in-memory only.

### Quest Mode
Most complex persistence: XStream XML with 13 schema versions and incremental migrations.
Tracks: card collection, credits, win/loss, inventory items, combat pets, format rules.

### Directory Constants
All paths in `ForgeConstants.java` (lines 220-327). Platform defaults:
Windows `%APPDATA%\Forge\`, macOS `~/Library/Application Support/Forge/`, Linux `~/.forge/`.
