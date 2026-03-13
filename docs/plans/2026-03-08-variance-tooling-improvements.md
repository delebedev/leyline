# Variance Tooling Improvements

Friction points from running 5 parallel conformance agents against `just proto-annotation-variance` (2026-03-08 sprint). Each one slowed annotation research; fixing them compounds across every future investigation.

**Tool:** `just proto-annotation-variance` (`tooling/src/main/kotlin/leyline/debug/AnnotationVariance.kt`)

---

## Priority order

### 1. Multi-type annotations hidden (highest impact)

The tool reports by individual type name. Co-typed annotations (e.g. `[ModifiedCost, TextChange, ModifiedName, RemoveAbility, LayeredEffect]`) appear as 5 separate entries with identical counts — hiding that they're one annotation.

**Fix:** Show full type list per annotation. Flag entries with identical count+sessions as likely co-typed. Group or add a "co-types" column.

### 2. Stale jar on classpath (trivial fix, blocks iteration)

`just build` runs `classes` but CLI tools reference `tooling.jar`. Edits to `AnnotationVariance.kt` don't take effect until manual `./gradlew :tooling:jar`.

**Fix:** Make `just build` include jar tasks, or switch classpath to classes directories.

### 3. grpId resolution stale after zone transfers (wrong data)

instanceId→grpId mapping uses last-known value. After ObjectIdChanged realloc, reports wrong card names (e.g. Swamp instead of Overlord of the Hauntwoods).

**Fix:** Snapshot instanceId→grpId at each GSM. Resolve using the mapping from the annotation's GSM, not global last-seen.

### 4. Per-seat echo inflates counts (misleading)

2-player recordings count each seat's copy as a separate instance. "4 instances, 1 session" = 2 real events x 2 seats.

**Fix:** Deduplicate by gsId within session (same gsId + same annotation type = same event). Report deduplicated counts.

### 5. affectorId missing from report (additive)

Report shows `affectedIds` with grpId resolution but omits `affectorId`. For many types (LayeredEffectCreated, ReplacementEffect, TemporaryPermanent), the affector is more informative than the affected.

**Fix:** Add `affectorId` to report output with grpId resolution.

### 6. Recording research helpers (quality of life)

**md-frames.jsonl is the fast path.** All agents converged on grepping JSONL directly over `just proto-decode-recording` (64KB+, not grep-friendly). Field gotcha: `types` (array), not `type` (string).

**No zone ID cheatsheet.** Agents derived zone ID→name mappings from recording data each time.

**Fix:**
- `just rec-annotations <session> <TypeName>` — grep JSONL, output clean JSON with resolved card names
- `just rec-zones <session>` — extract zone map from gsId=1 (initial full GSM)
- Document preferred path: `md-frames.jsonl` for annotation research, `proto-decode-recording` for full message structure
