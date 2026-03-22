---
name: plan-work
description: >
  Use when planning non-trivial work: picking up issues,
  designing approaches, deciding scope, or creating PRs.
  Also use when asked to plan, propose, or assess an issue.
---

Before writing any code, produce a plan.

## Required reading

Read `docs/engineering-stance.md` and `docs/systems-map.md` first. Apply the stance's principles. Use the systems map to understand what tier the work falls in and what's already tracked.

## Plan structure

1. **Premise verification** — independently verify the issue's claim.
   What did you look at? (recordings, proto, code) Do you agree with the issue?
2. **Context orientation** — which module, which files, which recordings matter?
3. **Approach** — what you'll do and why this way
4. **Deliverables checklist:**
   - [ ] Code changes
   - [ ] Tests (what are they actually testing?)
   - [ ] Verification mode: (recording diff | puzzle pass | screenshots | manual)
   - [ ] Doc updates needed? (especially if touching tooling, mechanics, or wire format)
   - [ ] Related issues to enrich?
5. **Unknowns** — what you're unsure about, where you need input
6. **Leverage assessment** — does this unlock new patterns or is it filling in a known one?

## Gates

- If change affects player-visible UI → screenshots are mandatory deliverable
- If touching tooling/just recipes → doc update is mandatory deliverable
- If scope is unclear after reading → this is a research task, not an implementation task. Produce findings, not a PR.

## After merge

Add to PR description:
- What worked well in the process
- What we'd do differently
- Anything discovered relevant to other issues (and update those issues)
