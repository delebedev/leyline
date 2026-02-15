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

## 2. Extract Full-Game Fingerprints

```bash
cd forge-nexus && just proto-compare --extract /tmp/arena-capture/payloads full-game
```

This parses all `S-C_*.bin` files, extracts `StructuralFingerprint` from every `GREToClientMessage`, and saves the sequence to `src/test/resources/golden/full-game.json`.

Output shows each fingerprint with index:

```
  [0] ConnectResp gsType=null update=null annotations=[] actions=[]
  [1] DieRollResultsResp ...
  [2] GameStateMessage gsType=Full update=SendAndRecord ...
  ...
```

## 3. Identify Golden Slices

Scan the full-game fingerprint list and identify index ranges for each scenario.

### Game start (post-keep → Main1) — STABLE

5-message pattern, confirmed across 2 recordings:

```
[idx+0] GS Diff, SendHiFi, PhaseOrStepModified x2    ← AI/spectator view (gameInfo+turnInfo+players+timers+actions+annotations)
[idx+1] GS Diff, SendHiFi, empty                      ← marker (turnInfo+actions)
[idx+2] GS Diff, SendAndRecord, PhaseOrStepModified    ← player view (turnInfo+timers+actions+annotations)
[idx+3] PromptReq (promptId=37)
[idx+4] ActionsAvailableReq (promptId=2)               ← first real priority
```

Find by: first `PhaseOrStepModified x2` cluster after mulligan. Both recordings: index 19.

### Phase transition — STABLE

Double-diff pair, identical across recordings:

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

### Cast creature — STABLE

4-message pattern from AI perspective, confirmed across 2 recordings:

```
[idx+0] GS Diff, SendHiFi, CastSpell (AbilityInstanceCreated, ManaPaid, TappedUntappedPermanent, UserActionTaken, ObjectIdChanged, ZoneTransfer)
[idx+1] GS Diff, SendHiFi, empty marker
[idx+2] GS Diff, SendHiFi, Resolution (ResolutionComplete, ResolutionStart, ZoneTransfer), category=Resolve
[idx+3] GS Diff, SendHiFi, empty marker
```

**Key:** real server sends 4 consecutive GS Diffs with NO ActionsAvailableReq between them (AI doesn't get priority prompts during its own cast/resolution). Our BundleBuilder sends GS Diff + ActionsAvailableReq pairs.

Annotation count scales with mana cost: ManaPaid x N, TappedUntappedPermanent x N for N mana.

### Cast targeted spell (sorcery/instant) — NEW

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

**Key new element:** `updateType=Send` (not SendHiFi or SendAndRecord) is used during target selection. This is a third updateType we must support.

### Discard + reveal spell — NEW

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

### Game end (concede) — NEW

```
GS Diff, SendAndRecord, empty (actions only, no annotations)  ← repeated 3x
IntermissionReq
```

The 3 empty GS Diffs may contain final state snapshots. `IntermissionReq` appears twice (once per game in match).

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

**Index ranges WILL differ between recordings.** Always re-identify landmarks by scanning the full fingerprint list. Action type counts differ by deck.

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
| **Action count mismatch** | `expected=[Cast, Cast, Play, Play] actual=[Cast, Play]` | Action list construction differs |

## 7. Comparing Recordings

When you have a new recording, compare against the previous one:

1. **Structural shape match?** Diff goldens ignoring `actionTypes` (deck-dependent). Message types, annotations, field presence, update types should match.
   ```bash
   diff <(jq -S '.' golden/game-start.json) <(jq -S '.[19:24]' golden/full-game-2.json)
   ```
2. **New patterns?** Grep for message types not in the pattern catalog: `SelectTargetsReq`, `SelectNreq`, `QueuedGameStateMessage`, `SubmitTargetsResp`, `IntermissionReq`. Each new type = new conformance test candidate.
3. **New annotation categories?** Grep `annotationCategories` for values beyond PlayLand/CastSpell/Resolve/Discard.

## Confirmed Stable Patterns

Validated across 2 recordings (757-msg creature-focused game + 396-msg sorcery/instant game):

| Pattern | Msg count | Shape identical? | Deck-dependent fields |
|---------|-----------|------------------|-----------------------|
| Game start | 5 | Yes | actionTypes, promptId counts |
| Phase transition | 2 | **Yes (zero diff)** | actionTypes |
| Play land (player) | 2 | Yes | actionTypes, zone/objectCount |
| Play land (AI) | 2 | Yes | actionTypes |
| Cast creature (AI) | 4 | Yes | annotation counts scale with mana cost |
| NewTurnStarted | 2 | Yes | TappedUntappedPermanent count varies |

## updateType Semantics

| Value | Meaning | When |
|-------|---------|------|
| `SendAndRecord` | Sent to the acting player; client records for replay | Player actions, game start (player view) |
| `SendHiFi` | Sent to opponent/spectator; high-fidelity update | Opponent sees your actions, AI turns |
| `Send` | Sent during interactive prompts (targeting, selection) | Target selection flow, resolution reveals |

## Known Gaps (as of 2026-02-15)

Confirmed across 2 recordings:

1. **PhaseOrStepModified annotations** — real server emits on every phase change; we don't
2. **PromptReq message** — real server sends before ActionsAvailableReq in game-start; we skip it
3. **Resolution annotations** — ResolutionStart/ResolutionComplete/ZoneTransfer(Resolve) on spell resolution; we don't emit
4. **CastSpell annotations** — ManaPaid/TappedUntappedPermanent/AbilityInstanceCreated on cast; we don't emit
5. **PlayLand annotations** — ObjectIdChanged/UserActionTaken/ZoneTransfer(PlayLand); we don't emit
6. **Per-seat updateType** — SendHiFi vs SendAndRecord vs Send depends on context; we use a fixed value
7. **AI auto-pass** — real server sends consecutive GS Diffs without ActionsAvailableReq for AI actions; we interleave ActionsAvailableReq

Not yet tested (no conformance test):

8. **Targeted spell flow** — SelectTargetsReq/SubmitTargetsResp/Send updateType
9. **Reveal/discard** — RevealedCardCreated/Deleted, SelectNreq, QueuedGameStateMessage
10. **Activate action type** — non-mana activated abilities (distinct from ActivateMana)
11. **Game end** — IntermissionReq

## File Inventory

| File | Purpose |
|------|---------|
| `src/main/kotlin/forge/nexus/conformance/StructuralFingerprint.kt` | ID-agnostic message shape — the unit of comparison |
| `src/main/kotlin/forge/nexus/conformance/RecordingParser.kt` | Parses .bin captures → fingerprint sequences |
| `src/main/kotlin/forge/nexus/conformance/StructuralDiff.kt` | Positional diff of two fingerprint sequences |
| `src/main/kotlin/forge/nexus/conformance/CompareMain.kt` | CLI: `just proto-compare` |
| `src/test/kotlin/forge/nexus/conformance/ConformanceTestBase.kt` | Test helpers: start game, play actions, compare goldens |
| `src/test/kotlin/forge/nexus/conformance/*ConformanceTest.kt` | Per-scenario conformance tests |
| `src/test/resources/golden/full-game.json` | Recording 1: 757 msgs, creature-focused Sparky game |
| `src/test/resources/golden/full-game-2.json` | Recording 2: 396 msgs, sorcery/instant mono-black game |
| `src/test/resources/golden/{scenario}.json` | Per-scenario golden slices (from recording 1) |

## Recordings Log

| # | Date | Msgs | Deck | Key actions | Notes |
|---|------|------|------|-------------|-------|
| 1 | 2026-02-15 | 757 | Starter (green/white) | AI land, creature, Ajani | Creature cast, combat, planeswalker. AI on play. |
| 2 | 2026-02-15 | 396 | Mono-black (sorcery/instant) | Kill spells, discard+reveal, bat creature | Targeted spells, reveal mechanic, Activate actions. Concede at turn 8. |
