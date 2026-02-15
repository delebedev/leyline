# Recording Analysis Runbook

How to record a real game, extract golden files, and validate conformance tests.

## Prerequisites

- `just build` in forge-nexus (compiles proto + Kotlin)
- MTGA client configured to connect through nexus proxy (certs installed)
- Sparky bot queue selected (Practice → Sparky)

## 1. Record a Game

```bash
rm -rf /tmp/arena-capture/payloads && mkdir -p /tmp/arena-capture/payloads
cd forge-nexus && just serve-proxy
```

Launch MTGA, play a Sparky game. Nexus captures every Match Door payload to `$NEXUS_PAYLOADS` (default `/tmp/arena-capture/payloads/`). Files are `S-C_MATCH_*.bin` (server→client) and `C-S_MATCH_*.bin` (client→server). We only use S→C for conformance.

End the game normally (concede or win). Stop the proxy.

## 2. Extract and Analyze

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

## 3. Pattern Catalog

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

## 4. Extract Slices to Golden Files

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

## 5. Run Conformance Tests

```bash
cd forge-nexus
just test-one GameStartConformanceTest
just test-one PhaseTransitionConformanceTest
just test-one PlayLandConformanceTest
just test-one CastCreatureConformanceTest
```

All tests currently use `expectedExceptions = [AssertionError::class]` — they document known gaps. A test that **stops failing** means we closed a gap (remove `expectedExceptions` to lock it in).

## 6. Analyze Divergences

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
11. **Combat flow** — DeclareAttackersReq/BlockersReq, DamageDealt/ModifiedLife/SyntheticEvent
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

## File Inventory

| File | Purpose |
|------|---------|
| `src/main/kotlin/forge/nexus/conformance/StructuralFingerprint.kt` | ID-agnostic message shape — the unit of comparison |
| `src/main/kotlin/forge/nexus/conformance/RecordingParser.kt` | Parses .bin captures → fingerprint sequences |
| `src/main/kotlin/forge/nexus/conformance/StructuralDiff.kt` | Positional diff of two fingerprint sequences |
| `src/main/kotlin/forge/nexus/conformance/GameFlowAnalyzer.kt` | Pattern classifier — structured timeline from fingerprints |
| `src/main/kotlin/forge/nexus/conformance/CompareMain.kt` | CLI: `just proto-compare` / `just proto-compare --analyze` |
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
