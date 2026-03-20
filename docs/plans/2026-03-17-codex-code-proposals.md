# Codex Code Proposals

**Date:** 2026-03-17

Short list from an independent repo review. Not a full design doc. Goal: capture a few changes that look small-to-medium but high leverage. Another agent can validate / refine before implementation.

---

## 1. Add a few transport-seam tests, not many more unit tests

### Problem

Unit coverage is already strong. Remaining risk is concentrated in transport/lifecycle seams.

### Why it matters

More builder/unit coverage will not catch:

- seat-2 bridge misuse
- disconnect cleanup gaps
- game-over delivery after stack resolution

### Proposed direction

Prefer a tiny set of high-value integration/conformance tests:

1. stack-resolution lethal sends game-over bundle
2. seat-2 cast/target/prompt flow over real MD path

### Validation

If these are stable, a lot of current architectural anxiety drops.

---

## Suggested order

1. add remaining transport-seam tests
