# Recording Is the Spec

Conformance pipeline for verifying leyline's protocol output against real Arena server recordings.

## Principles

1. **The recording is the spec.** No guessing whether a field is optional, no confusing affectorId with affectedId. If the real server sent it, we send it. If it didn't, we don't. Every conformance question is answered by mining recordings.

2. **Templatize, don't compare literally.** Instance IDs differ between engines. Extract a recording segment, replace instance IDs with symbolic slots (`$SPELL`, `$TARGET`, `$LAND`), keep everything else literal. The template is the oracle.

3. **Match cards, not just shapes.** Puzzles use the same cards (grpIds) as the recording segment. Lightning Bolt targeting Grizzly Bears in the recording → Lightning Bolt targeting Grizzly Bears in the puzzle. This stabilizes ability IDs, annotation detail values, and card-specific behavior. Fewer variables to bind.

4. **Segments, not full games.** Compare bounded interaction units: a spell cast through resolution, a combat phase, a triggered ability chain. Each segment has a clear start (action/priority) and end (next priority pass or phase transition). Full games are too noisy — segments isolate the behavior under test.

5. **Reduce to active set.** Before comparing, strip objects that were present before and after the segment but never touched during it. If a permanent wasn't created, destroyed, moved, targeted, annotated, or mutated — it's scenery. Exclude it from comparison.

6. **Sequence, not snapshot.** A resolved spell is 4-8 messages in sequence: action → stack → priority passes → resolution → state update. Conformance means the sequence of state transitions matches, not just that each message looks right in isolation. Message N is only valid in context of N-1 through N-k.

7. **Annotations are the hard layer.** Zone transfers and game objects are structurally simple — the shapes match or they don't. Annotations carry semantic weight: type numbers, detail keys, affector/affected IDs, categories, ordering relative to other annotations. Most conformance failures are annotation failures.

8. **Dialogs are a separate pipeline.** GroupReq/GroupResp (scry, kicker, modes), DeclareAttackers/BlockersReq, MulliganReq — these are client-facing choice points with their own shapes. They're bounded, have clear expected structure from recordings, and template cleanly. Treat them as first-class segment types alongside state transitions.

## Pipeline

```
Recording                    Puzzle + Engine Run             Comparison
─────────                    ──────────────────             ──────────

1. Mine segment              3. Write puzzle                5. Bind IDs
   from recording               same cards as recording       grpId + zone → instance ID
   (spell cast, combat,         same board position           build binding table
   trigger chain)                                             $SPELL=4217, $TARGET=4183

2. Templatize                4. Run engine                  6. Hydrate + diff
   replace instanceIds          get leyline's output          apply bindings to template
   with symbolic slots          for same interaction          compare against actual
   keep grpIds, types,                                        report divergences
   detail keys literal
```

### Step 1: Mine segment

Extract a bounded interaction from a proxy recording. Segment boundaries:

| Segment type | Start | End |
|---|---|---|
| Spell cast + resolve | `ActionsAvailableReq` with cast action | Priority pass after resolution (`ResolutionComplete` annotation) |
| Land play | `ActionsAvailableReq` with play action | Next `ActionsAvailableReq` or phase transition |
| Combat phase | `PhaseOrStepModified` → Combat/BeginCombat | `PhaseOrStepModified` → Main2 or Ending |
| Triggered ability | `AbilityInstanceCreated` in resolution | `AbilityInstanceDeleted` + `ResolutionComplete` |
| ETB trigger chain | `ZoneTransfer` (Resolve) with `AbilityInstanceCreated` | Final `ResolutionComplete` + `AbilityInstanceDeleted` |
| Modal/dialog | `GroupReq` or `DeclareBlockersReq` | Corresponding `Resp` + GSM update |
| Mulligan | `MulliganReq` | First `ActionsAvailableReq` |

Input: proxy recording directory + segment index range.
Output: ordered list of `GREToClientMessage` + `ClientToGREMessage` protos.

### Step 2: Templatize

For each message in the segment:
- Scan all instance ID fields (instanceId, affectorId, affectedIds, source, targets, etc.)
- Build a symbol table: first occurrence of ID X → assign slot name based on role (card name, zone, position)
- Replace all occurrences of ID X with its slot name
- Keep literal: grpIds, zone IDs, annotation types, detail keys, detail values (except IDs), phase/step, player info

The symbol naming heuristic: resolve grpId → card name via `just card <grpId>`, use that as the slot base. `$LIGHTNING_BOLT`, `$GRIZZLY_BEARS_1`. If multiple instances of same card, suffix with ordinal.

Output: template file (JSON with `$SLOT` placeholders) + symbol table definition.

### Step 3: Write puzzle

From the recording segment's pre-state:
- Extract board position (which cards in which zones for each player)
- Use the same card names (matched by grpId)
- Set life totals, active player, phase

The puzzle reproduces the recording's board state at the segment start. Not the full game — just enough context for the interaction.

### Step 4: Run engine

Load the puzzle in leyline (`just serve-puzzle` or `ConformanceTestBase.startPuzzleAtMain1`), execute the same action sequence as the recording segment, capture leyline's output messages.

### Step 5: Bind IDs

Build a binding table mapping template slots to leyline instance IDs:
- For each slot in the template, find the corresponding object in leyline's state by grpId + zone + position
- If grpId is unique on the board (common for named cards), binding is trivial
- If ambiguous (e.g., two copies of same card), use zone position or creation order

### Step 6: Hydrate + diff

Apply bindings to the template, producing a concrete expected message sequence. Compare field-by-field against leyline's actual output.

Diff levels:
- **Structural:** message count, message types, annotation types present — already handled by `StructuralFingerprint`
- **Semantic:** annotation detail keys and values, zone transfer categories, field presence — the template layer
- **Sequence:** message ordering, annotation ordering within a GSM — position-sensitive comparison

## Layers

The pipeline operates at three layers, each catching different classes of bugs:

### Layer 1: Structural (existing)

`StructuralFingerprint` + `StructuralDiff` — message shapes, annotation type lists, field presence. Catches: missing messages, wrong message types, missing annotations, missing fields.

**Status: implemented.** `GameFlowAnalyzer` classifies segments, `GoldenSequence` stores baselines.

### Layer 2: Semantic (the template layer)

Templatized recording segments with ID slots. Catches: wrong detail key values, swapped affector/affected, wrong categories, optional-vs-required field confusion, wrong zone IDs in transfers.

**Status: not implemented.** This is the core of the new pipeline.

### Layer 3: Sequence (state transitions)

Ordered comparison of message sequences within a segment. Catches: messages in wrong order, missing intermediate states, wrong priority flow, wrong phase transitions.

**Status: partially implemented.** `SemanticTimeline` does event-level sequencing. Needs segment-scoped comparison.

## State Reduction

Before comparing at Layer 2 or 3, reduce the segment to its **active set**:

1. Snapshot object IDs at segment start (from prior GSM)
2. Snapshot object IDs at segment end (from final GSM)
3. Scan all messages in the segment for referenced IDs (annotations, zone transfers, actions, objects in diffs)
4. **Active set** = IDs referenced during the segment
5. **Scenery** = IDs present at both boundaries but never in active set
6. Strip scenery from both template and actual before comparison

This prevents false positives from bystander permanents whose field ordering or diff inclusion varies between engines.

## Quick Start

```bash
# One-shot: engine test → template → diff (with golden regression check)
just conform PlayLand [session]

# Capture golden baseline after a successful run
just conform-golden PlayLand [session]

# Manual steps (if needed):
just tape segment list [session]                          # 1. List segments
just tape segment template PlayLand [session] > /tmp/t.json  # 2. Templatize
just test-one ConformancePipelineTest                     # 3. Run engine
just tape conform run /tmp/t.json matchdoor/build/conformance/playland-frame.json  # 4. Diff
just tape conform run /tmp/t.json engine.json --json      # 4b. JSON output
just tape conform run /tmp/t.json engine.json --golden golden.json  # 4c. With regression check
```

Session defaults to the most recent recording with `md-frames.jsonl`. Substring matching works: `CHALLENGE-STARTER-SEAT1` matches the full timestamp prefix.

## What Exists Today

| Pipeline step | Tool | Status |
|---|---|---|
| Record proxy sessions | `just serve-proxy` + SessionRecorder | Done |
| Decode recordings | `RecordingDecoder` + `just proto-decode-recording` | Done |
| Classify segments | `GameFlowAnalyzer` | Done (structural level) |
| Structural fingerprint | `StructuralFingerprint` | Done |
| Structural diff | `StructuralDiff` (strict + shape) | Done |
| Golden baselines | `GoldenSequence` JSON files | Done |
| Annotation variance | `AnnotationVariance` | Done |
| Annotation contracts | `AnnotationContract` | Done |
| Semantic timeline | `SemanticTimeline` + `GREToDecoded` | Done |
| Card lookup (grpId↔name) | `just card`, `just card-grp` | Done |
| Puzzle loading | `PuzzleSource` + `PuzzleHandler` | Done |
| Puzzle test harness | `MatchFlowHarness` + `ConformanceTestBase` | Done |
| Instance ID tracing | `Trace.kt` + `just proto-trace` | Done |
| Mine segment from recording | `md-segments.py list/extract` | **Done** |
| Templatize segment | `md-segments.py template` | **Done** |
| Puzzle generation from recording | `md-segments.py puzzle` | **Done** |
| Engine JSON serializer | `AnnotationSerializer.kt` | **Done** |
| Engine run tests | `ConformancePipelineTest.kt` | **Done** |
| Bind IDs + hydrate + diff | `md-segments.py diff` | **Done** |
| JSON diff output | `md-segments.py diff --json` | **Done** |
| Golden regression check | `md-segments.py diff --golden <file>` | **Done** |
| One-shot recipe | `just conform <category> [session]` | **Done** |
| Golden capture recipe | `just conform-golden <category> [session]` | **Done** |
| PlayLand golden baseline | `matchdoor/src/test/resources/golden/conform-playland.json` | **Done** |
| Wire spec schema | `docs/conformance/wire-spec-schema.md` | **Done** |
| Diagnosis schema | `docs/conformance/diagnosis-schema.md` | **Done** |
| **State reduction (active set)** | — | **Not built** |

## Discoveries

Findings from running the pipeline against `recordings/2026-03-08_19-44-CHALLENGE-STARTER-SEAT1` (Starter Challenge, seat 1).

### Annotation ordering is per-category, not global

`annotationsForTransfer` emits a fixed schema-driven order per `TransferCategory`. Recording order reflects engine execution order (tap → activate mana → pay → delete → cast). The client likely doesn't care about ordering within a single GSM diff — it animates by type, not by position. Not a conformance failure unless we find evidence otherwise.

### Mana ability lifecycle is the big CastSpell gap

Recording treats mana activation as a full lifecycle: separate instance ID for the mana ability, `AbilityInstanceCreated`/`AbilityInstanceDeleted`, `UserActionTaken` with `actionType=4`. Our engine collapses mana activation into the spell cast — no separate ability instance, no mana-specific `UserActionTaken`. Root cause of 4 of the 6 CastSpell annotation diffs.

### State reduction matters for CastSpell but not PlayLand

PlayLand diffs cleanly (3/3 annotations pass). CastSpell has scenery IDs (remaining hand cards, battlefield permanents not involved in the cast) that don't participate in the interaction. Stripping scenery from templates before diffing would reduce noise and make the real gaps clearer.

### Binder needs 1:many support

One engine instance ID (e.g. 122) can map to two template variables (`$var_2` for the spell and `$var_4` for the mana ability). Current binder dict overwrites — last binding wins. Need either: detect when engine collapses IDs the recording kept separate, or allow multiple vars per engine ID.

### Resolution annotations are deferred past GroupReq prompts

The auto-pass loop sends `GroupReq` directly without first emitting a state diff with `ResolutionStart`/`ResolutionComplete` annotations. They appear only after the player responds to the `GroupReq`. This matches real server behavior — not a bug, just something the pipeline has to account for when building segment boundaries around prompts.

### Scry annotation shape mismatch (fixed)

Real server: `affectedIds=[cardInstanceId]`, detail keys `topIds`/`bottomIds`. Our engine was: `affectedIds=[seatId]`, `topCount`/`bottomCount`. Fixed in `ScryETBFlowTest` — engine now matches recording shape.

### Zone IDs are 18–38, card instance IDs start at ~100+

Templatizer uses zone ID collection from the recording to exclude them from variable replacement. Zone IDs are structural constants (Battlefield=28, Hand=31, Stack=27, etc.). Card instance IDs always start well above this range, so the threshold is safe.

### `--tests` Gradle filter doesn't work with Kotest

Use `just test-one ClassName` (sets `kotest.filter.specs`) or tag-based tasks (`testGate`, `testIntegration`).

### CastSpell conformance gaps

Found by running the pipeline end-to-end against the recording's CastSpell segment (Wall of Runes, 1U creature).

| # | Gap | Recording | Engine |
|---|---|---|---|
| 1 | Mana ability instance ID | Separate ID for mana ability, distinct from spell | Same ID for both |
| 2 | AbilityInstanceCreated affectorId | Points to mana source (Island) | Missing (0) |
| 3 | Annotation order | Tap→UserAction(mana)→ManaPaid→Delete→UserAction(cast) | ManaPaid→Delete→UserAction(cast)→Tap |
| 4 | Missing UserActionTaken | Two: actionType=4 (mana) + actionType=1 (cast) | One: actionType=1 only |
| 5 | ManaPaid details | `id=3, color=2` (blue) | `id=0, color=""` (empty) |
| 6 | TappedUntappedPermanent affectorId | Mana ability instance | The Island itself |

### PlayLand conformance gaps

| # | Gap | Recording | Engine |
|---|---|---|---|
| 1 | ColorProduction persistent annotation | Present, `colors:2` for Island | Not emitted |

## Architecture

### Files

| File | Role |
|---|---|
| `tools/tape/segments.py` | Python CLI: `list`, `extract`, `template`, `puzzle`, `diff` (via `just tape segment`) |
| `matchdoor/.../conformance/AnnotationSerializer.kt` | Proto → recording-compatible JSON serializer |
| `matchdoor/.../conformance/ConformancePipelineTest.kt` | Engine run tests, dumps to `build/conformance/` |
| `matchdoor/.../conformance/MatchFlowHarness.kt` | Test harness: puzzle load, action execution, message capture |

### Data flow

```
Recording (md-frames.jsonl)
  │
  ├─ md-segments.py list        → category index
  ├─ md-segments.py template    → templatized JSON ($var_N slots)
  ├─ md-segments.py puzzle      → .pzl file (same cards as recording)
  │
Engine (ConformancePipelineTest)
  │
  ├─ Load puzzle, perform action
  ├─ AnnotationSerializer → recording-compatible JSON
  └─ Write to build/conformance/*.json
  │
Diff (md-segments.py diff)
  │
  ├─ Bind engine IDs to template $var_N by grpId+zone+annotation type
  ├─ Hydrate template with bound IDs
  └─ Field-by-field comparison → PASS/FAIL + gap report
```

## What the Agent Does vs What's Scripted

The pipeline has two kinds of work: mechanical (scriptable) and interpretive (agent).

**Scriptable (tools):**
- Decode recordings → structured messages
- Extract segment by index range → message list
- Scan messages for instance IDs → symbol table
- Replace IDs with slots → template
- Resolve grpId → card name
- Load puzzle, run engine, capture output
- Match grpId+zone → binding table
- Hydrate template, field-by-field diff
- Compute active set from boundary snapshots

**Agent work (synthesis):**
- Pick which recording segments are interesting (new mechanics, known failures, edge cases)
- Choose segment boundaries (where does this interaction really start/end?)
- Name template slots meaningfully ($BLOCKER vs $CREATURE_2)
- Write puzzle metadata (which phase, whose turn, life totals)
- Interpret diff results (is this divergence a bug or acceptable engine difference?)
- Decide what to investigate next based on diff patterns
- Connect annotation mismatches to code changes needed

## Worked Example: Cast Creature with ETB Scry

Source: `recordings/2026-03-08_19-44-CHALLENGE-STARTER-SEAT1`, indices 17–29.

### What happened

Turn 1, Main Phase 1. Player has 7 cards in hand including an Island and a 0/4 Wall creature (grpId 75478) with "When this creature enters the battlefield, scry 1." Board is empty.

1. Player plays Island (hand → battlefield)
2. Player casts Wall (hand → stack, tapping Island for mana)
3. Both players pass priority
4. Wall resolves (stack → battlefield), ETB trigger goes on stack
5. Both players pass priority
6. Trigger resolves — server sends GroupReq for scry (top or bottom?)
7. Player chooses bottom
8. Trigger completes, ability deleted from stack

### The segment (13 messages)

```
idx 17: ActionsAvailableReq gs=6          — action menu: Cast Wall, Cast others, Play Island, Pass
idx 18: PerformActionResp (C→S)           — Play Island (grpId 75554)
idx 19: GSM gs=7 Diff SendAndRecord       — Island enters battlefield
         annotations: ObjectIdChanged(161→282), ZoneTransfer(31→28, PlayLand),
                      UserActionTaken(seat 1, actionType 3)
         persistentAnnotations: EnteredZoneThisTurn(zone 28, [282]),
                                ColorProduction(282, colors:2)
idx 20: ActionsAvailableReq gs=7          — updated: Cast Wall, ActivateMana(Island), Pass
idx 21: PerformActionResp (C→S)           — Cast Wall (grpId 75478)
idx 22: GSM gs=8 Diff SendHiFi            — Wall on stack, Island tapped
         annotations: ObjectIdChanged(162→283), ZoneTransfer(31→27, CastSpell),
                      AbilityInstanceCreated(284, mana ability), TappedUntappedPermanent(282),
                      UserActionTaken(seat 1, actionType 4/1002), ManaPaid(282→283, color:2),
                      AbilityInstanceDeleted(284), UserActionTaken(seat 1, actionType 1)
idx 23: GSM gs=9 Diff SendHiFi            — priority passes to opponent
idx 24: GSM gs=10 Diff SendHiFi           — Wall resolves, ETB trigger created
         annotations: ResolutionStart(283, grpid 75478), ResolutionComplete(283, grpid 75478),
                      ZoneTransfer(27→28, Resolve), AbilityInstanceCreated(285, trigger)
         persistentAnnotations: EnteredZoneThisTurn(zone 28, [283,282]),
                                TriggeringObject(285→283, source_zone 28)
idx 25: GSM gs=11 Diff SendHiFi           — priority passes to opponent
idx 26: GSM gs=12 Diff Send               — trigger resolution starts (scry)
         annotations: ResolutionStart(285, grpid 176406),
                      MultistepEffectStarted(285, SubCategory:15)
         objects: top card of library revealed (instanceId 168, grpId 75554 = Island)
idx 27: GroupReq gs=12                     — scry choice: [168] → top or bottom of library
         context: Scry, sourceId: 285, groupSpecs: [{top,1},{bottom,1}]
idx 28: GroupResp (C→S)                    — player puts card on bottom
idx 29: GSM gs=13 Diff SendHiFi           — scry completes, trigger cleaned up
         annotations: Scry(285→168, bottomIds:168), MultistepEffectComplete(285, SubCategory:15),
                      ResolutionComplete(285, grpid 176406), AbilityInstanceDeleted(283→285)
         library updated: 168 moved to bottom
```

### Template (what it would look like)

Symbols from this segment:
```
$ISLAND      = instanceId 161/282  (grpId 75554, Island — gets ObjectIdChanged to 282)
$WALL        = instanceId 162/283  (grpId 75478, 0/4 Wall — gets ObjectIdChanged to 283)
$MANA_ABILITY = instanceId 284    (transient — created and deleted during mana payment)
$ETB_TRIGGER  = instanceId 285    (grpId 176406 — ability instance, scry trigger)
$SCRY_CARD    = instanceId 168    (grpId 75554, Island — top card of library)
$HAND        = zoneId 31
$BATTLEFIELD = zoneId 28
$STACK       = zoneId 27
$LIBRARY     = zoneId 32
$LIMBO       = zoneId 30
```

Template for GSM gs=10 (resolution + ETB trigger creation):
```json
{
  "annotations": [
    {"types": ["ResolutionStart"], "affectorId": "$WALL", "affectedIds": ["$WALL"],
     "details": {"grpid": 75478}},
    {"types": ["ResolutionComplete"], "affectorId": "$WALL", "affectedIds": ["$WALL"],
     "details": {"grpid": 75478}},
    {"types": ["ZoneTransfer"], "affectorId": 1, "affectedIds": ["$WALL"],
     "details": {"zone_src": "$STACK", "zone_dest": "$BATTLEFIELD", "category": "Resolve"}},
    {"types": ["AbilityInstanceCreated"], "affectorId": "$WALL", "affectedIds": ["$ETB_TRIGGER"],
     "details": {"source_zone": "$BATTLEFIELD"}}
  ],
  "persistentAnnotations": [
    {"types": ["EnteredZoneThisTurn"], "affectorId": "$BATTLEFIELD",
     "affectedIds": ["$WALL", "$ISLAND"]},
    {"types": ["TriggeringObject"], "affectorId": "$ETB_TRIGGER", "affectedIds": ["$WALL"],
     "details": {"source_zone": "$BATTLEFIELD"}}
  ]
}
```

grpId 75478 stays literal — it's a card identity, not an instance. Zone IDs become slots because Forge may assign different zone numbers. Annotation IDs (80, 81, ...) are stripped — they're sequence counters, not semantic.

### Puzzle

```
[metadata]
Name:Cast Wall with ETB Scry
URL:recording/2026-03-08_19-44-CHALLENGE-STARTER-SEAT1/idx-17-29
Goal:AI wins
Turns:T1/0
Difficulty:Easy

[state]
ActivePlayer=Human
ActivePhase=Main1
HumanLife=20
AILife=20
humanhand=Island;Wall of Mist;Sworn Guardian;Windstorm Drake;Waterknot;Island;Island
humanlibrary=Island;Island;Island;Island;Island;Island;Island;Island
```

(Wall of Mist is a placeholder — need the actual Forge card name for grpId 75478. The point: same cards as recording.)

### What this tests

- ObjectIdChanged annotation: hand ID → battlefield ID mapping
- ZoneTransfer categories: PlayLand, CastSpell, Resolve
- Mana payment sequence: AbilityInstanceCreated → TappedUntappedPermanent → ManaPaid → AbilityInstanceDeleted
- ETB trigger lifecycle: AbilityInstanceCreated on resolution → TriggeringObject persistent annotation → GroupReq for scry → Scry annotation with topIds/bottomIds → MultistepEffectStarted/Complete → AbilityInstanceDeleted
- Persistent annotation management: EnteredZoneThisTurn accumulates objects, ColorProduction on lands, TriggeringObject created/deleted with trigger lifecycle
- GroupReq shape: context, sourceId, groupSpecs with zoneType/subZoneType, instanceIds

Each of these is a specific conformance property. If the template hydrates and matches, all of them are correct. If it diverges, the diff tells you exactly which property failed.
