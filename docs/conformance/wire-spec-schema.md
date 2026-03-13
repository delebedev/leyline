# Wire Spec Schema

Phase 0 artifact for the diagnose-fix-verify pipeline. Produced by decoding a recording segment BEFORE any diagnosis or implementation work.

## When to produce

Any bug or feature involving:
- Zone transfers (card movement between zones)
- Annotations (visual effects, state changes)
- Interactive prompts (GroupReq, SelectTargetsReq, DeclareAttackersReq)
- Priority flow (auto-pass, phase stops)
- Message sequencing

## How to produce

```bash
# 1. List available segments in a recording
just tape segment list [session]

# 2. Extract + templatize the relevant segment
just tape segment template <category> [session] > /tmp/<mechanic>-template.json

# 3. Review the template — the message structure IS the spec
cat /tmp/<mechanic>-template.json
```

## Schema

The wire spec is the template JSON output plus a header block. Agents producing a wire spec should include:

```
Recording:  <session path>
Segment:    <category>, index <N>, frames <start>-<end>
Cards:      <card names involved, with grpIds>

Key observations:
- <what annotations are present>
- <what persistent annotations are present>
- <what zone transfers occur, with categories>
- <what instanceId lifecycle events occur (ObjectIdChanged, realloc)>
- <anything unexpected or noteworthy>
```

The JSON template itself (from `just tape segment template`) is the machine-readable ground truth. The observations are the agent's interpretation — and are subject to independent verification at Gate 1.

## Verification

After producing a wire spec, dispatch to the conformance subagent:

> Verify claims in [wire spec] against recording data at [session path].
> Check: annotation types, detail keys, categories, instanceId lifecycle.
> Report any incorrect or missing claims.

The subagent reads the recording independently and confirms or corrects each observation.

## Example

See `docs/conformance/pipeline.md` "Worked Example: Cast Creature with ETB Scry" for a fully annotated wire spec covering 13 messages.
