# Diagnosis Schema

Structured format for bug diagnoses in the diagnose-fix-verify pipeline. Every claim must cite evidence.

## Citation types

| Type | Format | Verified by |
|---|---|---|
| `recording` | `recording:<session>,gs=<N>,<path>` | Grep md-frames.jsonl |
| `code` | `code:<file>:<line>` | Read file at line |
| `engine` | `engine:<build-output-file>,<path>` | Read engine JSON output |
| `test` | `test:<TestClass>:<line>` | Run test, check assertion |
| `tool` | `tool:<just-command>` | Run command, check output |

## Template

When diagnosing a protocol/annotation/wire bug, structure the diagnosis as:

```
## Diagnosis: <issue title>

Issue: #<N>
Wire spec: <path to wire spec from Phase 0>

### Summary
<1-2 sentences: what's wrong and why>

### Root cause
<specific code location and logic error>
[citation: code:<file>:<line>]

### Claims

1. Server sends <X> for this interaction
   [citation: recording:<session>,gs=<N>,annotations[M].details.<key>]

2. Our code produces <Y> instead
   [citation: engine:build/conformance/<file>,annotations[M].details.<key>]
   [citation: code:<file>:<line> — <why it produces Y>]

3. <additional claims as needed>
   [citation: ...]

### Blast radius
- <files/functions affected by the fix>

### Test gap
- <what test is missing that would have caught this>

### Proposed fix
- <what to change, derived from verified claims>
```

## Rules

1. **Every factual claim gets a citation.** "The server sends category:'Surveil'" needs `[citation: recording:...]`. No unsourced assertions about protocol behavior.

2. **Code citations include line numbers.** "inferCategory falls through" needs `[citation: code:StateMapper.kt:142]`. An agent or human can verify by reading that line.

3. **Claims are independently verifiable.** The conformance subagent at Gate 1 should be able to check each citation without context from the diagnosis.

4. **Root cause explains the observed behavior.** If claims 1 and 2 are both verified but don't logically lead to the observed bug, the root cause is wrong.

5. **Blast radius is conservative.** List everything that touches the affected code path, not just the direct fix site.

## Example

See `docs/retros/2026-03-08-surveil-scoping-retro.md` for a case where incorrect diagnosis (without citations) led to 4 hours of wasted work. The conformance subagent corrected the root cause by independently verifying against recording data.
