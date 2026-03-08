# Variance Tooling Improvements

Findings from the annotation investigation sprint (2026-03-08). Five parallel conformance agents each investigated 3-6 annotation types from the `proto-annotation-variance` report, writing field notes to `docs/field-notes/`. Every agent independently hit tooling friction that slowed research. This doc captures the patterns for a scoping pass.

## Context

The `just proto-annotation-variance` tool (`tooling/src/main/kotlin/leyline/debug/AnnotationVariance.kt`) scans all proxy recordings, extracts annotations, groups by type, and compares detail keys against our `OUR_BUILDERS` map. It's the primary entry point for annotation conformance work.

The investigation also relied on `md-frames.jsonl` (raw Match Door frames), `just card`/`just ability` lookups, and `just proto-decode-recording`. Five agents ran in parallel on non-overlapping type batches.

---

## 1. Multi-type annotations hidden

**Problem:** The variance tool filters and reports by individual type name. Many annotations carry multiple types in a single proto message (e.g. `types: ["ModifiedType", "LayeredEffect"]`). The tool splits these into separate report entries, making co-typed annotations look like independent types with identical instance counts and sessions.

**Impact:** Three agents wasted time investigating "separate" types that turned out to be the same annotation. ModifiedCost, TextChange, and ModifiedName are a single 5-type annotation (`RemoveAbility + ModifiedCost + TextChange + ModifiedName + LayeredEffect`). Agent discovered this only after decoding raw frames.

**Examples from recordings:**
- `["ModifiedType", "LayeredEffect"]` — always co-typed, never standalone
- `["RemoveAbility", "ModifiedType", "LayeredEffect"]` — triple co-type
- `["ModifiedCost", "TextChange", "ModifiedName", "RemoveAbility", "LayeredEffect"]` — 5-type bundle (Room door-unlock effect)
- `["CopiedObject", "LayeredEffect"]` — clone/copy always pairs with LayeredEffect
- `["MiscContinuousEffect", "LayeredEffect"]` — MaxHandSize variant

**Suggested fix:** Show the full type list in the report when an annotation carries multiple types. Group co-typed annotations together or at minimum add a "co-types" column. When the tool detects identical instance counts + sessions across multiple types, flag them as likely co-typed.

---

## 2. grpId resolution is stale after zone transfers

**Problem:** The variance tool resolves `affectedIds` instanceId → grpId using the last-known mapping. After zone transfers (which reallocate instanceIds via ObjectIdChanged), the mapping goes stale. This produces wrong card names in the report.

**Impact:** Two agents got false-start card lookups. One agent saw grp:75557 (Swamp) for what was actually grp:92286 (Overlord of the Hauntwoods). Another saw grp:75554 (Island) for the ReplacementEffect on Multiversal Passage (grp:97998) — the grpId was the chosen land type, not the annotated card.

**Agent quote (batch 4):** "Had to grep raw frames to verify. This is a real tooling gap — the tool should resolve grpId at the time of the annotation, not the most recent time that instanceId was seen."

**Suggested fix:** Snapshot instanceId→grpId at each GSM. When resolving for a specific annotation, use the mapping from that GSM (or the most recent GSM before it), not the global last-seen.

---

## 3. affectorId missing from report

**Problem:** The report shows `affectedIds` with grpId resolution but doesn't include `affectorId`. For many annotation types, the affector (source of the effect) is more informative than the affected (target).

**Examples:**
- `LayeredEffectCreated`: affectorId = ability instance on stack that created the effect
- `ReplacementEffect`: affectorId uses mysterious 9000+ synthetic IDs (zone-change IDs)
- `TemporaryPermanent`: affectorId = TriggerHolder object (grp:5), not the source spell
- `Designation`: affectorId = the Room enchantment or ability that unlocked the door

**Agent quote (batch 1):** "Extend affectedIds resolution to also resolve affectorId where possible. The affector is often the more interesting field."

**Suggested fix:** Add `affectorId` to the report output, with grpId resolution when available.

---

## 4. Per-seat echo inflates instance counts

**Problem:** Every S→C message is sent to both seats (in 2-player games). The report counts each seat's copy as a separate instance. This means "4 instances, 1 session" usually means 2 real occurrences × 2 seats, not 4 distinct events.

**Impact:** Agents had to mentally halve instance counts. For types with few instances (CardRevealed: 1 instance = actually 1 seat copy of 1 event in a 1-seat recording), the count is accurate. But for most types, the inflation is 2×.

**Agent quote (batch 3):** "When instances/sessions ratio is suggestive of per-seat echo (4/1 = 2 real × 2 seats), call that out explicitly."

**Suggested fix:** Deduplicate by gsId within a session (same gsId + same annotation type = same event, different seat copies). Report both raw and deduplicated counts, or just deduplicated.

---

## 5. md-frames.jsonl is the fast path, proto-decode-recording is not

**Problem:** All five agents converged on grepping `md-frames.jsonl` directly rather than using `just proto-decode-recording`. The decoded output is a different JSON format (wrapped in `clientAction`/`greType` wrappers), 64KB+, and not grep-friendly. The JSONL format is one frame per line, making targeted extraction trivial.

**Impact:** Agents that started with `proto-decode-recording` lost a round-trip discovering the format mismatch. All fell back to `md-frames.jsonl`.

**Field name gotcha:** `md-frames.jsonl` uses `types` (array), not `type` (string). One agent lost a round-trip on this.

**Suggested fix:**
- Document the preferred research path: `md-frames.jsonl` for annotation research, `proto-decode-recording` for full message structure
- Add a helper: `just rec-annotations <session> <TypeName>` that greps the JSONL correctly and outputs clean JSON with resolved card names
- Consider `just rec-context <session> <gsId>` that returns the full frame JSON for a specific game state

---

## 6. No zone ID cheatsheet

**Problem:** Agents had to derive zone ID → name mappings from recording data each time. Zone IDs vary per game (player-relative), making them non-obvious.

**Agent quote (batch 3):** "No quick way to look up all zone IDs — had to derive from recording data."

**Suggested fix:** Either document the zone ID derivation in the investigation playbook, or add a `just rec-zones <session>` command that extracts the zone map from gsId=1 (the initial full GSM always contains the zone definitions).

---

## 7. Stale jar on classpath

**Problem:** `just build` runs the `classes` task but the CLI tools reference `tooling.jar` on the classpath. After editing `AnnotationVariance.kt` and running `just build`, the tool still uses the old jar. Must run `./gradlew :tooling:jar` separately.

**Impact:** The variance tool fix (registering 18 missing builders in OUR_BUILDERS) appeared to not work until the jar was manually rebuilt. Moved from 26 OK → 44 OK after jar rebuild.

**Suggested fix:** Either make `just build` include the jar tasks, or switch the classpath to use classes directories instead of jars.

---

## Priority suggestion

1. **Multi-type annotations** (#1) — highest impact, hides structural relationships
2. **Stale jar** (#7) — trivest fix, blocks all tooling iteration
3. **grpId resolution** (#2) — causes wrong data in report
4. **Per-seat dedup** (#4) — inflated counts mislead prioritization
5. **affectorId** (#3) — additive, no existing data changes
6. **Zone cheatsheet / rec helpers** (#5, #6) — quality-of-life
