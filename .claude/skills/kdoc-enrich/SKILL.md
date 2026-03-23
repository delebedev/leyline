---
name: kdoc-enrich
description: Enrich KDoc on a Kotlin class with lifecycle, ordering invariants, threading contracts, and cross-class references. Use when a class has thin documentation relative to its complexity — especially classes with invisible constraints, multi-class state flows, or ordering dependencies.
---

## What I do

Audit a Kotlin class's KDoc against its actual complexity, then enrich with documented facts from code and tests. Follows `docs/principles-documentation.md` — KDoc at the seam, invisible constraints documented, no standalone docs for single-class knowledge.

## When to use me

- "enrich KDoc on X" / "document X's lifecycle"
- When a class has ordering dependencies, threading contracts, or cross-class state flows that aren't in the KDoc
- After the architect agent flags a class as having thin documentation relative to complexity
- Before onboarding someone to a complex subsystem

## Inputs

- **Target class** (required) — fully qualified or just class name
- **Focus areas** (optional) — "threading", "lifecycle", "cross-class", "ordering"

## Process

### 1. GATHER — read the class and its ecosystem

Read the target class fully. Then identify and read:

- **Callers:** Where is this class instantiated? Who calls its key methods? (Use `Grep` for method names)
- **Callees:** What does it depend on? What mutable state does it touch?
- **Tests:** What invariants do tests assert? What ordering do they enforce? (Read test files for the class)
- **Cross-class contracts:** Does this class set flags/state consumed elsewhere? Does it consume flags set elsewhere?
- **Existing docs:** Check class-level KDoc, method KDocs, inline comments, and any `docs/` references

### 2. ASSESS — identify documentation gaps

Score the class on these axes (from `docs/principles-documentation.md`):

| Axis | Question | If yes → document it |
|------|----------|---------------------|
| **Ordering** | Can swapping two calls break something non-obviously? | KDoc on the method with the ordering constraint |
| **Threading** | Which thread runs each method? Can methods interleave? | Class-level KDoc section |
| **Lifecycle** | Create/update/delete across GSMs — is the full cycle documented? | Class-level KDoc section |
| **Cross-class** | Does state flow to/from another class via flags, queues, or shared refs? | Both classes should reference each other |
| **Invisible constraints** | Are there preconditions the type system doesn't enforce? | KDoc on the method that enforces them |
| **ID allocation** | Are there monotonic counters, ID spaces, or collision avoidance? | Class-level KDoc section |

### 3. WRITE — enrich KDoc with facts

Rules:
- **Class-level KDoc:** Add sections for lifecycle, threading, cross-class contracts — only what's missing
- **Method-level KDoc:** Add ordering invariants, preconditions, "must call A before B" constraints
- **Inline comments:** Add "invisible constraint" comments at the exact lines where ordering matters (in callers like StateMapper, not just the class itself)
- **Cross-references:** If class A sets flags consumed by class B, both A and B should name each other
- **Never create standalone docs** for single-class knowledge (principle #4)
- **Never restate** what the code already makes obvious — document the WHY, not the WHAT
- **Keep existing KDoc** — extend, don't replace. Preserve the author's voice and existing structure

### 4. VERIFY — check the result

- Run `just fmt` (formatting only, no tests needed for comment-only changes)
- Verify no behavioral changes: `git diff --stat` should show only `.kt` files, only comment/KDoc lines
- Read back the enriched KDoc — does it answer "what would I need to know if I'd never seen this class?"

## Output format

Brief summary of what was added:
- Which classes were enriched
- What key knowledge was captured (1-2 sentences per class)
- Any cross-references added between classes

## Candidates backlog

Priority ranking from architectural analysis (update as classes are enriched):

| Class | Priority | Key gap |
|-------|----------|---------|
| MatchSession | MED-HIGH | `onPerformAction` flow summary, snapshot seeding invariant |
| BundleBuilder | MED | phaseTransitionDiff rationale, pendingMessageCount caller contract |
| InstanceIdRegistry | MED | Limbo monotonic growth, startId convention |
| DiffSnapshotter | LOW-MED | "Don't snapshot what you haven't sent", 3 seeding sites |
| EffectTracker | LOW | Already best KDoc-to-complexity ratio |

## Decision criteria — "is this class worth enriching?"

Use when triaging new candidates:

1. **Gap between complexity and documentation** — many LOC, many callers, thin KDoc
2. **Cross-class contracts** — state shared via flags/queues with no docs on either side
3. **Deadlock/threading risk** — wrong usage can block a thread or corrupt state
4. **Re-derivation cost** — how long would it take to re-learn by reading code alone?

If a class scores HIGH on any of these, it's worth enriching. If it scores LOW on all, skip it.
