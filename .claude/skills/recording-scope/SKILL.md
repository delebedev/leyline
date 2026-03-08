---
name: recording-scope
description: Recording-first feature scoping. Decode real server recordings BEFORE implementing a mechanic to discover the full message sequence — diffs, annotations, instanceId lifecycle, zone transfers. Prevents discovering critical protocol gaps during debugging.
---

## What I do

Decode a proxy recording of the target mechanic and produce a wire-level spec: every S→C and C→S message in the flow, with exact annotation types, zone transfers, instanceId reallocs, and category labels. This becomes the implementation contract.

## When to use me

- Before implementing any new mechanic (combat, surveil, scry, counters, etc.)
- Before adding a new zone transfer category or annotation type
- When a mechanic works in the engine but doesn't render in the client
- "scope surveil from recordings" / "what does the real server send for scry?"

## Why this exists

**Surveil (#66) was the lesson.** We implemented GroupReq/GroupResp exchange correctly but missed:
- `ObjectIdChanged` annotation (instanceId realloc 172→329)
- `TransferCategory.Surveil` (we sent `"Mill"` instead of `"Surveil"`)
- Dual-object diff pattern (old→Limbo, new→Graveyard)
- `affectorId` on annotations

All of these were visible in the proxy recording before we wrote any code. We found them during live debugging instead. See `docs/retros/2026-03-08-surveil-scoping-retro.md`.

## Prerequisites

A proxy recording that exercises the mechanic. If none exists:
```bash
just serve-proxy
# play through the mechanic in Arena client
# recording lands in recordings/<timestamp>/
```

## Process

### 1. Find the mechanic in the recording

```bash
just proto-decode-recording recordings/<session>/capture/payloads
```

Grep for the trigger: `GroupReq`, `SelectTargetsReq`, specific `ClientMessageType`, annotation types, card grpIds. Identify the message index range.

### 2. Extract the full message window

Not just the req/resp — **3 messages before and after**. The surrounding diffs carry the protocol contract:

```bash
# Pipe decoded JSONL, extract indices N-3 through N+3
just proto-decode-recording <dir> | sed -n '<start>,<end>p'
```

For each S→C message, document:
- **GSM type** (Diff/Full), prevGsId chain
- **Objects** — instanceIds, grpIds, zones, visibility
- **Zones** — which zones updated, objectIds added/removed
- **Annotations** — types, affectorId, affectedIds, detail keys+values
- **diffDeletedInstanceIds** — what got retired

### 3. Trace instanceId lifecycle

```bash
just proto-trace <instanceId> <dir>
```

Follow the card from allocation through zone changes to retirement. Note every `ObjectIdChanged` realloc.

### 4. Compare to our code

For each annotation/category in the recording:
```bash
# Do we have the TransferCategory?
grep "<Category>" matchdoor/src/main/kotlin/leyline/game/TransferCategory.kt

# Do we have the annotation builder?
grep "<annotationType>" matchdoor/src/main/kotlin/leyline/game/AnnotationBuilder.kt

# Do we have the GameEvent?
grep "<MechanicName>" matchdoor/src/main/kotlin/leyline/game/GameEvent.kt

# Do we emit it in categoryFromEvents?
grep "<MechanicName>" matchdoor/src/main/kotlin/leyline/game/AnnotationBuilder.kt
```

### 5. Write the wire spec

Output to `docs/plans/<date>-<mechanic>-wire-spec.md`:

```markdown
## <Mechanic> Wire Spec

**Recording:** recordings/<session>/
**Indices:** N-M

### Message sequence
| Index | Dir | Type | Key fields |
|-------|-----|------|------------|
| ... | S→C | Diff | reveal card, Private+viewer |
| ... | S→C | GroupReq/SelectTargetsReq | instanceIds, context |
| ... | C→S | GroupResp/SelectTargetsResp | groups/targets |
| ... | S→C | Diff | ObjectIdChanged, ZoneTransfer, zones |

### Annotations required
| Type | affectorId | affectedIds | Detail keys |
|------|-----------|-------------|-------------|

### TransferCategories required
| Category label | zone_src | zone_dest | Exists in our code? |

### InstanceId lifecycle
<instanceId> allocated at gsId=X, realloc'd at gsId=Y (old→Limbo, new→dest)

### Gaps vs our code
- [ ] Missing TransferCategory variant
- [ ] Missing GameEvent
- [ ] Missing annotation builder param
- [ ] Missing diff pattern (dual-object, Limbo retirement)
```

### 6. Verify with conformance subagent

Launch a `conformance` subagent to independently verify claims:

```
Agent(subagent_type="conformance", prompt="Verify claims in docs/plans/<date>-<mechanic>-wire-spec.md against recording data. Check annotations, categories, instanceId lifecycle. Write findings to docs/plans/<date>-<mechanic>-wire-spec-verification.md")
```

The subagent cross-checks recording data against code. Its findings feed back into the spec before implementation begins.

## Output

1. **Wire spec** — `docs/plans/<date>-<mechanic>-wire-spec.md`
2. **Verification** — `docs/plans/<date>-<mechanic>-wire-spec-verification.md` (from conformance subagent)
3. **Gap checklist** — concrete list of missing enum variants, builders, events, diff patterns

## Key conventions

- **Decode before code.** The recording is the spec, not the engine behavior.
- **3-message window.** The req/resp is the obvious part. The diffs before and after carry the hard protocol.
- **Trace instanceIds.** Every zone change = potential realloc. Miss one = client can't render the transfer.
- **Category labels are exact.** `"Surveil"` ≠ `"Mill"`. The client uses these strings for animations.
- **Verify independently.** The conformance subagent catches assumptions you didn't know you made.
