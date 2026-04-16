---
summary: "How to document leyline: rationale at the seam, one source of truth per fact, frontmatter on every standalone doc, and no orphaned copies."
read_when:
  - "writing or updating documentation"
  - "deciding where to put a new piece of knowledge"
  - "adding frontmatter to a new doc"
---
# Documentation Principles

Rules for documenting a codebase that bridges a complex rules engine to an external wire protocol.

## 1. Rationale at the seam

The hardest thing to re-derive is *why* a design split exists. Put that in the KDoc of the class at the split point — not in a standalone doc someone has to know exists.

A class header explaining why `ClientAutoPassState` is separate from `PhaseStopProfile` is a decision record that lives exactly where a reader needs it. A standalone ADR in `docs/decisions/` saying the same thing is a copy that rots.

**Good:** `"Extracted from MatchSession for independent testability."` on `CombatHandler`. Read the class, understand the boundary.

**Bad:** a design doc titled "Combat Handler Extraction" that explains the same thing but lives three directories away and references line numbers that moved.

## 2. One source of truth per layer

Every fact has one authoritative home. Other layers cross-reference it; they never restate it.

| Layer | Authority |
|---|---|
| Engine behaviour | Engine source (e.g. `forge/.../PhaseHandler.java`) |
| Bridge design + invariants | KDoc on the bridge class; `bridge-threading.md` for cross-class contracts |
| System shape | `architecture.md` |
| How to debug a specific failure class | The playbook that covers it |

When two docs explain the same thing, one will drift. Delete the copy, add a cross-reference.

## 3. Document invisible constraints

The most expensive undocumented knowledge is the constraint the type system does not enforce: ordering dependencies, threading contracts, "build state before actions because instanceId reallocation happens during diff-building."

These cost hours to debug when missing and two lines to explain when present. They belong in the KDoc of the function that enforces them.

Signs you are looking at an invisible constraint:

- Swapping two calls breaks something non-obviously.
- A `synchronized` block or lock whose scope is not self-evident from the caller.
- A field that must be set before another field is read.
- An annotation that must appear before another annotation in a list.

## 4. Standalone docs for cross-cutting; KDoc for per-class

A doc earns standalone status when it spans many classes and has no single natural home: protocol translation tables, mechanic catalogs, diagnostic playbooks, wire format specs.

A doc about how one class works is that class's KDoc. The moment it is external, it cannot be updated in the same commit as the code change, and it will not be.

Test: if renaming the class would make the doc's title wrong, it should be a KDoc.

## 5. Plans are ephemeral

A planning document that survives the work it planned is noise — either it restates code that now exists, or it describes a future that never shipped. Delete or archive plans when the work they describe lands. Git history preserves everything.

Investigation journals (deep-dive notes from tracing one behaviour) are valuable research but they are not reference. If kept, label them as dated investigation notes so nobody mistakes last quarter's observations for current specification.

## 6. Agent-first, human-readable

`CLAUDE.md` is the primary entry point for contributors — agent and human alike. Standalone docs serve agents first; humans benefit from the same clarity. Write for a reader who has 200K tokens of context and needs to decide in one line whether a doc is relevant right now.

## 7. `read_when` frontmatter on every standalone doc

Every file in `docs/` gets YAML frontmatter:

```yaml
---
summary: "One-line purpose of this document."
read_when:
  - "condition when the doc should be read"
  - "another condition"
---
```

`summary` is for scanning. `read_when` declares relevance conditions. An agent should never need to read all of `docs/` — it scans summaries, matches conditions to its current task, and reads selectively.

## 8. One canonical location per fact

Build instructions live in `CLAUDE.md`. Module boundaries live in the module's own `CLAUDE.md`. Architectural shape lives in `architecture.md`. Everything else links — never restates. Redundancy is staleness waiting to happen.
