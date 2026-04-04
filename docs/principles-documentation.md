---
summary: "Five rules for documenting leyline: rationale at the seam, single source of truth, YAML frontmatter on every doc, and no orphaned docs."
read_when:
  - "writing or updating documentation"
  - "deciding where to put a new piece of knowledge"
  - "adding frontmatter to docs"
---
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

## 6. Agent-first, human-readable

CLAUDE.md is the primary entry point for contributors — agent and human
alike. Standalone docs serve agents first; humans benefit from the same
clarity. Write for a reader who has 200K tokens of context and needs to
decide in one line whether a doc is relevant right now.

## 7. `read_when` frontmatter on every standalone doc

Every file in `docs/` gets YAML frontmatter:

```yaml
---
summary: "One-line purpose of this document."
read_when:
  - "condition when an agent should read this"
  - "another condition"
---
```

`summary` is for scanning. `read_when` declares relevance conditions.
An agent should never need to read all 150 docs — it scans summaries,
matches conditions to its current task, and reads selectively.

## 8. One canonical location per fact

Every fact has one authoritative home. Build instructions live in
CLAUDE.md. Module boundaries live in the module's own CLAUDE.md.
Everything else links — never restates. Redundancy is staleness
waiting to happen.

## 9. Clean room discipline

Public repo docs describe *what the interface does* — message formats,
protocol sequences, behavioral contracts. Never *how the binary
implements it*. No RVAs, no offsets, no decompiled snippets in docs/.
Raw RE artifacts belong in arena-notes, not here.

References to "client decompilation" in KDoc are acceptable as
provenance markers ("per client decompilation, the client expects X").
The doc describes the expected behavior, not the disassembly.

## 10. Changelog is a first-class artifact

CHANGELOG.md is maintained per-PR (add entry to `[Unreleased]`) and
finalized per-release. Domain-grouped sections (Protocol, Engine,
Launcher, Puzzles) over generic Added/Fixed. Contributor attribution
inline per-bullet.
