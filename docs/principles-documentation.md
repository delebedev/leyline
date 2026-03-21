# Documentation Principles

Five rules for documenting a codebase that bridges a complex engine to a
reimplemented protocol.

## 1. Rationale at the seam

The hardest thing to re-derive is *why* a design split exists. Put that
in the KDoc of the class at the split point — not in a standalone doc
someone has to know exists.

A class header explaining why `ClientAutoPassState` is separate from
`PhaseStopProfile` is an architectural decision record that lives
exactly where you need it. A standalone ADR in `docs/decisions/` saying
the same thing is a copy that rots.

Good: "Extracted from MatchSession for independent testability" on
CombatHandler. You read the class, you understand the boundary.

Bad: a design doc titled "Combat Handler Extraction" that explains the
same thing but lives three directories away and references line numbers
that moved.

## 2. One source of truth per layer

Every fact has one authoritative home. Other layers cross-reference it;
they never restate it.

| Layer | Authority | Example |
|-------|-----------|---------|
| Protocol behavior | Decompiled client reference | (from Arena client analysis) |
| Engine behavior | Engine source | `PhaseHandler.java` |
| Bridge design | KDoc on the bridge class | `GameBridge.kt` header |
| What works e2e | Living catalog | `catalog.yaml` |
| How to debug X | Playbook | `playbooks/priority-debugging-playbook.md` |

When two docs explain the same thing, one will drift. Delete the copy,
add a cross-reference. A `diff-semantics.md` that restates what the
decompiled `diff-processing.md` already covers and what `StateMapper.kt`
already documents in its KDoc is two extra places to go stale.

## 3. Document invisible constraints

The most expensive undocumented knowledge is the constraint the type
system doesn't enforce. Ordering dependencies, threading contracts,
"build state before actions because instanceId reallocation happens
during diff-building."

These cost hours to debug when missing and two lines to explain when
present. They belong in the KDoc of the function that enforces them.

Signs you're looking at an invisible constraint:
- Swapping two calls breaks something non-obviously
- A `synchronized` block or lock whose scope isn't self-evident
- A field that must be set before another field is read
- An annotation that must appear before another annotation in a list

## 4. Standalone docs for cross-cutting; KDoc for per-class

A doc earns standalone status when it spans many classes and has no
single natural home: protocol translation tables, mechanic catalogs,
diagnostic playbooks, wire format specs.

A doc about how one class works is that class's KDoc. The moment it's
external, it can't be updated in the same commit as the code change,
and it won't be.

Test: if renaming the class would make the doc's title wrong, it should
be a KDoc.

## 5. Plans are ephemeral

A plan still in `docs/plans/` looks like unstarted work. An implemented
plan is noise that dilutes the remaining work's signal. Archive or
delete when done. Git history preserves everything.

Corollary: investigation journals (deep-dive notes from tracing a
specific annotation type or recording session) are valuable research
but are not reference. Label them as dated investigation notes so
nobody mistakes observations from February for current spec.
