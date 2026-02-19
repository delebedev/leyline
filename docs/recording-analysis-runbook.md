# Recording Analysis Runbook

How to record a real game, extract golden files, and validate conformance tests.

For day-to-day CLI reference (`rec-*` + `proto-*`), see `docs/recording-cli.md`.

## Prerequisites

- `just dev-build` in forge-nexus (Kotlin-only, ~3-5s; use `just build` only after proto changes)
- MTGA client configured to connect through nexus proxy (certs installed)
- Sparky bot queue selected (Practice → Sparky)

## 1. Record a Game

```bash
rm -rf /tmp/arena-capture/payloads && mkdir -p /tmp/arena-capture/payloads
cd forge-nexus && just serve-proxy
```

Launch MTGA, play a Sparky game. Nexus captures every Match Door payload to `$NEXUS_PAYLOADS` (default `/tmp/arena-capture/payloads/`). Files are `S-C_MATCH_*.bin` (server→client) and `C-S_MATCH_*.bin` (client→server). We only use S→C for conformance.

End the game normally (concede or win). Stop the proxy.

## 2. Trace an ID

```bash
cd forge-nexus && just proto-trace 220
```

Recursively walks every proto field (via reflection) across all `S-C_MATCH_DATA_*.bin` payloads, printing every field where the target ID appears. Follows ObjectIdChanged renames transitively — if `orig_id=220 → new_id=280`, automatically traces 280 going forward.

Output shows the full field path per hit:

```
=== S-C_MATCH_DATA_006.bin ===
  GRE[2]: GameStateMessage gsId=6
    gsm.zones[4].objectInstanceIds[2] = 220
    gsm.gameObjects[3].instanceId = 220
  -> ID renamed: 220 -> 280, now also tracing 280

Summary: 252 hits across 42 files. Traced IDs: [220, 280, 284]
```

IDs flow between field names: `objectInstanceIds` → `instanceId` → `affectedIds` → `affectorId` → `srcInstanceId` → `targetInstanceId` → `attackerInstanceId` → `blockerInstanceId`. The tool catches all of these because it checks every int32 field generically.

## 3. Extract and Analyze

```bash
cd forge-nexus

# Extract fingerprints
just proto-compare --extract /tmp/arena-capture/payloads full-game-N

# Analyze — structured game timeline with pattern labels and golden matching
just proto-compare --analyze src/test/resources/golden/full-game-N.json
```

The analyzer outputs a structured timeline:

```
=== GAME START [19-23] (5 msgs) -- matches golden ===

=== Turn 1 (AI, indices 25-40) ===
  PLAY_LAND (AI) [29-30] <- NEW (no golden)
  PHASE_TRANSITION x5 [31-40] -- matches golden

=== Turn 3 (player, indices 58-99) ===
  PLAY_LAND (player) [66-67] -- matches golden
  TARGETED_SPELL [71-88] <- NEW (no golden)
    targeting [71-75]
    cast [76-77]
    resolution [78-88]
  PHASE_TRANSITION x5 [89-98]
```

Each segment shows:
- Pattern label (PLAY_LAND, CAST_CREATURE, PHASE_TRANSITION, COMBAT, etc.)
- Index range
- Golden match status: "matches golden" / "N divergences" / "NEW (no golden)"
- Turn grouping with AI/player classification

### Analyzer limitations

- **Action-count divergences are false positives.** Different decks produce different action lists (more Cast/Play/ActivateMana). The analyzer reports these as divergences even though the structural shape is identical. Ignore small divergence counts (1-5) on known-stable patterns.
- **Dual SendAndRecord not distinguished.** Late-game land plays send 2 SendAndRecord messages (one per seat). The analyzer labels both as PLAY_LAND; the second is the opponent's view of the same action.
- **COMBAT sub-phases not broken down.** The analyzer groups the entire combat sequence under COMBAT but doesn't label declare-attackers/blockers/damage sub-phases.
- **DRAW detection is heuristic.** Classified by ObjectIdChanged + PhaseOrStepModified + ZoneTransfer at turn start — could misfire on other zone transfers.

### Future improvements

- **Shape-only golden matching**: strip `actionTypes` before diffing to eliminate deck-dependent false positives. Only compare message types, annotations, field presence, and update types.
- **COMBAT sub-labels**: break down into DECLARE_ATTACKERS, DECLARE_BLOCKERS, DAMAGE using the DeclareAttackersReq/DeclareBlockersReq/DamageDealt boundaries.
- **Player cast creature pattern**: currently all creature casts label as "CAST_CREATURE (AI)" since they use SendHiFi. Player-perspective casts (SendAndRecord) should label separately — they produce 2 dual SendAndRecord msgs + ActionsAvailableReq (not the 4-msg SendHiFi pattern).
- **Auto-golden extraction**: `--analyze --extract-goldens` could auto-slice the first occurrence of each pattern into golden files.

## 4. Pattern Catalog

### Game start (post-keep → Main1) — STABLE

5-message pattern, confirmed across 3 recordings:

```
[idx+0] GS Diff, SendHiFi, PhaseOrStepModified x2    ← AI/spectator view (gameInfo+turnInfo+players+timers+actions+annotations)
[idx+1] GS Diff, SendHiFi, empty                      ← marker (turnInfo+actions)
[idx+2] GS Diff, SendAndRecord, PhaseOrStepModified    ← player view (turnInfo+timers+actions+annotations)
[idx+3] PromptReq (promptId=37)
[idx+4] ActionsAvailableReq (promptId=2)               ← first real priority
```

Find by: first `PhaseOrStepModified x2` cluster after mulligan. All 3 recordings: index 19.

### Phase transition — STABLE

Double-diff pair, identical across all recordings:

```
[idx+0] GS Diff, SendHiFi, PhaseOrStepModified   ← turnInfo+actions+annotations
[idx+1] GS Diff, SendHiFi, empty                  ← turnInfo+actions only
```

Every phase change produces this pair. During AI auto-pass, you see long runs of these pairs.

### NewTurnStarted — STABLE (variable annotations)

Turn boundary marker. Always bundled with 4x PhaseOrStepModified (beginning→upkeep→draw→main1):

```
annotations=[NewTurnStarted, PhaseOrStepModified x4]                     ← no permanents
annotations=[NewTurnStarted, PhaseOrStepModified x4, TappedUntappedPermanent x N]  ← with permanents
```

TappedUntappedPermanent count = number of tapped permanents to untap.

### Play land — STABLE

**Player perspective (SendAndRecord):**
```
[idx+0] GS Diff, SendAndRecord, [ObjectIdChanged, UserActionTaken, ZoneTransfer], category=PlayLand
[idx+1] ActionsAvailableReq (updated actions with new mana abilities)
```

Late-game may produce 2 SendAndRecord messages (one per seat with different action lists), then ActionsAvailableReq.

**AI perspective (SendHiFi):**
```
[idx+0] GS Diff, SendHiFi, [ObjectIdChanged, UserActionTaken, ZoneTransfer]
[idx+1] GS Diff, SendHiFi, empty marker
```
No ActionsAvailableReq during AI's turn.

### Cast creature (AI perspective) — STABLE

4-message pattern, confirmed across 3 recordings:

```
[idx+0] GS Diff, SendHiFi, CastSpell (AbilityInstanceCreated, ManaPaid, TappedUntappedPermanent, UserActionTaken, ObjectIdChanged, ZoneTransfer)
[idx+1] GS Diff, SendHiFi, empty marker
[idx+2] GS Diff, SendHiFi, Resolution (ResolutionComplete, ResolutionStart, ZoneTransfer), category=Resolve
[idx+3] GS Diff, SendHiFi, empty marker
```

**Key:** real server sends 4 consecutive GS Diffs with NO ActionsAvailableReq between them. Annotation count scales with mana cost: ManaPaid x N, TappedUntappedPermanent x N for N mana.

### Cast creature (player perspective)

Different from AI perspective. Player sees:

```
[idx+0] GS Diff, SendAndRecord, CastSpell (same annotations as AI view)  ← seat 1
[idx+1] GS Diff, SendAndRecord, CastSpell (same annotations)              ← seat 2
[idx+2] ActionsAvailableReq
```

No separate resolution messages — resolution happens during priority pass. The dual SendAndRecord messages (one per seat) contain different action lists.

### Cast targeted spell (sorcery/instant)

Full targeted spell sequence (player casting, `Send` updateType during targeting):

```
[idx+0] GS Diff, Send, [ObjectIdChanged, PlayerSelectingTargets, ZoneTransfer], category=CastSpell
[idx+1] SelectTargetsReq                          ← prompt: pick target
[idx+2] GS Diff, Send, empty                       ← state during targeting
[idx+3] SelectTargetsReq                          ← re-prompt (target confirmed)
[idx+4] GS Diff, SendHiFi, [AbilityInstanceCreated, ManaPaid, PlayerSubmittedTargets, TappedUntappedPermanent, UserActionTaken x2]  ← cast confirmed
[idx+5] GS Diff, SendHiFi, empty marker
[idx+6...] Resolution varies by spell effect
```

**Key:** `updateType=Send` (not SendHiFi or SendAndRecord) is used during target selection.

### Discard + reveal spell

After targeted spell sequence, resolution involves reveal:

```
GS Diff, Send, [ResolutionStart, RevealedCardCreated x N]  ← reveals N cards
SubmitTargetsResp
SelectNreq                                                  ← "choose N cards to discard"
QueuedGameStateMessage x2                                   ← queued state (opponent's targeting)
GS Diff, SendHiFi, [PlayerSubmittedTargets, ...]            ← choice confirmed
GS Diff, Send, [ResolutionStart, RevealedCardCreated x N]   ← re-reveal
GS Diff, SendHiFi, [ResolutionComplete, RevealedCardDeleted, ZoneTransfer x2], categories=[Discard, Resolve]
```

New message types: `SelectNreq`, `QueuedGameStateMessage`, `SubmitTargetsResp`.
New annotations: `RevealedCardCreated`, `RevealedCardDeleted`, `PlayerSelectingTargets`, `PlayerSubmittedTargets`.
New category: `Discard`.

### Combat — NEW (recording 3)

**Simple combat (no blockers):**
```
DeclareAttackersReq
GS Diff, SendHiFi, CastSpell (AI cast during declare)    ← optional: AI can cast during combat
GS Diff, SendHiFi, empty marker
GS Diff, SendHiFi, Resolution                             ← optional
GS Diff, SendHiFi, empty marker
PHASE_TRANSITION x1-2
SubmitAttackersResp
GS Diff, SendHiFi, empty x2                              ← damage step
```

**Combat with blockers:**
```
DeclareAttackersReq
GS Diff, SendAndRecord x2                                ← attacker state (dual seat)
DeclareAttackersReq (re-prompt)
GS Diff, SendHiFi, [TappedUntappedPermanent]              ← creatures tapped
GS Diff, SendHiFi, empty
GS Diff, SendAndRecord, PhaseOrStepModified               ← enter declare-blockers
DeclareBlockersReq
SubmitAttackersResp
GS Diff, SendHiFi, [TappedUntappedPermanent]              ← blocker state
GS Diff, SendHiFi, empty
GS Diff, SendAndRecord, PhaseOrStepModified               ← enter damage
SubmitBlockersResp
GS Diff, SendHiFi x4                                      ← damage resolution
GS Diff, SendHiFi, [DamageDealt, ModifiedLife, PhaseOrStepModified, SyntheticEvent]  ← damage dealt
```

New annotations: `DamageDealt`, `ModifiedLife`, `SyntheticEvent`.
New message types: `DeclareAttackersReq`, `DeclareBlockersReq`, `SubmitAttackersResp`, `SubmitBlockersResp`.

### Game end (concede)

```
GS Diff, SendAndRecord, empty (actions only, no annotations)  ← repeated 3x
IntermissionReq
```

Appears twice (once per game in match).

## 5. Extract Slices to Golden Files

Use jq to slice `full-game.json` by index range:

```bash
# Game start (always around index 19 after mulligan)
jq '.[19:24]' src/test/resources/golden/full-game.json > src/test/resources/golden/game-start.json

# Phase transition (find first PhaseOrStepModified pair after game-start)
jq '.[31:33]' src/test/resources/golden/full-game.json > src/test/resources/golden/phase-transition.json

# Play land (look for PlayLand category + SendAndRecord)
jq '.[66:68]' src/test/resources/golden/full-game.json > src/test/resources/golden/play-land.json

# Cast creature (look for CastSpell category, 4 consecutive SendHiFi GS Diffs)
jq '.[346:350]' src/test/resources/golden/full-game.json > src/test/resources/golden/cast-creature.json
```

**Index ranges WILL differ between recordings.** Use `--analyze` to find them automatically.

## 6. Run Conformance Tests

```bash
cd forge-nexus
just test-one GameStartConformanceTest
just test-one PhaseTransitionConformanceTest
just test-one PlayLandConformanceTest
just test-one CastCreatureConformanceTest
```

All tests currently use `expectedExceptions = [AssertionError::class]` — they document known gaps. A test that **stops failing** means we closed a gap (remove `expectedExceptions` to lock it in).

## 7. Analyze Divergences

Run a test without `expectedExceptions` (comment it out temporarily) to see the full diff report:

```
Wire conformance FAILED for 'game-start':
Sequence length mismatch: expected=5, actual=4
Message #0: annotationTypes
  expected: [PhaseOrStepModified, PhaseOrStepModified]
  actual:   []
...
```

Key divergence categories:

| Category | Example | Meaning |
|----------|---------|---------|
| **Missing annotations** | `annotationTypes: expected=[PhaseOrStepModified] actual=[]` | BundleBuilder doesn't emit this annotation type |
| **Wrong updateType** | `expected=SendHiFi actual=SendAndRecord` | Per-seat filtering not matching real server |
| **Missing message** | `Sequence length mismatch: expected=5, actual=4` | BundleBuilder produces fewer/more messages |
| **Extra ActionsAvailableReq** | Real: 4 GS Diffs. Ours: 2 GS Diff + 2 ActionsAvailableReq | Over-prompting — AI shouldn't get priority during own resolution |
| **Missing fieldPresence** | `expected=[timers] actual=[]` | We don't populate timer fields |

## Confirmed Stable Patterns

Validated across 3 recordings (757 + 396 + 257 msgs):

| Pattern | Msg count | Stable? | Notes |
|---------|-----------|---------|-------|
| Game start | 5 | Yes | actionTypes vary by deck |
| Phase transition | 2 | **Yes (zero structural diff)** | actionTypes vary |
| Play land (player) | 2 (+1 dual) | Yes | late-game adds dual SendAndRecord |
| Play land (AI) | 2 | Yes | — |
| Cast creature (AI) | 4 | Yes | annotation counts scale with mana cost |
| Cast creature (player) | 2-3 | Yes | dual SendAndRecord + ActionsAvailableReq |
| NewTurnStarted | 2 | Yes | TappedUntappedPermanent count varies |
| Combat | 8-15 | Yes (shape) | variable length, sub-phases depend on blockers |

## updateType Semantics

| Value | Meaning | When |
|-------|---------|------|
| `SendAndRecord` | Sent to the acting player; client records for replay | Player actions, game start (player view) |
| `SendHiFi` | Sent to opponent/spectator; high-fidelity update | Opponent sees your actions, AI turns |
| `Send` | Sent during interactive prompts (targeting, selection) | Target selection flow, resolution reveals |

## Perspective (Seat) Considerations

Real server sends a separate message stream per seat. Differences between acting player and opponent are **envelope-only** — the state content (annotations, zone transfers, objects) is identical.

| Aspect | Acting player | Opponent/spectator |
|--------|--------------|-------------------|
| updateType | SendAndRecord | SendHiFi |
| ActionsAvailableReq | Present (after each GS) | Absent |
| Annotations | Same | Same |
| Zone/object diffs | Same | Same |

**Current test strategy:** player perspective only (seat 1 = human). Sufficient because annotation shapes are perspective-independent. `resolveUpdateType()` handles the envelope split. Opponent perspective conformance is a future concern for PvP/spectator mode, not wire-shape correctness.

**Golden file caveat:** recordings capture one seat's view. If the recorder wasn't acting, the golden is opponent perspective (all SendHiFi, no ActionsAvailableReq). When our test is player perspective, the golden must be regenerated from our output. This is fine for regression testing but weaker for conformance — replace with real-server goldens if we capture a recording from the acting seat.

## Known Gaps (as of 2026-02-15)

Confirmed across 3 recordings:

1. ~~**PhaseOrStepModified annotations**~~ — FIXED. `AnnotationBuilder.phaseOrStepModified()` + `StateMapper.buildTransitionState()` emits on every transition. `updateType` fixed to `SendHiFi`. Phase-transition conformance test passes via shape comparison.
2. **PromptReq message** — real server sends before ActionsAvailableReq in game-start; we skip it
3. ~~**Resolution annotations**~~ — FIXED. ResolutionStart/ResolutionComplete/ZoneTransfer emitted for Resolve category (stack→battlefield). Validated by cast-creature conformance test.
4. ~~**CastSpell annotations**~~ — FIXED. `AnnotationBuilder` factories for ManaPaid/TappedUntappedPermanent/AbilityInstanceCreated. `StateMapper.buildFromGame` emits per-category annotations via `when (category)` branching. Cast-creature golden regenerated for player perspective. Conformance test passes.
5. ~~**PlayLand annotations**~~ — FIXED. ObjectIdChanged + UserActionTaken + ZoneTransfer(PlayLand) emitted per land play. Also fixed race in `advanceToMain1` (check `pending.state.phase` instead of live `game.phaseHandler.phase`). Conformance test passes.
6. ~~**Per-seat updateType**~~ — FIXED. `StateMapper.resolveUpdateType()` picks SendAndRecord (acting player) vs SendHiFi (spectator). `buildEmptyDiff` fixed to SendHiFi. `selectTargetsBundle` uses Send for interactive prompts. All BundleBuilder functions thread updateType through.
7. ~~**AI auto-pass**~~ — FIXED. NexusGamePlayback subscribes to engine events via Guava EventBus. Sends individual GS Diffs (SendHiFi, no ActionsAvailableReq) per AI action. Engine-thread pacing provides animation timing. See ADR-001.

Not yet tested (no conformance test):

8. **Targeted spell flow** — SelectTargetsReq/SubmitTargetsResp/Send updateType
9. **Reveal/discard** — RevealedCardCreated/Deleted, SelectNreq, QueuedGameStateMessage
10. **Activate action type** — non-mana activated abilities (distinct from ActivateMana)
11. ~~**Combat damage annotations**~~ — FIXED. DamageDealt/ModifiedLife/SyntheticEvent emitted at COMBAT_DAMAGE phase via StateMapper. DeclareAttackersReq/BlockersReq already handled by existing priority flow.
12. ~~**Game end**~~ — FIXED. 3x GS Diff (SendAndRecord) with GameOver gameInfo + ResultSpec, then IntermissionReq. Client transitions to end-of-game screen cleanly.

## Fixing a Conformance Gap

Compressed loop for closing a known gap. Each cycle: research → implement → verify.

### Prerequisites

- Gap identified in Known Gaps list above (or discovered via analyzer)
- Golden file exists for the pattern (in `src/test/resources/golden/`)
- Conformance test exists (even if using `expectedExceptions`)

### Step 1: Research — understand the real server shape

Read the golden file for the target pattern:

```bash
cat src/test/resources/golden/<pattern>.json
```

Key fields to note: `annotationTypes`, `updateType`, `fieldPresence`, message count.

Cross-reference with full recordings if needed:

```bash
just proto-compare --analyze src/test/resources/golden/full-game.json
```

Look for the pattern label in the analyzer output — it shows the real server's message sequence for that pattern.

### Step 2: Identify divergences

Read the conformance test to see what our code currently produces. Run the test with `expectedExceptions` temporarily removed (or read test output) to see the `StructuralDiff` report:

```
Message #0: annotationTypes
  expected: [PhaseOrStepModified]
  actual:   []
```

Make a checklist of exact divergences: wrong updateType, missing annotations, missing fields, wrong message count.

### Step 3: Implement — minimal targeted changes

Typical touch points (in order of likelihood):

| Gap type | File to change |
|----------|---------------|
| Missing annotation | `AnnotationBuilder.kt` (add factory) + `StateMapper.kt` (emit it) |
| Wrong updateType | `StateMapper.buildTransitionState()` or `BundleBuilder` |
| Missing actions in message | `BundleBuilder` (build + embed actions) |
| Wrong message count | `BundleBuilder` (add/remove messages in the bundle) |
| New message type | `BundleBuilder` (add new bundle method) |

### Step 4: Format + build + verify

```bash
# from worktree root
just fmt

# from forge-nexus/
just dev-build                # fast Kotlin-only compile (~3-5s)
just test-conformance         # ALL conformance tests in one shot (~5s)
```

`just test-conformance` runs every `*ConformanceTest` class via TestNG `conformance` group. No need to run tests individually.

### Step 5: Update the test

- Remove `expectedExceptions = [AssertionError::class]` from the test annotation
- Switch `assertConformance` → `assertShapeConformance` if the golden has deck-dependent action types
- Update the test description to remove "Expected to fail"

### Step 6: Commit + update this runbook

- Mark the gap as FIXED in Known Gaps
- Note which files changed and what the fix entailed (one line)
- Commit with `feat(nexus):` prefix

### Comparison modes

| Method | What it checks | When to use |
|--------|---------------|-------------|
| `assertConformance` | Everything: types, annotations, actions, field presence (exact) | Golden regenerated from our output |
| `assertShapeConformance` | Types, annotations, updateType, prompt. Skips actionTypes, allows extra fieldPresence | Golden from real server recording (deck-dependent actions differ) |

Shape comparison is preferred when the golden comes from real server recordings, since action types/counts are deck-dependent. Exact comparison is for regression-guarding our own output.

### Efficiency notes

- **Don't read files you already have in context.** The golden + current code are the only inputs. If you've read them, go straight to implementation.
- **`just test-conformance` not `test-one`** — runs all 4+ tests in ~5s, catches regressions immediately.
- **Format before build.** `just fmt` fixes imports/whitespace; `just dev-build` then compiles clean.
- **One commit per gap.** Keep changes reviewable. Touch only the files that need to change.

## Deep Field Comparison

Workflow for discovering field-level protocol gaps by comparing our output against recordings side-by-side. Different from conformance tests (shape-only) — this catches missing fields, wrong types, absent IDs.

### Step 1: Capture our output

```bash
ARENA_DUMP=1 just serve     # starts server with proto dump enabled
# play a land (or whatever action) in the client
ls /tmp/arena-dump/         # find the numbered .txt file for the action
```

### Step 2: Find the matching recording

```bash
just proto-inspect /tmp/arena-capture/payloads/S-C_MATCH_DATA_<timestamp>.bin
```

Look for a recording with matching `gsId`, seat, and action pattern (e.g. same annotation categories). The recording and our output won't match exactly — different game, different IDs, possibly different action (AI vs player perspective).

### Step 3: Compare side by side

Read both full payloads. Don't diff — visual comparison catches structural differences that diffs miss (field ordering, nesting depth, presence vs absence).

### Step 4: Classify differences

**Critical: not all differences are gaps.** Classify each one:

| Classification | Action | Example |
|----------------|--------|---------|
| **By design** | Document why, move on | `updateType: SendHiFi` (recording) vs `SendAndRecord` (ours) — perspective difference, see [updateType Semantics](#updatetype-semantics) |
| **Context-dependent** | Note, don't fix blindly | Timer contents differ because different game state |
| **Confirmed gap** | Write failing test, then fix | Missing `annotation.id` field (always 0 in our output) |
| **Inference** | Label as unconfirmed | "affectorId appears to be the source card" — plausible but derived from one example |

### Step 5: Trace IDs for semantic understanding

```bash
just proto-trace 279     # trace across all recordings
```

Use the trace output + gameObject labels to reconstruct what happened. Example from tracing ID 279 (Swamp) through a creature cast:

| ann.id | affectorId | affectedIds | type | Inferred meaning |
|--------|-----------|-------------|------|------------------|
| 60 | 279 (Swamp) | 281 (ability) | AbilityInstanceCreated | Land created a mana ability on stack |
| 61 | 281 (ability) | 279 (Swamp) | TappedUntappedPermanent | Ability tapped the land |
| 63 | 279 (Swamp) | 280 (Rat) | ManaPaid | Land's mana paid for the creature |
| 64 | 279 (Swamp) | 281 (ability) | AbilityInstanceDeleted | Mana ability cleaned up after use |
| 65 | 2 (player) | 280 (Rat) | UserActionTaken | Player initiated the cast action |

**These are inferences, not confirmed semantics.** Derived from one recording of one action. The field names (`affectorId`, `affectedIds`) and annotation ordering are consistent across recordings, but the exact semantic contract is not documented by the server — we're reverse-engineering from observed behavior.

### Step 6: Write failing tests

Write `PlayLandFieldTest`-style integration tests that assert field-level properties using game context. The test starts a real game, plays the action, captures raw proto messages, and asserts:

- **Structural fields** (must match exactly): annotation type, detail key names, zone IDs
- **Relational fields** (must reference correct game objects): `affectorId` = source card, `affectedIds` = target card
- **Sequential fields** (must be non-zero, monotonic): `annotation.id`
- **Presence fields** (must exist, value flexible): `prevGameStateId`, `persistentAnnotations`, `uniqueAbilities`

```bash
just test-one PlayLandFieldTest   # all should fail (TDD)
# implement fixes
just test-one PlayLandFieldTest   # should pass
just test                         # verify no regressions
```

### Known by-design differences (not gaps)

These appear in comparisons but are intentional:

| Field | Recording | Our output | Why |
|-------|-----------|-----------|-----|
| `update` | `SendHiFi` | `SendAndRecord` | Perspective: recording is opponent's view, our output is player's view. See [updateType Semantics](#updatetype-semantics). |
| `actions` seat list | Both seat 1 and seat 2 actions | Only acting seat's actions | Recordings capture opponent view which includes both seats' action lists |
| Timer `running` | Only changed timer | Both timers | We send full timer state; real server sends delta-only. Low priority. |
| Action counts | Vary by deck/board state | Vary by deck/board state | Deck-dependent, not a structural gap |

## File Inventory

| File | Purpose |
|------|---------|
| `src/main/kotlin/forge/nexus/conformance/StructuralFingerprint.kt` | ID-agnostic message shape — the unit of comparison |
| `src/main/kotlin/forge/nexus/conformance/RecordingParser.kt` | Parses .bin captures → fingerprint sequences |
| `src/main/kotlin/forge/nexus/conformance/StructuralDiff.kt` | Positional diff of two fingerprint sequences |
| `src/main/kotlin/forge/nexus/conformance/GameFlowAnalyzer.kt` | Pattern classifier — structured timeline from fingerprints |
| `src/main/kotlin/forge/nexus/conformance/CompareMain.kt` | CLI: `just proto-compare` / `just proto-compare --analyze` |
| `src/main/kotlin/forge/nexus/debug/Trace.kt` | CLI: `just proto-trace <id>` — cross-payload ID tracing with transitive rename following |
| `src/test/kotlin/forge/nexus/conformance/ConformanceTestBase.kt` | Test helpers: start game, play actions, compare goldens |
| `src/test/kotlin/forge/nexus/conformance/*ConformanceTest.kt` | Per-scenario conformance tests |
| `src/test/resources/golden/full-game.json` | Recording 1: 757 msgs, creature-focused Sparky game |
| `src/test/resources/golden/full-game-2.json` | Recording 2: 396 msgs, sorcery/instant mono-black game |
| `src/test/resources/golden/full-game-3.json` | Recording 3: 257 msgs, simple creatures + combat + concede |
| `src/test/resources/golden/{scenario}.json` | Per-scenario golden slices (from recording 1) |

## Recordings Log

| # | Date | Msgs | Deck | Key actions | Notes |
|---|------|------|------|-------------|-------|
| 1 | 2026-02-15 | 757 | Starter (green/white) | AI land, creature, Ajani | Creature cast, combat, planeswalker. AI on play. |
| 2 | 2026-02-15 | 396 | Mono-black (sorcery/instant) | Kill spells, discard+reveal, bat creature | Targeted spells, reveal mechanic, Activate actions. Concede at turn 8. |
| 3 | 2026-02-15 | 257 | Simple creatures (both) | Land, creature casts, face attack, concede | Combat (with/without blockers), DamageDealt/ModifiedLife. AI on play. |
