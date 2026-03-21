# Annotation Variance Tool

Profile annotation detail keys across **all** proxy recordings to find
mismatches, missing types, and optional fields that single golden tests can't catch.

Companion to [golden-tests.md](golden-tests.md) (per-message field coverage)
and [recording-cli.md](recording-cli.md) (game-level browsing).

## Quick start

```bash
just proto-annotation-variance
```

Output is markdown to stdout. Pipe to a file for reference:

```bash
just proto-annotation-variance > /tmp/variance-report.md
```

## What it does

1. Scans all `recordings/*/capture/payloads/` directories (proxy captures from real Arena server)
2. Decodes every `MatchServiceToClientMessage` → extracts all annotations from every GSM
3. Groups by annotation type name (suffix-stripped, e.g. `DamageDealt_af5a` → `DamageDealt`)
4. Per type: computes always-present keys, sometimes-present keys (with frequency %), value samples
5. Compares against our `AnnotationBuilder` output → status flag per type

## Reading the report

### Status flags

| Status | Meaning | Action |
|---|---|---|
| **OK** | Our builder's detail keys match the server's always-present keys | None — keep monitoring |
| **MISMATCH** | Different keys (lists `Missing` and `Extra`) | Fix builder — wrong key names or missing fields |
| **NOT IMPLEMENTED** | No builder exists for this annotation type | Implement when the mechanic is needed |

### Key sections per type

```
## ModifiedLife (8 instances, 2 sessions)  -- MISMATCH
  Always:    {life}              ← keys present in 100% of instances
  Sometimes: (none)              ← keys in <100% (conditional fields)
  Our keys:  {delta}             ← what our builder emits
  Missing:   {life}              ← server sends, we don't
  Extra:     {delta}             ← we send, server doesn't
  Samples:   life=[-3, 3, -8]   ← real values seen

  [1] session=14-15-29 gsId=126 msg=52 T8 CombatDamage     ← provenance
      affectedIds=[2] details={life=-3}
      file: 000000445_MD_S-C_MATCH_DATA.bin                 ← exact payload
```

### Provenance

Every type includes 1-2 concrete examples with:
- **session** — recording directory (short name, e.g. `14-15-29`)
- **gsId** — game state ID (use with `proto-trace` or `proto-inspect` to dig deeper)
- **msg** — message ID within the GRE stream
- **turn/phase** — game context (e.g. `T8 CombatDamage`)
- **affectedIds** — instance IDs involved, resolved to card names when possible
- **file** — exact `.bin` payload file for `proto-inspect`

To inspect the raw proto of an example:

```bash
just proto-inspect recordings/2026-02-28_14-15-29/capture/payloads/000000445_MD_S-C_MATCH_DATA.bin
```

To trace a card across the game:

```bash
just proto-trace 355 recordings/2026-02-28_14-15-29/capture/payloads
```

## What to look for

### 1. MISMATCH types — wrong key names

These are bugs. The client's annotation parsers expect specific key names.

**Current mismatches:**

| Type | Server key | Our key | Impact |
|---|---|---|---|
| `ModifiedLife` | `life` (absolute) | `delta` (relative) | Client may not display life change animation |
| `ModifiedPower` | `effect_id` + variants | `value` | Client ties P/T changes to their cause via effect IDs |
| `ModifiedToughness` | `effect_id` + variants | `value` | Same as ModifiedPower |
| `SyntheticEvent` | `type` (always `1`) | (none) | Currently suppressed — client parser crashed on our shape |

### 2. Sometimes-present keys — conditional fields

These reveal fields that appear only in specific game situations:

- `ModifiedPower.sourceAbilityGRPID` (10%) — only when a specific ability causes the change
- `LayeredEffect` variants — `{effect_id}` always, but sometimes adds `{MaxHandSize}`, `{grpid, UniqueAbilityId, originalAbilityObjectZcid}`, or `{sourceAbilityGRPID}`

These won't cause immediate bugs if missing, but they're needed for full fidelity.

### 3. NOT IMPLEMENTED types — missing annotation builders

Types the real server sends that we don't emit at all. Priority order:

**High priority** (affect gameplay display):
- `ColorProduction` (41 instances) — mana color indicators, used by auto-tap UI
- `TriggeringObject` (18) — marks triggered abilities, client uses for trigger animation
- `Counter` (3) — persistent counter state (complements `CounterAdded`)

**Medium priority** (affect visual fidelity):
- `LayeredEffect` / `LayeredEffectCreated` / `LayeredEffectDestroyed` — continuous effect tracking
- `TargetSpec` / `PlayerSelectingTargets` / `PlayerSubmittedTargets` — targeting flow UI
- `PowerToughnessModCreated` — P/T modification display
- `Qualification` — card qualification indicators

**Low priority** (cosmetic / rare):
- `AbilityWordActive` — threshold indicator (e.g. "you have 3+ lessons in graveyard")
- `AbilityExhausted` — "used up" ability marker
- `AddAbility` — granted ability indicator
- `DisplayCardUnderCard` — card-under-card display (e.g. Imprint, Adventure exile)
- `MiscContinuousEffect` — max hand size changes
- `DamagedThisTurn` — damage marker for "was dealt damage this turn" effects

### 4. Value patterns

Look at `Samples` for unexpected patterns:
- `ManaPaid.color` uses numeric codes (`1`=White, `2`=Blue, `3`=Black, `4`=Red, `5`=Green)
- `ManaPaid.id` is sequential per-game (mana payment tracking)
- `DamageDealt.type` is always `1` (combat) in our recordings — non-combat damage would be `0`
- `DamageDealt.markDamage` is always `1` — marks damage on the creature, usually equals `damage` but can differ with prevention

## CLI options

```bash
# Full report (default 2 examples per type, sorted: MISMATCH → NOT IMPL → OK)
just proto-annotation-variance

# Problems only — skip OK types
just proto-annotation-variance --skip-ok

# Compact summary table (no examples, just status per type)
just proto-annotation-variance --summary

# More examples per type
just proto-annotation-variance --examples 5

# Filter to one type (shows all instances)
just proto-annotation-variance --type DamageDealt

# Custom recordings directory
just proto-annotation-variance path/to/other/recordings
```

Output is always sorted by severity: MISMATCH first, then NOT IMPLEMENTED, then OK.
The header line shows counts: `Status: 4 MISMATCH, 17 NOT IMPLEMENTED, 18 OK`.

## Fixing a builder — workflow

1. **Fix** the builder in `AnnotationBuilder.kt` (e.g. rename `delta` → `life`)
2. **Run `just test-gate`** — `goldenReferenceConformance` test fails:
   `"ModifiedLife: marked as expectedMismatch but now matches! Remove from expectedMismatch."`
3. **Remove** the entry from `expectedMismatch` in `AnnotationBuilderTest.kt`
4. **Run `just test-gate`** again — all pass
5. **Run `just proto-annotation-variance --summary`** — confirm type shows OK

The `expectedMismatch` map in `AnnotationBuilderTest.kt` is the backlog.
Each entry has a one-line fix instruction. When the map is empty, all
builders match the real server.

Current mismatches (fix instructions inline):
```
"ModifiedLife"      → sends {delta} instead of {life} — rename key
"ModifiedPower"     → sends {value} — server sends no required keys, drop it
"ModifiedToughness" → sends {value} — server sends no required keys, drop it
"SyntheticEvent"    → missing {type} detail — add it
```

## When to run

- **After capturing new proxy recordings** — new games may reveal new annotation types or variant keys
- **After fixing a builder** — verify the type moves from MISMATCH to OK
- **When investigating client animation bugs** — check if the annotation type is MISMATCH or NOT IMPLEMENTED
- **Before implementing a new mechanic** — check what annotations the server sends for it

## Key files

| File | Role |
|---|---|
| `src/main/kotlin/.../debug/AnnotationVariance.kt` | CLI tool |
| `src/main/kotlin/.../game/AnnotationBuilder.kt` | Our annotation builders (comparison target) |
| `recordings/*/capture/payloads/` | Proxy captures (data source) |
| `docs/plans/2026-02-28-annotation-variance.md` | Implementation plan |
