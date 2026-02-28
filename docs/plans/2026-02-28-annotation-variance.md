# Annotation Variance Tool

CLI tool that profiles annotation detail keys across all proxy recordings,
compares against our `AnnotationBuilder` output, and links each finding
to concrete game moments for quick scenario reconstruction.

## Motivation

Golden tests compare one recording per type — but one sample can't reveal
variance. Some annotation types have **optional** detail keys that only
appear in certain game situations (e.g. `ModifiedPower` with `effect_id`
vs `counter_type`). A variance profile across many games catches:

- Keys we emit with the wrong name (`ModifiedLife: {delta}` vs server's `{life}`)
- Keys that appear only sometimes (indicates conditional fields)
- Annotation types we don't implement at all (15+ discovered)
- Detail value ranges/samples for future builder enrichment

## Usage

```bash
# Full report across all proxy recordings
just proto-annotation-variance

# Filter to one annotation type (all instances, not just examples)
just proto-annotation-variance --type DamageDealt

# Control example count per type (default: 2)
just proto-annotation-variance --examples 5

# Custom recordings directory
just proto-annotation-variance path/to/recordings
```

## Output Format

Markdown report to stdout. Pipe to file for docs:

```
just proto-annotation-variance > /tmp/variance-report.md
```

### Example output

```markdown
# Annotation Variance Report
3 sessions, 622 S-C payloads, 1089 annotation instances, 28 distinct types

## DamageDealt (8 instances, 3 sessions)  — OK
  Always:    {damage, markDamage, type}
  Our keys:  {damage, markDamage, type}

  Examples:
    session=14-15-29 gsId=126 msg=52 T8 CombatDamage
      affectedIds=[341 → Grizzly Bears] details={damage=2, type=1, markDamage=2}
      file: 000000168_MD_S-C_MATCH_DATA.bin

## ModifiedLife (6 instances, 2 sessions)  — MISMATCH
  Always:    {life}
  Our keys:  {delta}
  Missing:   {life}
  Extra:     {delta}

  Examples:
    session=14-15-29 gsId=126 msg=52 T8 CombatDamage
      affectedIds=[2] details={life=14}
      file: 000000168_MD_S-C_MATCH_DATA.bin

## ColorProduction (25 instances, 3 sessions)  — NOT IMPLEMENTED
  Always:    {colors}

  Examples:
    session=09-33-05 gsId=48 msg=19 T3 Main1
      affectedIds=[283 → Plains] details={colors=["White"]}
      file: 000000098_MD_S-C_MATCH_DATA.bin
```

## Architecture

### Data flow

1. **Discover** proxy capture dirs: `recordings/*/capture/payloads/`
2. **Decode** each session via `RecordingDecoder.decodeDirectory(captureDir)`
   — returns `List<DecodedMessage>` with annotations, objects, turnInfo
3. **Build instanceId→cardName map** per session from `ObjectSummary` entries
   — uses `CardDb.getCardName(grpId)`, falls back to `grp:12345` if no Arena DB
4. **Collect** every `AnnotationSummary` from every `DecodedMessage`, recording:
   - Type name (suffix-stripped: `DamageDealt_af5a` → `DamageDealt`)
   - Detail key names + values
   - Provenance: session, gsId, msgId, turn, phase, file, cards involved
5. **Aggregate** per type: instance count, session count, key frequency, value samples
6. **Compare** vs static `OUR_BUILDERS` map → status flag
7. **Print** markdown report

### Dependencies (all existing)

| Dependency | Purpose |
|---|---|
| `RecordingDecoder` | Parse .bin, decode GRE messages into summaries |
| `CardDb` | grpId → card name (lazy init from Arena SQLite) |
| `AnnotationBuilder` | Call with dummy args to extract our detail keys |

### New file

`forge-nexus/src/main/kotlin/forge/nexus/debug/AnnotationVariance.kt` (~200 LOC)

### Just target

In `forge-nexus/just/proto.just`:
```just
proto-annotation-variance *args: (_require classpath) check-java
    @{{_nexus_cli}} forge.nexus.debug.AnnotationVarianceKt {{args}}
```

## Known Findings (from quick scan)

From 3 proxy sessions, 622 payloads:

| Type | Instances | Server Keys | Our Keys | Status |
|---|---|---|---|---|
| DamageDealt | 8 | damage, type, markDamage | damage, type, markDamage | OK (just fixed) |
| ManaPaid | 48 | id, color | id, color | OK (just fixed) |
| AbilityInstanceCreated | 69 | source_zone | source_zone | OK (just fixed) |
| ModifiedLife | 6 | **life** | **delta** | MISMATCH |
| ModifiedPower | 4+ | **effect_id** (variants) | **value** | MISMATCH |
| ModifiedToughness | 4+ | **effect_id** (variants) | **value** | MISMATCH |
| SyntheticEvent | 5 | **type** | (none) | MISMATCH |
| ColorProduction | 25 | colors | — | NOT IMPLEMENTED |
| TriggeringObject | 15 | source_zone | — | NOT IMPLEMENTED |
| LayeredEffect* | 6+ | effect_id (variants) | — | NOT IMPLEMENTED |
| AbilityWordActive | 6 | AbilityGrpId, ... | — | NOT IMPLEMENTED |
| TargetSpec | 5 | abilityGrpId, index, ... | — | NOT IMPLEMENTED |
| Qualification | 3 | QualificationType, ... | — | NOT IMPLEMENTED |
| AbilityExhausted | 3 | AbilityGrpId, ... | — | NOT IMPLEMENTED |
| PowerToughnessModCreated | 3 | power, toughness | — | NOT IMPLEMENTED |
| +7 more | 1-5 each | various | — | NOT IMPLEMENTED |

### Key mismatches to investigate

1. **ModifiedLife**: Server sends absolute `{life}`, we send relative `{delta}`.
   Client parser likely expects `life` key — potential display bug.

2. **ModifiedPower/Toughness**: Server uses `{effect_id}` (references layered
   effect system), sometimes with `{counter_type, count}` or
   `{effect_id, sourceAbilityGRPID}`. We send `{value}` (just the number).
   Completely different model — server ties P/T changes to their cause.

3. **SyntheticEvent**: Server sends `{type}` detail, we send nothing.
   Currently suppressed in our pipeline (client parser crash), but wrong shape.

## Edge Cases

- **Sessions without MD payloads** (FD-only early captures): skipped silently
- **CardDb unavailable** (no Arena install): card names show as `grp:12345`
- **Persistent annotations**: included in scan, same treatment as transient
- **Multiple types per annotation**: rare but possible — each type counted separately
- **Value variance within a key**: samples capped at 5 unique values per key

## Not in Scope

- No test class (CLI tool only — run on demand)
- No auto-fix of builders — tool reports, human triages
- No engine recordings (our own output, not reference data)
- No cross-session correlation (each session independent)
