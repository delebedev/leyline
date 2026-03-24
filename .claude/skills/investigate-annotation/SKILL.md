---
name: investigate-annotation
description: Investigate a single persistent annotation type from real server recordings. Documents what it means, what triggers it, detail key schema, and affected cards. Produces a structured field note for implementation planning.
---

## What I do

Research one annotation type from the `proto-annotation-variance` report. Combines recording data, proto definitions, and card DB lookups into a structured field note.

## When to use me

- "investigate Counter" / "investigate AddAbility"
- When planning implementation of a missing persistent annotation
- As input to a spec or implementation plan

## Inputs

- **Annotation type name** (required) — e.g. `Counter`, `AddAbility`, `LayeredEffect`

## Process

### 0. HARD GATE — Extract full contract BEFORE any manual investigation

Run `tape annotation contract` on every proxy session that has the type. This extracts the complete annotation shape (multi-types, affectorId, detail keys, companions, lifecycle) in seconds. **Do not skip this step.**

```bash
# Find sessions with relevant cards
just tape session find "<CardName>"

# Extract full contract from a proxy session
just tape annotation contract <session> <TypeName>

# Narrow to a specific effect ID if needed
just tape annotation contract <session> <TypeName> <effectId>
```

The contract output gives you:
- **Type arrays** — multi-type co-occurrence (e.g. `[ModifiedPower, ModifiedToughness, LayeredEffect]`)
- **affectorId pattern** — always/never/sometimes set, and what it points to
- **Detail keys** — frequency, value type, samples
- **Companion annotations** — other types in same GSM with related IDs
- **Lifecycle** — Created → Persistent → Destroyed timeline

**This is the ground truth.** Everything below is verification and enrichment.

### 1. Variance report

```bash
just proto-annotation-variance 2>&1 | grep -A 20 '## <TypeName> '
```

Extract:
- Instance count and session spread
- Status (NOT IMPLEMENTED / MISMATCH / OK)
- Cross-session detail key frequency (complements per-session contract)

### 2. Recording deep-dive (if contract output needs clarification)

Only needed when the contract output raises questions. The contract tool handles most cases.

For edge cases, manually inspect:

```python
for ann in d.get("persistentAnnotations", []) + d.get("annotations", []):
    if "<TypeName>" in ann.get("types", []):
        # extract affectedIds, affectorId, details
```

Track:
- **Affected cards** (instanceId → grpId from objects in same GSM)
- **Detail key values** — distribution, not just samples
- **Multi-typed annotations** — the contract tool shows these, verify if unclear
- **Lifecycle** — the contract tool shows Created/Persistent/Destroyed events

### 3. Resolve names

```bash
# Card names from grpIds
just card <grpId1> <grpId2> ...

# Ability names from ability grpIds (if detail keys contain grpid/abilityGrpId)
just ability <id>

# Counter types, phases, etc. from proto enums
grep -A 5 '= <value>' matchdoor/src/main/proto/messages.proto
```

### 4. Check our code

```bash
# Do we emit this annotation type anywhere?
grep -rn '<TypeName>' matchdoor/src/main/kotlin/

# Do we have a builder for it?
grep -n '<typeName>\|<TypeName>' matchdoor/src/main/kotlin/leyline/game/AnnotationBuilder.kt

# Do we have a GameEvent for it?
grep -n '<TypeName>\|<related_concept>' matchdoor/src/main/kotlin/leyline/game/GameEvent.kt
```

### 5. Write field note

Output as a structured artifact:

```markdown
## <TypeName> — persistent annotation field note

**Status:** NOT IMPLEMENTED / MISMATCH / OK
**Instances:** N across M sessions
**Proto type:** AnnotationType.<proto_enum_name> (number N)
**Field:** persistentAnnotations / annotations

### What it means in gameplay
<1-2 sentences: what the player sees, what game concept it represents>

### Detail keys
| Key | Always/Sometimes | Values | Meaning |
|-----|-----------------|--------|---------|
| ... | ... | ... | ... |

### Triggers
<What game actions cause this annotation to appear/change/disappear>

### Affected cards (from recordings)
| Card | grpId | Context | Count |
|------|-------|---------|-------|
| ... | ... | ... | ... |

### Our code status
- Builder: exists / missing (AnnotationBuilder.kt:line)
- GameEvent: exists / missing
- Emitted: yes / no (call sites)

### Dependencies
<Other annotations this relates to, e.g. AddAbility depends on LayeredEffect>

### Implementation contract (from tape annotation contract)
- Type array: `[<types>]`
- affectorId: always/never/sometimes — points to <what>
- Detail keys: <key=frequency>
- Companion transients: <types>
- Lifecycle: Created→Persistent→Destroyed / one-shot / permanent

### Implementation notes
<Anything notable: enum mappings needed, lifecycle complexity, Forge API to read from>
```

## Post-fix verification checklist

After implementing or fixing an annotation, verify end-to-end:

1. **Write puzzle** — `.pzl` file that triggers the annotation (e.g. creature with counters, aura attachment). Validate with `just puzzle-check`.
2. **Unit test** — use real Forge names in test input (e.g. `"+1/+1"` not `"P1P1"`). Assert detail key values match proto enum numbers.
3. **Visual verify** — set `game.puzzle` to the file, run `just serve`, connect client, trigger the annotation in-game.
4. **Wire check** — `curl -s http://localhost:8090/api/messages` → find the annotation in JSON, confirm detail values match real server recordings.
5. **Update docs** — `docs/rosetta.md` status column, `docs/catalog.yaml` entry, protocol-summary counts if tier changes.

### Forge naming gotcha

Forge enum display names ≠ Java enum constants ≠ proto enum names:
- `CounterEnumType.P1P1.getName()` → `"+1/+1"` (display), `.name()` → `"P1P1"` (Java), proto → `P1P1 = 1`
- `CounterEnumType.LOYALTY.getName()` → `"LOYAL"`, proto → `Loyalty_a40e = 7`

Any mapping between Forge events and proto values must account for display names. Test with what Forge actually sends, not what the enum constant looks like.

## Related skills

- **recording-scope** — for full mechanic scoping (not just one annotation). Covers the entire message sequence around a mechanic including zone transfers, instanceId lifecycle, and diff patterns. Use recording-scope when implementing a new mechanic; use investigate-annotation when researching a single annotation type.

## Key conventions

- **Contract first, code never.** Always run `just tape annotation contract` before writing any annotation code. The contract shows multi-types, affectorId, companions — things the variance report misses.
- **Use tooling, not manual grep.** `just tape session find` for card lookup, `just tape annotation contract` for full shape, `just tape annotation variance` for cross-session stats. `just card`/`just ability` for name resolution.
- **One annotation at a time.** Don't batch — each has unique detail keys and semantics.
- **Multi-typed annotations are the norm.** `["AddAbility", "LayeredEffect"]`, `["ModifiedPower", "ModifiedToughness", "LayeredEffect"]` — the type array is part of the contract. Don't emit a single type when the real server sends three.
- **affectorId matters.** It drives client behavior (animation source, linking). Check the contract — it tells you if it's always/never/sometimes set.
- **Check companion annotations.** `PowerToughnessModCreated` alongside `LayeredEffectCreated`, `ResolutionComplete` alongside ability resolution — companions are part of the contract.
- **Check lifecycle.** Some persistent annotations appear once and stay forever. Others update (count changes) or get removed. Note which.
- **Don't propose fixes.** This is research, not implementation. The field note feeds into plan-fix.
- **Variance report can be misleading.** "NOT IMPLEMENTED" means not in proxy recordings — check our code first before assuming a gap.
