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

### 1. Variance report

```bash
just proto-annotation-variance 2>&1 | grep -A 20 '## <TypeName> '
```

Extract:
- Instance count and session spread
- Status (NOT IMPLEMENTED / MISMATCH / OK)
- Detail keys: Always / Sometimes / Samples
- Sample instances with gsId, affectedIds, file references

### 2. Recording extraction

Find all instances across recordings with full context:

```python
# Search persistentAnnotations (persistent state) and annotations (one-shot events)
# The type may appear in either or both — check the disjoint catalog:
#   persistentAnnotations: ongoing effects, counters, granted abilities, markers
#   annotations: one-shot events (zone transfers, damage, taps)
for ann in d.get("persistentAnnotations", []) + d.get("annotations", []):
    if "<TypeName>" in ann.get("types", []):
        # extract affectedIds, affectorId, details
```

Track:
- **Affected cards** (instanceId → grpId from objects in same GSM)
- **Detail key values** — distribution, not just samples
- **Dual-typed annotations** — some have two types e.g. `["AddAbility", "LayeredEffect"]`
- **Lifecycle** — does it appear, persist across GSMs, then disappear? Or one-shot?

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

### Implementation notes
<Anything notable: enum mappings needed, lifecycle complexity, Forge API to read from>
```

## Key conventions

- **Use tooling, not manual grep.** `proto-annotation-variance` is the entry point. `just card`/`just ability` for name resolution.
- **One annotation at a time.** Don't batch — each has unique detail keys and semantics.
- **Dual-typed annotations are common.** `["AddAbility", "LayeredEffect"]` — document both types and their relationship.
- **Check lifecycle.** Some persistent annotations appear once and stay forever. Others update (count changes) or get removed. Note which.
- **Don't propose fixes.** This is research, not implementation. The field note feeds into plan-fix.
