# Engineering Stance — Leyline

This document captures how we think about work on this project. It's not a rulebook — it's a decision-making framework. Read it before planning non-trivial work. Reference it when making judgment calls.

## The project in one sentence

Leyline reimplements an opaque, undocumented wire protocol where the client is unforgiving and shortcuts compound. Correctness over speed, always.

## Recordings are the spec

The real server's behavior is the source of truth — not guesses, not docs, not what "makes sense." When implementing or fixing protocol behavior, the first question is always: what does the recording show? If there's no recording for this scenario, that's important context — flag it.

## Leverage-first prioritization

Not all work is equal. Prioritize work that **unlocks architectural understanding** — new message patterns, new interaction flows, protocol structures that make future work easier. If something is safe to omit and non-trivial to implement, it stays on the backlog. Ask: "Does this teach us something new about the protocol, or is it just filling in a known pattern?"

## Verify the premise before implementing

Never blindly trust an issue description. Before starting work, independently verify the claim. Read the relevant recording. Check the proto definition. Form your own view. If you disagree with the issue, say so and explain why. This gate prevents garbage-in-garbage-out.

When assessing system coverage, read the implementation before cataloging gaps. Annotation or protocol gaps are not the same as functional gaps — a system can work correctly without emitting every annotation type the client knows about.

## The plan is the deliverable (before code is)

For any non-trivial task, produce a **plan** before writing code. The plan must include:

- **Your understanding of the problem** (verified, not parroted from the issue)
- **What you looked at** — which files, recordings, docs informed your thinking
- **Proposed approach** — how you'll implement this, and why this way
- **Deliverables checklist** — explicit list of what "done" means for THIS task:
  - Code changes
  - Tests (and what they test — the right abstraction, not just coverage)
  - Conformance verification (recording diff, puzzle pass, etc.)
  - Visual proof (before/after screenshots) — mandatory if the change affects what the player sees
  - Doc/issue updates
- **What you're unsure about** — unknowns, risks, areas where you need input

The plan gets reviewed before execution. This is the primary control point. Feedback on plans is more valuable than feedback on code.

## Task types require different approaches

### Clear scope + known pattern → Execute with verification
The issue is well-specified, the fix is mechanical, the verification is objective. Verify the premise, implement, check deliverables. Examples: wrong constant values, missing field in a proto builder.

### Clear scope + unknown pattern → Research then propose  
You know what needs to happen but not how the protocol handles it. Study recordings and codebase first. Come back with findings and a proposed approach before implementing. Examples: new Req/Resp message types, unfamiliar interaction flows.

### Unclear scope → Explore and report
The issue describes a symptom or a broad area. The work is understanding the problem, not fixing it yet. Produce a findings doc, not a PR. Examples: "something's wrong with layer interactions," architectural gap analysis.

### Architecture/design → Collaborative
Decisions about structure, patterns, boundaries. These need human judgment. Present options with tradeoffs, not recommendations. The human decides.

## Definition of done depends on the change

| Change type | Verification required |
|---|---|
| Protocol field fix | Recording diff shows match (or improvement) |
| New message implementation | Puzzle passes in MatchHarness + Arena playtest |
| UI-affecting change (prompts, labels, annotations) | Before/after Arena screenshots — **mandatory** |
| Refactoring | Existing tests green + no behavioral change |
| Tooling/infrastructure | Demonstrate it works on a real example |

"Tests pass" is necessary but not sufficient. The verification mode must match the change type.

## How we learn: every PR and issue is a training sample

### On every PR, before merge:
- **What worked well** in the process (plan quality, verification, approach)
- **What would we do differently** — be specific. "The plan missed X" or "Should have checked recording Y first"
- **Anything discovered** that's relevant to other issues — update those issues now, don't lose the knowledge

### On every issue close:
- Was the spec good enough? If not, what was missing?
- Was the verification approach right?
- Did the agent need human help? Why — missing context, wrong approach, or genuinely hard judgment call?

### Periodically (every ~5 PRs):
- Review the PR learnings. Are there patterns?
- Distill patterns into updated guidance (this doc, CLAUDE.md, agent prompts)
- Flag process problems: token waste, unclear instructions, missing tools, repeated mistakes

## Enrichment is ambient

When working on task X and you discover something relevant to issue Y — update issue Y right then. Don't create a separate task to "go back and update issues." Context is perishable. Capture it when you have it.

## Architecture emerges, then gets consolidated

Sometimes you need to lay out the shape before touching code. Sometimes you need to do three small implementations and then the shape reveals itself. Both are valid.

The signal for "architecture first": you're about to touch something that affects how multiple future things work, and the approach isn't obvious.

The signal for "implement first, consolidate later": the changes are local and self-contained, but after a few of them you notice a pattern that should be extracted.

After a batch of implementation work, explicitly pause and ask: "Is there a refactoring or architectural consolidation that would make the next batch easier?" This is a distinct step, not an afterthought.

## Scope direction: big blocks vs. small fixes

`docs/systems-map.md` maps 12 player-facing systems, tracks GRE message coverage, and ranks gaps by severity. Use it to assess whether a task fills a known pattern or touches an unmapped system. When filing new issues, reference the relevant tier and update the map.

When an issue touches a gap documented in the systems map, reference the relevant tier and check if the work unlocks the broader system or just patches one instance. Tier 1 gaps (alternative casts, continuous effects infra) need design before implementation. Tier 2/3 gaps are mostly known patterns that can be filled incrementally.

## Code review taste

When reviewing (or self-reviewing) code, these things matter beyond correctness:

- **Are the tests testing the right abstraction?** A test that checks 15 specific field values is fragile. A test that checks "the message contains a valid zone transfer with the right category" is durable.
- **Simplicity.** If a solution feels complex, it probably is. Pause and ask if there's a cleaner way. (Skip this for trivial fixes — don't over-engineer.)
- **Implicit patterns.** The codebase has conventions that aren't all written down. When you see a pattern in existing code, follow it. When you're about to break a pattern, flag it and explain why.
- **Separation of concerns.** matchdoor owns protocol translation. Forge coupling stays in bridge/. Don't leak protocol details into game logic or vice versa.

## Process reflection

The agent should notice and flag when:

- A task took way more tokens than it should have (context problem? unclear spec?)
- The same kind of mistake keeps happening (missing rule? bad instruction?)
- A tool or playbook is missing that would save significant time
- Instructions are contradictory or ambiguous
- The verification approach didn't actually catch a real bug (false confidence)

Flag these observations in PR learnings. They're input to improving the system itself.

## What this document is not

This is not a complete set of operational instructions. For build commands, module layout, testing conventions, recording tools, and day-to-day workflows — see CLAUDE.md and the docs/ directory. This document is about *how to think*, not *what to type*.
