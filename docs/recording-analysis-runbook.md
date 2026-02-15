# Recording Analysis Runbook

How to record a real game, extract golden files, and validate conformance tests.

## Prerequisites

- `just build` in forge-nexus (compiles proto + Kotlin)
- MTGA client configured to connect through nexus proxy (certs installed)
- Sparky bot queue selected (Practice → Sparky)

## 1. Record a Game

```bash
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

Scan the full-game fingerprint list and identify index ranges for each scenario. Key landmarks:

### Game start (post-keep → Main1)

Look for the first cluster after mulligan. Pattern:
- 2-3 `GameStateMessage/Diff` messages (SendHiFi and SendAndRecord, PhaseOrStepModified annotations)
- `PromptReq` (with promptId)
- `ActionsAvailableReq` (first real priority with Play/Cast actions)

The server sends separate perspectives: `SendHiFi` = AI/spectator view, `SendAndRecord` = active player view.

Previous recording: indices 19-23 (5 messages). The count may vary — look for the PhaseOrStepModified cluster followed by the first ActionsAvailableReq with real actions.

### Phase transition

Double-diff pattern. Look for adjacent `GameStateMessage/Diff` pairs where:
- First has `PhaseOrStepModified` in `annotationTypes` + `actions` + `turnInfo`
- Second has same `actionTypes` but no `annotations`

Both are `SendHiFi` (AI perspective) or `SendAndRecord` (player perspective). Previous recording: indices 31-32.

### Play land (player action)

Identified by `ZoneTransfer` annotation with `PlayLand` category. Pattern:
- `GameStateMessage/Diff` with `annotationCategories=["PlayLand"]`, `annotationTypes` includes `ObjectIdChanged`, `UserActionTaken`, `ZoneTransfer`. Has `zones`, `objects`, `actions`, `annotations` in fieldPresence. updateType=`SendAndRecord`.
- `ActionsAvailableReq` with updated action list (new mana abilities from the land).

Previous recording: indices 196-197 (player's land play). AI land plays look different (SendHiFi perspective, may have ActivateMana in actions but fewer annotation details).

### Cast creature

Complex multi-message sequence. Look for `ZoneTransfer` with `CastSpell` category. Real server pattern (AI casting, SendHiFi perspective):
- `GameStateMessage/Diff` — CastSpell annotations (AbilityInstanceCreated, ManaPaid, TappedUntappedPermanent, UserActionTaken, ObjectIdChanged, ZoneTransfer). Rich: 10+ annotation entries.
- `GameStateMessage/Diff` — empty marker (turnInfo + actions only)
- `GameStateMessage/Diff` — Resolution (ResolutionStart, ResolutionComplete, ZoneTransfer/Resolve)
- `GameStateMessage/Diff` — empty marker

**Key difference from our output:** real server sends 4 consecutive GameStateMessage/Diff messages with no `ActionsAvailableReq` between them (AI doesn't get priority prompts during its own turn). Our BundleBuilder sends GS Diff + ActionsAvailableReq pairs.

For player casting: look for `SendAndRecord` updateType with CastSpell category, followed by priority prompts.

Previous recording: indices 199-202 (AI cast, SendHiFi).

### Other patterns to look for

- **NewTurnStarted annotation**: marks turn boundaries
- **SubmitAttackersReq / SubmitBlockersReq**: combat sequences
- **Multiple perspectives**: same game action often produces SendHiFi (to opponent/spectator) AND SendAndRecord (to acting player) messages. Don't confuse them.

## 4. Extract Slices to Golden Files

Use jq or a script to slice `full-game.json` by index range:

```bash
# Example: extract indices 19-23 for game-start
jq '.[19:24]' src/test/resources/golden/full-game.json > src/test/resources/golden/game-start.json

# Example: extract indices 31-32 for phase-transition
jq '.[31:33]' src/test/resources/golden/full-game.json > src/test/resources/golden/phase-transition.json

# Play land (player perspective)
jq '.[196:198]' src/test/resources/golden/full-game.json > src/test/resources/golden/play-land.json

# Cast creature (AI perspective)
jq '.[199:203]' src/test/resources/golden/full-game.json > src/test/resources/golden/cast-creature.json
```

**Index ranges WILL differ between recordings** — the game plays out differently each time. Always re-identify landmarks by scanning the full fingerprint list.

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
Message #0: fieldPresence
  expected: [actions, annotations, gameInfo, players, timers, turnInfo]
  actual:   [gameInfo, objects, players, timers, turnInfo, zones]
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

1. **Same patterns?** Check that game-start, phase-transition, play-land, cast-creature sequences still have the same structural shape. The real server should be deterministic for the same game actions.
2. **New patterns?** Look for message types or annotation categories not seen before. Games with different decks or longer duration may expose enchantments, instants, activated abilities, planeswalkers.
3. **AI vs player perspective:** Real server sends both SendHiFi (to the non-acting player) and SendAndRecord (to the acting player). Our BundleBuilder currently doesn't distinguish — this is a known gap.

## Known Gaps (as of 2026-02-15)

From first Sparky recording (757 GRE messages):

1. **PhaseOrStepModified annotations** — real server emits on every phase change; we don't
2. **PromptReq message** — real server sends before ActionsAvailableReq in game-start; we skip it
3. **Resolution annotations** — ResolutionStart/ResolutionComplete/ZoneTransfer(Resolve) on spell resolution; we don't emit
4. **CastSpell annotations** — ManaPaid/TappedUntappedPermanent/AbilityInstanceCreated on cast; we don't emit
5. **PlayLand annotations** — ObjectIdChanged/UserActionTaken/ZoneTransfer(PlayLand); we don't emit
6. **Per-seat updateType** — SendHiFi vs SendAndRecord depends on which player the message targets; we use a fixed value
7. **AI auto-pass** — real server sends consecutive GS Diffs without ActionsAvailableReq for AI actions; we interleave ActionsAvailableReq

## File Inventory

| File | Purpose |
|------|---------|
| `src/main/kotlin/forge/nexus/conformance/StructuralFingerprint.kt` | ID-agnostic message shape — the unit of comparison |
| `src/main/kotlin/forge/nexus/conformance/RecordingParser.kt` | Parses .bin captures → fingerprint sequences |
| `src/main/kotlin/forge/nexus/conformance/StructuralDiff.kt` | Positional diff of two fingerprint sequences |
| `src/main/kotlin/forge/nexus/conformance/CompareMain.kt` | CLI: `just proto-compare` |
| `src/test/kotlin/forge/nexus/conformance/ConformanceTestBase.kt` | Test helpers: start game, play actions, compare goldens |
| `src/test/kotlin/forge/nexus/conformance/*ConformanceTest.kt` | Per-scenario conformance tests |
| `src/test/resources/golden/full-game.json` | Complete recording (all fingerprints) |
| `src/test/resources/golden/{scenario}.json` | Per-scenario golden slices |
