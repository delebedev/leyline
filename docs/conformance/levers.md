# Conformance Pipeline: 10x Levers

Six structural gaps that prevent the conformance pipeline from becoming self-sustaining. Not tooling polish — architectural leverage points where fixing one unlocks compound gains.

Current state: the pipeline compares annotation shapes in single GSM frames, manually, across two languages, for seat 1 only, with no regression detection. These levers transform it into an automated regression suite covering full message sequences for both seats.

---

## Status (2026-03-22)

**Done:**
- **#4 partially** — structural binder (type-based matching, not positional zip) + ObjectIdChanged anchoring.
- **#6 partially** — golden baselines with regression detection. `just conform` / `just conform-golden` pipeline works. Not yet wired into `test-gate` CI.
- **QueuedGSM split** — CastSpell GSMs now emit the real server's QueuedGSM triplet (not a lever, but enables conformance of message types).

**In progress (2026-03-22):**
- **#2 closed-loop tests** — `RecordingFrameLoader` + `ProtoDiffer` provide single-runtime Kotlin conformance. `just conform-proto` decodes raw proto losslessly. Branch: `feat/conformance-observatory`.
- **#6 regression signal** — Conformance Observatory: `SegmentVarianceProfiler` (field variance from recordings), `RelationshipValidator` (72 structural invariants — 40 hand-written + 32 auto-derived). `just segment-variance`, `just segment-relationships` CLIs. Branch: `feat/conformance-observatory`.
- **See [`how-we-conform.md`](how-we-conform.md)** for the current tooling reference.

**Findings from #119 (Treasure sacrifice conformance):**
- Single-frame comparison (#1) is the binding constraint. Our engine bundles CastSpell + mana brackets + sacrifice + phase transitions + combat into one GSM. The recording splits across 3+ GSMs. `extractByCategory("Sacrifice")` grabs the whole GSM, producing cross-GSM noise that neither #4 nor #5 can fix.
- Recording-matched puzzles (#5) don't help without #1 — even identical cards produce cross-GSM diffs because the engine's GSM boundaries differ from the recording's.
- The structural binder (#4) eliminates type_mismatch noise but can't help when annotations are genuinely in different GSMs.
- **Conclusion: #1 (sequence comparison) is the critical path.** Without it, conformance diffs are dominated by GSM-boundary noise, not annotation-content gaps.

---

## 1. Sequence comparison (unit of comparison is wrong)

**What:** The pipeline compares ONE GSM frame — the frame containing the ZoneTransfer annotation for a given category. Real conformance is a *message sequence*: `ActionsAvailableReq → cast → GSM(stack) → GSM(priority flip) → GSM(resolve)`. Single-frame comparison catches annotation bugs but is structurally blind to message-flow bugs (#92, #93).

**What it misses:**
- Wrong message at wrong time (spurious `ActionsAvailableReq` during auto-pass)
- Missing intermediate diffs (bare `turnInfo`-only GSMs for priority passing)
- Wrong `updateType` on non-ZoneTransfer messages (`SendHiFi` vs `SendAndRecord`)
- `pendingMessageCount` mismatches (client expecting follow-up that never arrives)
- Edictal passes that should/shouldn't exist

**How — engine side:** `MatchFlowHarness.messagesSince(snap)` already captures the full message sequence after an action. `ConformancePipelineTest` needs to serialize ALL messages, not just extract-by-category. Output: ordered `List<GREToClientMessage>` as JSON array.

**How — recording side:** `scry-ts sequences` extracts bracketing patterns. Needs `find_interaction_segment()` that returns the full message window from `ActionsAvailableReq` to next `ActionsAvailableReq` (stable-state boundaries). Saved games in `~/.scry/games/` contain the full ordered stream.

**How — diff side:** Replace positional zip with sequence alignment. Pair recording messages to engine messages by structure (GRE type + annotation fingerprint), then diff each pair. Proper sequence diff (LCS/edit-distance on message fingerprints), not positional zip. Extra/missing messages surface as insertions/deletions in the alignment. `scry-ts sequences --diff leyline real` already does side-by-side source comparison — extend to per-message structural diff.

**Unlocks:** Catches the entire class of bugs where the right annotations are sent but in the wrong message, at the wrong time, or with wrong surrounding messages. This is where #92 and #93 live — and likely many undiscovered bugs.

**2026-03-21 note:** This is now the #1 priority. The QueuedGSM split (CastSpell triplet) makes GSM boundaries conformant, but the single-frame comparison can't verify it. The Sacrifice conformance diffs (30) are almost entirely GSM-boundary noise. Sequence comparison would match our QueuedGSM against the recording's QueuedGSM, our main GSM against the recording's main GSM, and eliminate the cross-GSM false diffs.

---

## 2. Closed-loop tests (no single entry point)

**What:** The workflow requires manual steps: run `ConformancePipelineTest` → engine JSON, then compare against recording data. No single entry point. No CI gate. A code change can silently break PlayLand conformance and nothing detects it.

**Why it matters:** The binding/diff logic is mechanical: match by type, extract IDs, build a map, compare field-by-field. All in Kotlin now (Python tooling removed). Put next to `AnnotationSerializer.kt` in the conformance package.

**How — load recordings in JVM tests:** `RecordingDecoder` (in `tooling/`) already decodes `md-frames.jsonl` into structured proto messages. The conformance test loads the recording segment, runs the engine with `MatchFlowHarness`, binds IDs, diffs, and asserts — all in one test method. No shell scripts, no file copying.

**How — make it a regression gate:** Conformance tests run under `testGate` tag. `just test-gate` catches conformance regressions before commit. Golden expected-diff results checked into repo as JSON — test fails if divergences increase.

**Unlocks:** Conformance becomes a regression suite, not an investigation tool. Every `testGate` run automatically verifies that PlayLand, CastSpell, Resolve, etc. still conform to recordings. Code changes that introduce new divergences fail the build.

---

## 3. Seat 2 conformance (AI messages are a blind spot)

**What:** `GamePlayback` generates AI-turn messages through completely different code paths than human actions. `remoteActionDiff()` vs `postAction()` — different `updateType` logic, different annotation embedding, no `pendingMessageCount`. No conformance test exercises these paths. Recordings contain full AI-turn message sequences (the `SendHiFi` frames with `activePlayer=2`), but nobody compares against them.

**What it misses:**
- Wrong `updateType` on AI action diffs
- Missing or extra annotations in `remoteActionDiff` vs what the real server sends
- Wrong `turnInfo` during AI turns (could explain #93's wrong button labels)
- Phase transition messages during AI turn (BeginCombat, DeclareAttackers, etc.)

**How — harness extension:** `MatchFlowHarness` already captures all messages including AI-generated ones. `messagesSince(snap)` after `passPriority()` or `passUntilTurn()` returns AI diffs queued by `GamePlayback`. The messages are already there — just need tests that assert on them.

**How — recording extraction:** `scry-ts gsm list --has <type>` filters by annotation type; seat filtering via `turnInfo.activePlayer` on saved games. Extract AI-turn segments by filtering for `activePlayer=2`. Compare against engine's AI-turn output.

**How — scripted AI:** Use `ScriptedPlayerController` (or the existing `PuzzleAI`) to make AI behavior deterministic. Set up a board where AI will attack/cast predictably, capture the resulting message sequence, compare against recording of same board state.

**Unlocks:** Full protocol conformance for both players. Currently, AI-turn bugs only surface when a human plays through and notices wrong visuals. This makes them testable.

---

## 4. Structural binding (binder assumes positional alignment)

**What:** The binder works by positional pairing (`zip`). If annotation ordering differs, counts differ, or repeated types exist, it silently mis-binds or skips. Works for PlayLand (3 annotations, same order). Breaks for CastSpell (6 vs 8 annotations, mana ability lifecycle), combat (multiple creatures → multiple identical-type annotations), triggered ability chains (variable-length sequences).

**What breaks:**
- CastSpell: recording has 8 annotations (including mana ability lifecycle), engine has 6. Zip truncates at 6, losing 2 recording annotations entirely.
- Combat: multiple `DamageDealt` annotations (one per creature). Positional zip pairs creature A's damage with creature B's damage if ordering differs.
- Triggered abilities: recording may have `AbilityInstanceCreated` for trigger + mana ability. Engine collapses them. 1:many mapping can't be expressed as a dict.

**How — composite key matching:** Match annotations by `(annotationType, grpId-in-details, zone_src/zone_dest-for-ZoneTransfer)` — a structural key identifying WHAT happened, not WHERE it appears. Multiple annotations of the same type disambiguate by `affectedIds` grpId or detail values.

**How — anchor on ObjectIdChanged:** `ObjectIdChanged` annotations provide the canonical ID mapping: `orig_id → new_id`. These are unambiguous (one per moved card). Build the binding table from ObjectIdChanged first, then propagate to other annotations. The templatizer already orders variables by ObjectIdChanged — make the binder use the same anchor.

**How — handle 1:many:** When the recording has a separate mana ability instance ID that the engine collapses into the spell ID, detect the collision (two template vars binding to one engine ID) and report it as a known divergence rather than a binding failure.

**Unlocks:** The pipeline works for complex mechanics (combat, ability chains, multi-spell turns) without false diffs from ordering differences. Required before sequence comparison (#1) works on anything beyond simple single-card interactions.

**2026-03-21 note:** Partially done. Structural binder (type-based matching) + ObjectIdChanged anchoring implemented. Anchoring didn't reduce current diff counts because the remaining diffs are cross-GSM (different GSM boundaries), not same-type confusion. Will pay off when sequence comparison (#1) brings same-type annotations from different GSMs into the same comparison window.

---

## 5. Lossless object tracking (puzzle generation is lossy)

**What:** `_resolve_hand_cards()` tries to find card names for hand IDs by scanning backwards for a Full GSM. Falls back to `'Island'` for unresolvable IDs. Hand cards dealt at mulligan and never moved may only appear in the initial Full GSM, which could be dozens of frames back. Result: puzzle generation fails silently for segments deep in a recording, producing wrong card setups.

**What breaks:** Any segment where the hand contains cards not visible in a nearby Full GSM. This is most segments past turn 2 — the further into the game, the staler the last Full GSM becomes. Currently limits pipeline to early-game segments in recordings where the initial Full GSM is close.

**How — running object accumulator:** `scry-ts board` already accumulates board state across GSMs. Extend to track `{instanceId: {grpId, name, zone, cardTypes, ...}}` across ALL frames in a saved game. On Full GSM: snapshot all objects. On Diff GSM: apply zone changes, apply ObjectIdChanged, track new objects. Same thing `ClientAccumulator` does engine-side.

**How — integrate with puzzle generation:** `cmd_puzzle` calls `tracker.objects_at(frame_index)` instead of `_resolve_hand_cards()`. Returns complete board state at any point in the recording. Hand, battlefield, graveyard, exile — all zones, all cards, all resolved.

**How — card name resolution:** Combine with `just card <grpId>` lookups (or a prebuilt grpId→name table from the Arena card DB). The tracker provides grpIds, the card DB provides Forge-compatible names for puzzle files.

**Unlocks:** Any segment in any recording becomes puzzle-generatable automatically. Currently, puzzle generation is reliable only for early-game segments. This makes the full recording catalogue usable as conformance test sources.

**2026-03-21 note:** Attempted for Treasure sacrifice — hand-wrote a recording-matched puzzle. Didn't reduce diffs because the cross-GSM boundary problem dominates. The puzzle itself works fine; the comparison pipeline can't leverage it without #1 (sequence comparison). Still valuable for scaling once #1 is in place.

---

## 6. Regression signal (no score, no trend, no CI gate)

**What:** No way to answer "how conformant are we?" or "did this commit make conformance better or worse?" The annotation-variance tool reports per-type key coverage. The conformance pipeline reports per-segment PASS/FAIL. Neither produces a number tracked over time. No CI gate prevents conformance regressions.

**How — golden conformance diffs:** For each tested interaction type (PlayLand, CastSpell, Resolve, etc.), store the expected diff result as a JSON file in the repo. Fields: which annotations matched, which diverged, what the specific gaps are. Test asserts that actual diff matches or improves on the golden. If a code change introduces new divergences, the golden file comparison fails.

**How — conformance index:** `(matched_fields / total_fields)` per interaction type across a stable set of recording segments. Composite score across all types. Stored in `build/conformance/index.json`, printed by CI. Goes up = good. Goes down = investigate.

**How — wire annotation-variance into tests:** `AnnotationVariance` knows which types have mismatched keys. When a conformance test runs for CastSpell and the recording contains `ManaPaid`, check whether our `ManaPaid` builder produces the right detail keys. Turns the variance report from a standalone analysis into a conformance oracle that fails tests.

**Unlocks:** Conformance improvements stick. Without regression detection, you fix a bug and a different change quietly re-breaks it. With golden diffs and a CI gate, the ratchet only moves forward.

---

## Compound effects

| Combination | What it enables |
|---|---|
| 1 + 2 (sequence + closed loop) | Automated regression suite catching message-flow bugs. **The highest-leverage pair.** |
| 2 + 6 (closed loop + regression) | CI gate preventing conformance regressions. Improvements stick. |
| 1 + 3 (sequence + seat 2) | Full protocol conformance for both players across all message types. |
| 4 + 5 (robust binder + lossless puzzles) | Any recording segment becomes a reliable conformance test. Scaling. |
| 1 + 4 (sequence + structural binding) | Sequence comparison works for complex multi-annotation mechanics. |
| All six | Pipeline that automatically converts any proxy recording into regression tests covering full message sequences for both seats, with structural binding, CI gating, and trend tracking. |

## Execution order

1. **Close the loop (#2)** — port binding/diff to Kotlin, self-contained tests. Fastest to implement (all pieces exist). Immediately enables CI regression.
2. **Structural binding (#4)** — prerequisite for sequence comparison on complex mechanics. Independent of #2, can parallelize.
3. **Sequence comparison (#1)** — extend closed-loop tests to compare message sequences. Catches #92/#93 class. Requires #4 for robustness.
4. **Lossless puzzles (#5)** — object tracker for recordings. Unblocks scaling to more segments. Independent, can happen anytime.
5. **Seat 2 (#3)** — add AI-turn tests. Infrastructure from #1+#2 makes this incremental.
6. **Regression signal (#6)** — golden diffs, CI gate. Meaningful once pipeline runs reliably from #1+#2.
