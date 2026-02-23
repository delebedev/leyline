# Recording Triage Runbook

Reproducible workflow: new recordings arrive → catalogue what's new → scope implementation.

Run after every batch of play sessions. Produces an inventory of new mechanics, message types, and annotation gaps with implementation scope.

## Prerequisites

```bash
cd forge-nexus
just dev-build   # Kotlin-only, ~3-5s
```

## 1. Discover Sessions

```bash
just rec-list
```

Output: session id, mode (engine/proxy/recording), file count, path.

Proxy sessions (real Arena traffic) are the conformance ground truth.
Engine sessions are our output — compare against proxy for gaps.

Check for analysis files:

```bash
ls recordings/*/analysis.json | wc -l
just rec-analyze-all   # generate missing analysis.json files
```

## 2. Summarize Each Session

For every session with >50 files:

```bash
just rec-summary <path>
```

Note: seats, turn count, message count, action count, top cards, actions by actor.

## 3. Mechanics Manifest

```bash
just rec-mechanics
```

Cross-session mechanic types. Compare against `recordings/manifest.json` — anything in `rec-mechanics` NOT in the manifest is **new**.

Update manifest after triage:

```bash
just rec-mechanics | python3 -c "import sys,json; print(json.dumps(sorted(set(l.strip().split()[-1] for l in sys.stdin if l.strip() and not l.startswith('Cross')))))" > recordings/manifest.json
```

## 4. Action Timeline

For each interesting session:

```bash
just rec-actions <path> 100
```

Scan for action types not seen in previous sessions:
- `Countered`, `Return`, `Exile`, `Put`, `Discard`, `SBA_*`, `Destroy` — all have distinct protocol implications.

## 5. Analysis Deep-Dive

Read auto-generated analysis:

```bash
cat recordings/<session>/analysis.json | python3 -c "
import json,sys
d = json.load(sys.stdin)
for k in ['mechanicsExercised','invariantViolations','interestingMoments','termination','winner','turns']:
    if k in d:
        print(f'=== {k} ===')
        print(json.dumps(d[k], indent=2))
        print()
"
```

Key signals:
- `interestingMoments` — first occurrence of a mechanic across sessions
- `invariantViolations` — protocol bugs (annotation_seq, gsid_monotonicity, pending_count)
- `mechanicsExercised` — per-session mechanic breakdown

## 6. GRE Type Coverage (Proxy Only)

Decode proxy recordings to JSONL:

```bash
just proto-decode-recording <capture/payloads> /tmp/decoded.jsonl
```

Extract GRE type distribution:

```bash
python3 -c "
import json
events = [json.loads(l) for l in open('/tmp/decoded.jsonl')]
types = {}
for e in events:
    types[e['greType']] = types.get(e['greType'], 0) + 1
for k, v in sorted(types.items(), key=lambda x: -x[1]):
    print(f'  {v:4d}  {k}')
"
```

Compare against `MatchHandler.kt` dispatch table. Any GRE type in the proxy not dispatched in `MatchHandler` = gap.

Known handled: `ConnectReq`, `ChooseStartingPlayerResp`, `MulliganResp`, `SetSettingsReq`, `ConcedeReq`, `PerformActionResp`, `DeclareAttackersResp`, `SubmitAttackersReq`, `DeclareBlockersResp`, `SubmitBlockersReq`, `SelectTargetsResp`, `CheckpointReq`.

## 7. Classify New Interactions

For each new mechanic or GRE type, classify:

| Classification | Meaning | Action |
|----------------|---------|--------|
| **Conformance test** | Engine handles it; need integration test verifying proto output | Add test using `ConformanceTestBase` / `MatchFlowHarness` |
| **Handler gap** | New client→server or server→client message type | Implement handler + bundle method, integration test |
| **Annotation gap** | Engine does the right thing but missing Arena annotation | Add AnnotationBuilder factory + StateMapper wiring, test |
| **Engine gap** | Forge engine doesn't model the mechanic at all | Out of scope for nexus; file upstream issue |

### Test strategy: conformance infra over goldens

The primary verification path is **integration tests** (`ConformanceTestBase` + `MatchFlowHarness`), not golden files. These boot a real Forge engine, play scripted actions, and assert proto output — fully self-contained, run in CI, no recording files needed.

Proxy recordings serve as **reference** to understand the real server's message shape (annotation types, updateType, message count, field presence). Read them with `just proto-inspect` / `just rec-actions` to learn the shape, then encode that knowledge into an integration test.

Use `WireShapeTest` + golden JSON only for recording-group tests (require capture files, don't run in CI). These are a secondary validation layer.

## 8. Implementation Reference

Read these **before** coding. They compress thousands of LOC into patterns and touch points.

| Working on | Read first |
|---|---|
| New annotation type | `docs/rosetta.md` (annotation type table + Forge event wiring status) |
| New zone transition / category | `docs/rosetta.md` (zone transfer categories table) |
| New client action handler | `CLAUDE.md` cookbook "Adding a new client action handler" |
| New annotation builder | `CLAUDE.md` cookbook "Adding a new annotation type" |
| New zone transition category | `CLAUDE.md` cookbook "Adding a new zone transition category" |
| Wire format / message shape | `docs/wire-format.md`, `docs/match-sequence.md` |
| Combat protocol | `docs/combat-protocol.md` |
| Priority/prompt flow | `docs/priority-loop.md` |
| Diff semantics | `docs/diff-semantics.md` |
| Proto field meanings | `recording-analysis-runbook.md` §4 Pattern Catalog, §5 updateType Semantics |
| Debugging test timeout | `CLAUDE.md` cookbook "Debugging a test timeout" |
| Debugging conformance failure | `docs/conformance-debugging.md` |

### Key source files by role

| Role | File | When to read |
|------|------|-------------|
| Annotation factories | `AnnotationBuilder.kt` | Adding/modifying annotation output |
| Event → annotation wiring | `GameEventCollector.kt`, `NexusGameEvent.kt` | New Forge event subscription |
| Diff assembly pipeline | `StateMapper.kt` | Any annotation/zone/category change |
| GRE message construction | `BundleBuilder.kt` | New message type or bundle shape |
| Client message dispatch | `MatchHandler.kt` | New inbound message type |
| Session handlers | `MatchSession.kt` | New action/prompt handler |
| Zone transitions | `ZoneMapper.kt`, `TransferCategory.kt` | Zone move behavior |
| Test harness | `ConformanceTestBase.kt`, `MatchFlowHarness.kt` | Writing new integration tests |
| Card injection for tests | `TestCardInjector.kt`, `TestCardRegistry.kt` | Test setup with specific cards |

### Finding proxy recording data for reference

Use proxy recordings to understand the real server shape, then encode into tests:

```bash
# Inspect a specific payload for field-level detail
just proto-inspect <capture/payloads/S-C_MATCH_DATA_NNN.bin>

# Trace an instanceId across all payloads (follows renames)
just proto-trace <id> <capture/payloads>

# Action timeline to find the right message index
just rec-actions <path> 100

# Decode all to JSONL for scripted analysis
just proto-decode-recording <capture/payloads> /tmp/decoded.jsonl
```

## 9. Scope Template

For each new interaction, fill in:

```
## <Interaction Name>

**Source recording:** <session, turn, action index, grpId>
**Classification:** conformance test / handler gap / annotation gap
**Mechanic type:** <from rec-mechanics>
**GRE types involved:** <list>
**Annotation types needed:** <list with type numbers, see rosetta.md>

### What exists
- <what the engine already does>
- <what tests already cover>

### What's missing
- <specific gaps>

### Touch points (read first → then implement)
- Docs: <which docs to read>
- Files: <which files to change, in order>

### Test approach
- <integration test using ConformanceTestBase/MatchFlowHarness>
- <what to assert: annotation types, categories, zone transitions, message shape>

### Effort: S / M / L
### Dependencies: <other interactions that must land first>
```

---

## Recording Catalogue (as of 2026-02-22)

### Sessions

| # | Session | Mode | Files | Turns | Msgs | Deck / Theme |
|---|---------|------|-------|-------|------|--------------|
| 1 | 2026-02-15 | proxy | — | — | 757 | Starter (G/W). Creature cast, combat, planeswalker. |
| 2 | 2026-02-15 | proxy | — | — | 396 | Mono-B. Kill spells, discard+reveal, targeted spells. |
| 3 | 2026-02-15 | proxy | — | — | 257 | Simple creatures. Combat w/ and w/o blockers. |
| 4 | `22-31-58` | proxy | 358 | 16 | 1115 | Targeted removal, Exile, SBA_ZeroToughness, Return, Put. |
| 5 | `18-45-57-game2` | proxy | 408 | 13 | 1104 | Discard, Return, Exile, SBA_Damage, PayCostsReq, SelectNReq. |
| 6 | `18-45-57-game1` | proxy | 95 | 5 | 291 | SBA_Damage, SBA_Deathtouch, combat trades. |
| 7 | `22-24-00` | engine | 213 | 15 | 268 | Countered x3, Discard x4, AI win. |
| 8 | `15-12-59` | engine | 137 | 6 | 170 | Basic land+creature+combat. |
| 9 | `14-57-27` | engine | 86 | 4 | 105 | Basic land+creature. |

### Mechanic Coverage (13 types across all sessions)

```
countered  destroy  discard  draw  exile  land_play
put  return  sba_damage  sba_deathtouch  sba_zerotoughness
spell_cast  spell_resolve
```

### New vs Previous (recordings 1-3)

Previously covered: `land_play`, `spell_cast`, `spell_resolve`, `draw`, combat (simple + blockers), targeted spells, game end.

**First seen in recordings 4-9:**

| Mechanic | First seen | Notes |
|----------|------------|-------|
| `countered` | #7 (engine) | First counterspell — engine emits Countered category |
| `sba_zerotoughness` | #4 (proxy) | -X/-X kills creature at 0 toughness |
| `sba_damage` | #5, #6 (proxy) | Lethal damage state-based action |
| `sba_deathtouch` | #6 (proxy) | First deathtouch recording |
| `return` | #4, #5 (proxy) | Bounce — permanent returned to hand |
| `put` | #4 (proxy) | Card put directly onto battlefield (ETB/CIP) |
| `discard` | #5 (proxy), #7 (engine) | Spell-forced discard (not just hand-size cleanup) |
| `exile` | #4, #5 (proxy) | Targeted exile by spell effect |
| `destroy` | #4 (proxy) | Targeted destruction by spell |

### New GRE Message Types

| Message | Seen in | Engine status |
|---------|---------|---------------|
| `PayCostsReq` (type 36) | #5 (3x) | **NOT IMPLEMENTED** — no handler, no bundle method |
| `SelectNReq` (type 22) | #4 (1x), #5 (4x) | **NOT IMPLEMENTED** — no handler, no bundle method |
| `GroupReq` (type 11) | RecordingDecoderTest only | **NOT IMPLEMENTED** — needed for ETB trigger choices |

### New Annotation Types Needed

| Annotation | Type # | Needed for |
|------------|:------:|------------|
| RevealedCardCreated | 59 | Discard+reveal spell flow |
| RevealedCardDeleted | 60 | Paired with above |
| AttachmentCreated | 70 | Aura/Equipment protocol |
| PlayerSelectingTargets | — | Send updateType during targeting |
| PlayerSubmittedTargets | — | After target confirmed |
| LayeredEffectCreated | 18 | P/T modifiers, enchantment effects |
| LayeredEffectDestroyed | 19 | Cleanup of above |

---

## Scoped Implementation

### Test approach

All new mechanics should be verified via **integration tests** using `ConformanceTestBase` or `MatchFlowHarness`. These boot a real engine, play scripted actions, and assert proto output. No golden files needed; runs in CI.

Proxy recordings are reference material — use `just proto-inspect` and `just rec-actions` to understand the real server's message shape, then encode that knowledge into assertions.

Pattern: existing tests like `ZoneTransitionConformanceTest`, `CombatFlowTest`, `TargetingFlowTest` cover the model. New mechanics follow the same pattern: inject cards → script actions → capture proto → assert annotations, categories, zone transitions, message shape.

### Tier 1 — Integration tests for mechanics the engine already handles (S)

Engine produces correct game state; need conformance tests verifying the proto wire output.

#### 1.1 Countered Spell

- **Source:** Recording #7 (`22-24-00/engine`). T9 action #24 grp:91806 Countered (gsId=108), T11 #32 grp:91806 (gsId=133), T13 #34 (gsId=94). Three counter events in one game.
- **Classification:** conformance test
- **What exists:** `Countered` category emitted by `GameEventCollector`. `ZoneTransitionConformanceTest` covers Stack→GY zone move. Engine recording shows correct behavior.
- **What's missing:** No integration test asserting counter-specific proto output (annotations, category label, resolution flow when spell fizzles).
- **Read first:** `docs/rosetta.md` (ResolutionStart/Complete type numbers), `recording-analysis-runbook.md` §4 (resolution pattern)
- **Touch:** New test class `CounteredSpellTest.kt` using `ConformanceTestBase`. Inject two creatures + a counterspell. Script: cast creature → counter it → assert Stack→GY with `Countered` category, `ResolutionComplete` annotation, no BF placement.
- **Test assertions:** ZoneTransfer annotation with Countered category, spell goes Stack→GY (not Stack→BF), ResolutionStart + ResolutionComplete present, no zone transfer to BF.
- **Effort:** S
- **Note:** Engine recording #7 has gsId monotonicity violations around counter events — investigate whether `StateMapper` emits duplicate gsId when spell fizzles. May surface a bug.

#### 1.2 SBA Deaths (ZeroToughness / Damage / Deathtouch)

- **Source:** Recording #4 (`22-31-58`): action #49 SBA_ZeroToughness on grp:75485 at T10 (gsId=223). Recording #6 (`18-45-57-game1`): action #15 SBA_Damage grp:79618 at T3, #16 SBA_Deathtouch grp:75458 at T3 (combat trade — lethal damage + deathtouch simultaneous).
- **Classification:** conformance test
- **What exists:** Engine fires `GameEventCardDestroyed` → `CardDestroyed` NexusGameEvent → `Destroy` category. `ZoneTransitionConformanceTest` covers BF→GY. `CombatFlowTest` covers combat damage deaths.
- **What's missing:** No test for SBA-triggered death specifically (creature at 0 toughness after -X/-X effect; deathtouch-marked damage). Unknown if SBA death produces different annotations than spell-caused destroy.
- **Read first:** `docs/rosetta.md` (Destroy category, DamageDealt annotation type 3), `docs/combat-protocol.md`
- **Touch:** Add cases to `ZoneTransitionConformanceTest` or new `SBADeathTest.kt`. Inject creature + -X/-X spell for ZeroToughness. Inject deathtouch creature for Deathtouch. Assert BF→GY with correct category.
- **Test assertions:** ZoneTransfer BF→GY, correct SBA-specific category label (or generic Destroy — check proxy to determine), creature removed from BF zone in GSM.
- **Effort:** S — mostly test setup; engine already handles it.

#### 1.3 Bounce (Return to Hand)

- **Source:** Recording #4 (`22-31-58`): action #71 Return at T14, grp:75477 cast triggers bounce (gsId=332, msg=464). Recording #5 (`18-45-57-game2`): actions #24/#26 Return grp:92081/grp:92309 at T5 (gsId=128/128).
- **Classification:** conformance test
- **What exists:** `ZoneTransitionConformanceTest` covers BF→Hand with `Bounce` category.
- **What's missing:** No test through full spell flow (cast bounce spell → target → resolve → creature returns to hand). Current zone test is direct `GameAction`, not spell-driven.
- **Read first:** `docs/rosetta.md` (Bounce category), `recording-analysis-runbook.md` §4 (resolution pattern)
- **Touch:** Add test to `TargetingFlowTest` or new `BounceSpellTest.kt`. Inject creature + Unsummon-style spell. Script: cast spell → target creature → resolve → assert BF→Hand.
- **Test assertions:** ZoneTransfer BF→Hand with Bounce category, ObjectIdChanged, target creature back in hand zone.
- **Effort:** S

#### 1.4 Destroy (Targeted Removal)

- **Source:** Recording #4 (`22-31-58`): action #12 Destroy grp:75476 at T0 (gsId=54, part of grp:82141 targeted removal spell resolution). Also #68 Destroy grp:75485 at T14 (second removal spell).
- **Classification:** conformance test
- **What exists:** `Destroy` category, BF→GY zone transition in `ZoneTransitionConformanceTest`.
- **What's missing:** No end-to-end test of targeted-destroy spell (cast → select target → resolve → destroy). Current coverage is zone move only.
- **Read first:** `recording-analysis-runbook.md` §4 (targeted spell sequence, resolution pattern)
- **Touch:** Add test to `TargetingFlowTest` or new `DestroySpellTest.kt`. Inject creature + Murder-style spell. Script full targeting flow.
- **Test assertions:** SelectTargetsReq emitted, ZoneTransfer BF→GY with Destroy category, ResolutionStart/Complete, target creature in GY.
- **Effort:** S

#### 1.5 Exile (Targeted)

- **Source:** Recording #4 (`22-31-58`): action #19 Exile grp:75473 at T0 (gsId=87), action #39 Exile grp:75479 at T8 (grp:83781 exile spell, gsId=172). Recording #5 (`18-45-57-game2`): action #36 Exile grp:75485 at T7.
- **Classification:** conformance test
- **What exists:** `Exile` category, BF→Exile in `ZoneTransitionConformanceTest`.
- **What's missing:** No end-to-end targeted-exile spell test.
- **Read first:** `docs/rosetta.md` (Exile category, zone IDs — Exile zone)
- **Touch:** Similar to 1.4. Inject creature + Swords-to-Plowshares-style spell.
- **Test assertions:** ZoneTransfer BF→Exile with Exile category, creature in Exile zone.
- **Effort:** S

### Tier 2 — New handler plumbing (M)

#### 2.1 SelectNReq

- **Source:** Recording #4 (`22-31-58`): 1x SelectNReq. Recording #5 (`18-45-57-game2`): 4x SelectNReq — appears during discard spell resolution (choose cards to discard from revealed hand). Proxy game2 msg indices around gsId 225–233.
- **Classification:** handler gap
- **What exists:** Proto defined (type 22). `QueuedGameStateMessage` already built by `BundleBuilder.queuedGameState()`. `InteractivePromptBridge` handles existing prompt types.
- **What's missing:** No `SelectNReq` bundle method. No `MatchHandler` dispatch for `SelectNResp`. No `MatchSession.onSelectN()`. No prompt bridge for engine's "choose N" prompts.
- **Read first:** `CLAUDE.md` cookbook "Adding a new client action handler", `docs/priority-loop.md` (prompt bridge pattern), `recording-analysis-runbook.md` §4 "Discard + reveal spell" pattern
- **Touch:** `BundleBuilder.kt` (selectNBundle), `MatchHandler.kt` (dispatch SelectNResp), `MatchSession.kt` (onSelectN), `InteractivePromptBridge` or new bridge for "choose N" prompts.
- **Test:** Integration test: inject hand + discard spell → assert SelectNReq emitted with correct N and legal choices → submit response → assert discard zone transfer.
- **Effort:** M — follows `SelectTargetsReq` pattern closely.
- **Dependencies:** None.

#### 2.2 PayCostsReq

- **Source:** Recording #5 (`18-45-57-game2`): 3x PayCostsReq. Appears when mana payment requires explicit choice (multicolor lands, alternate costs). Specific msg indices not yet traced — search for type 36 in decoded JSONL.
- **Classification:** handler gap
- **What exists:** Proto defined (type 36).
- **What's missing:** No bundle, no handler, no dispatch.
- **Read first:** `docs/rosetta.md` (action types — ActivateMana, MakePayment), `docs/action-types.md`, proto `PayCostsReq` message definition
- **Touch:** `BundleBuilder.kt`, `MatchHandler.kt`, `MatchSession.kt`, mana payment bridge.
- **Test:** Integration test with multicolor spell or alternate-cost card.
- **Effort:** M-L — mana payment model differs significantly between Forge (auto-resolved) and Arena (explicit PayCostsReq).
- **Dependencies:** May interact with ActivateMana action type.

#### 2.3 Reveal Annotations (RevealedCardCreated/Deleted)

- **Source:** Documented in `recording-analysis-runbook.md` §4 "Discard + reveal spell" pattern. Types 59/60 in proto. Appear during Thoughtseize-style spells that reveal opponent's hand.
- **Classification:** annotation gap
- **What exists:** `QueuedGameStateMessage` built. Targeting flow implemented. `AnnotationBuilder` has factory pattern for new annotation types.
- **What's missing:** `AnnotationBuilder.revealedCardCreated()` / `.revealedCardDeleted()` factories. `GameEventCollector` subscription for Forge reveal events. `NexusGameEvent` sealed variant for reveal. `StateMapper` wiring.
- **Read first:** `CLAUDE.md` cookbook "Adding a new annotation type", `docs/rosetta.md` (annotation types 59/60), `recording-analysis-runbook.md` §4 (reveal pattern — shows the full message sequence)
- **Touch:** `AnnotationBuilder.kt` → `GameEventCollector.kt` → `NexusGameEvent.kt` → `StateMapper.kt`.
- **Test:** Integration test: cast reveal spell → assert RevealedCardCreated annotations with correct card count → choose discard → assert RevealedCardDeleted.
- **Effort:** M
- **Dependencies:** 2.1 (SelectNReq) for the full discard-after-reveal flow.

#### 2.4 Spell-Forced Discard (End-to-End)

- **Source:** Recording #5 (`18-45-57-game2`): action #50 Discard grp:92081 at T9. Full sequence: targeted spell → reveal hand → SelectNReq (choose card) → discard zone transfer. Also recording #7 (`22-24-00/engine`): actions #20/#28/#34/#38 Discard at T8/T11/T13/T15 (hand-size + engine-driven).
- **Classification:** integration (composition of 2.1 + 2.3)
- **What exists:** Targeting (working). Discard zone transition Hand→GY (in `ZoneTransitionConformanceTest`). `DiscardHandSizeTest` covers hand-size cleanup.
- **What's missing:** Integration of full spell-forced discard flow through targeting + reveal + selectN + discard.
- **Read first:** `recording-analysis-runbook.md` §4 full "Discard + reveal spell" pattern sequence
- **Touch:** New `SpellForcedDiscardTest.kt` composing the pieces.
- **Test:** End-to-end: cast Thoughtseize-style spell → target opponent → reveal → selectN → discard → assert Hand→GY with Discard category.
- **Effort:** M — composition of 2.1 + 2.3, plus test integration.
- **Dependencies:** 2.1 (SelectNReq) + 2.3 (Reveal annotations).

### Tier 3 — Larger scope (L)

#### 3.1 Activate Action Type

- **Source:** Falls back to PassPriority currently. `MatchSession.onPerformAction()` Activate branch logs and passes. Proxy recordings show Activate for non-mana abilities.
- **Classification:** handler gap (engine integration)
- **What exists:** Activate branch exists in `onPerformAction` — just doesn't wire through to engine.
- **What's missing:** `GameActionBridge` mapping for activated abilities. Forge engine `SpellAbility` selection.
- **Read first:** `CLAUDE.md` cookbook "Adding a new client action handler", `docs/action-types.md`, `docs/rosetta.md` (action types table — Activate = type 2)
- **Touch:** `MatchSession.onPerformAction()` Activate branch → `GameActionBridge` → Forge `SpellAbility` mapping.
- **Test:** Integration test with a card that has an activated ability (e.g., equipment, artifact, creature with tap ability).
- **Effort:** L — wide surface area; many ability subtypes.
- **Dependencies:** None direct.

#### 3.2 Attachment Protocol (Aura/Equipment)

- **Source:** `RecordingDecoderTest` (on-draw recording: Aura grp:75473 resolve at gsId 53 — AttachmentCreated + LayeredEffect annotations). Action trace: `src/test/resources/recordings/20260217-234330-on-draw-sparky-two-creatures-player-attacked/action-trace.md` line 66.
- **Classification:** annotation gap
- **What exists:** `ObjectMapper.applyCardFields()` sets parentId for visual attachment. No protocol annotations.
- **What's missing:** `AttachmentCreated` (type 70), `CreateAttachment`/`RemoveAttachment` (types 11/12), `LayeredEffectCreated`/`Destroyed` (types 18/19). `GameEventCardAttachment` exists in Forge but not wired to `NexusGameEvent`.
- **Read first:** `CLAUDE.md` cookbook "Adding a new annotation type", `docs/rosetta.md` (annotation types 11/12/18/19/70), on-draw action trace for reference shape
- **Touch:** `AnnotationBuilder.kt` (4+ new factories), `GameEventCollector.kt` (subscribe to `GameEventCardAttachment`), `NexusGameEvent.kt`, `StateMapper.kt`.
- **Test:** Integration test: inject creature + Aura → cast Aura targeting creature → assert AttachmentCreated annotation, parentId set on proto object.
- **Effort:** L — multiple annotation types, cross-reference with proxy recording for exact shape.
- **Dependencies:** Targeting flow (already working).

#### 3.3 ETB Triggers / GroupReq

- **Source:** `GroupReq` (type 11) in `RecordingDecoderTest`. Needed for ETB trigger choices (Scry, draw, modal ETBs).
- **Classification:** handler gap
- **What exists:** `AbilityInstanceCreated`/`Deleted` annotations. `EnteredZoneThisTurn` persistent. Stack abilities rendered via `ZoneMapper.addStackAbilities()`.
- **What's missing:** `GroupReq` message generation in `BundleBuilder`. `GroupResp` dispatch in `MatchHandler`. Engine trigger-choice bridge.
- **Read first:** `CLAUDE.md` cookbook "Adding a new client action handler", `docs/priority-loop.md`, proto GroupReq definition
- **Touch:** `BundleBuilder.kt` (groupReqBundle), `MatchHandler.kt` (dispatch GroupResp), new bridge for trigger choices.
- **Test:** Integration test with ETB-trigger creature (e.g., Scry 1 on ETB).
- **Effort:** L — Forge's trigger choice model differs from Arena's GroupReq model.
- **Dependencies:** None direct, but affects many cards.

---

## Invariant Violations Found

### Proxy recordings (real server)

| Violation | Sessions | Meaning |
|-----------|----------|---------|
| `annotation_seq` gaps | #4 (many) | Real server skips annotation IDs — annotation numbering is not strictly sequential. Our validator may be too strict. |
| `msgid_monotonicity` | #4, #7 | Message ID resets or goes backwards at session boundaries. Expected at game start. |

### Engine recordings

| Violation | Sessions | Meaning |
|-----------|----------|---------|
| `gsid_monotonicity` | #7 | gsId not monotonic — duplicate gsId emitted. Happens at `Countered` resolution. **Bug to investigate.** |
| `gsid_self_ref` | #7 | gameStateId == prevGameStateId. Same root cause as above. |
| `msgid_monotonicity` | #7 | Message ID regression around counter events. |
| `pending_count` | #5, #6 (proxy) | pendingMessageCount countdown off when SendAndRecord arrives. May be expected per-seat behavior (we capture one seat). |

**Action items:**
- #7 gsId violations around `Countered` — investigate `StateMapper` counter flow. Likely emits duplicate gsId when spell fizzles.
- Annotation seq gaps in proxy are likely by-design (server skips IDs consumed by other seats). Relax validator.
