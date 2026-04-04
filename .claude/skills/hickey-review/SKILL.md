---
name: hickey-review
description: Structural design review through Rich Hickey's lens — values vs places, information vs mechanism, complecting, pure cores + effect shells. Use on-demand or when a module crosses complexity thresholds.
user-invocable: true
---

# Rich Hickey Review

Thought experiment: "what would Rich Hickey say about this code?"

Not a code review (reviewer agent). Not a conformance check (conformance agent). A **structural pressure review** — are the abstractions honest? Is complexity essential or accidental? What's drifting?

## When to run

- **Post-batch consolidation.** After 5+ PRs land in a module with significant churn.
- **Pre-major-work.** Before features touching 3+ architectural pressure points.
- **Threshold crossing.** File crosses ~800 LOC, pattern count exceeds documented limits.
- **On-demand.** User asks, or architect agent flags drift.

## Inputs

Required: module path (e.g., `matchdoor/src/main/kotlin/leyline/game/`).

Agent gathers autonomously:
- LOC counts for files over 300 LOC
- Recent git log since last review (check dates in findings.md)
- Files that grew >20% since last review
- Prior findings from `findings.md` and wins from `wins.md` in this directory

Optional: focus questions (e.g., "has purity crept back?", "is the state machine still honest?").

## Method

For each finding:

1. **What the code does now.** Concrete — file, line ranges, call patterns.
2. **Essential or accidental?** The core Hickey question. Essential complexity serves the domain. Accidental complexity serves the implementation.
3. **If accidental:** proposal, priority, blast radius, what it unblocks.
4. **If essential:** why it's load-bearing, what to protect.

## Output

Update two files in this skill directory:

- **findings.md** — open items only. Close resolved items (move to wins if pattern-worthy, drop otherwise). Add new findings with date. Each item: status, priority, context, proposal.
- **wins.md** — patterns confirmed working. The immune system. Cite these in PR reviews when someone proposes undoing them.

Write a summary to the conversation. No separate notes doc needed — findings.md IS the living record.

## Feedback loop

- **High priority** → bead (p1), work next session
- **Medium priority** → bead (p2-3), pick up on next file touch
- **Low priority** → tracked in findings.md only, drop after 3 reviews if unchanged
- **Wins** → cited in PR reviews to prevent regression

## Scope

One module or sub-package per review. "The whole codebase" produces nothing actionable.
