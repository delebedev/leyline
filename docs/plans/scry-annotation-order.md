# scry sequences: annotation ordering output

Add within-GSM annotation ordering to `scry sequences`. Currently the tool reports which annotations are present per slot (always/sometimes). It should also report the **order** they appear in.

## What to add

New field in per-slot output: `annotationOrder` — the canonical ordering of annotation types within that slot, derived from the raw GSM annotation list across all instances.

### JSON output

Add to each slot in `--json`:

```json
{
  "annotationOrder": ["PhaseOrStepModified", "DamageDealt", "SyntheticEvent", "ModifiedLife", "ObjectIdChanged", "ZoneTransfer"],
  "orderConsistency": 0.95
}
```

- `annotationOrder`: most common ordering across instances (majority vote per position)
- `orderConsistency`: fraction of instances that match the canonical order exactly (1.0 = all identical)

### Human-readable output

After the existing annotation list, add ordering when the slot has 3+ annotation types:

```
  [1] COMBAT_DAMAGE  SendHiFi  [POS DD SE ML OIC? ZT?]
      order: POS → DD → SE → ML → OIC → ZT  (95% consistent)
```

## Implementation

In `classifySlot()` or the aggregation step:

1. For each instance's slot, extract the annotation type list from `gsm.raw.annotations` in order (already available — `annotations` is an array, order preserved from Player.log)
2. Strip `AnnotationType_` prefix
3. Collect all orderings across instances for the same slot position
4. Canonical = most frequent ordering
5. Consistency = count(canonical) / total

The annotation type list is already in `gsm.raw.annotations[].type[]`. Just map each annotation to its first type and collect the sequence.

### Edge case

Some annotations have multiple types (multi-typed pAnns like `[ModifiedType, LayeredEffect]`). Use the first type in the array — that's the primary type the client dispatches on.

## Where

`tools/scry-ts/src/commands/sequences.ts` — modify `aggregateSlots()` to compute ordering, modify both render functions to display it.

## Verification

Run against game `2026-03-30_20-06`. COMBAT_DAMAGE_KILL slot 1 should produce:

```
order: POS → DD → LED → OIC → ZT → AID  (100% consistent)
```

Confirming DamageDealt always before ObjectIdChanged/ZoneTransfer per Rule 5 in `docs/conformance/annotation-ordering-within-gsm.md`.
