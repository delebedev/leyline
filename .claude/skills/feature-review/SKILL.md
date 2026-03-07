---
name: feature-review
description: Use when reviewing a completed feature branch for architecture, wire conformance, test coverage, business logic, and documentation hygiene — before merge or when asked to "review this feature".
---

## What I do

Thorough code review of a feature implementation. Reads all changed files, evaluates against project principles, produces a prioritized findings table. Not a pre-merge scan (that's hygiene) — this is an architectural and correctness review.

## When to use

- "Review the sealed event implementation"
- "Review this feature before merge"
- After completing a major feature, before PR
- When asked to assess code quality, test coverage, or architecture of a feature

## Process

### 1. Scope the review

```bash
git log --oneline main..<branch>        # what was done
git diff --stat main...<branch>         # what changed
```

Identify: modules touched, domain model additions, wire format changes, new tests.

### 2. Read all implementation files

Read every changed file — not just diffs. Understand the full shape before judging. Parallel-read independent files. Also read files that interact with the changes (callers, wiring layer).

### 3. Evaluate each section

Work through sections 3a–3g. Skip sections with zero findings.

#### 3a. Architecture & seam documentation

- Domain model quality (types, value classes, separation)
- Dependency direction — does coupling flow the right way? (frontdoor → domain only, matchdoor owns engine deps)
- Cross-boundary wiring (lambdas, callbacks, shared state)
- Scattered logic — same check in N places? Should be one authoritative source.
- **Seam KDoc audit** (principles-documentation rule 1): do classes at split points explain *why* the boundary exists?
- **Invisible constraints** (rule 3): ordering deps, threading contracts, state preconditions — documented in KDoc?

#### 3b. Single source of truth

- Duplicate checks or logic across files
- Duplicate documentation (two docs explaining same thing → delete copy, add cross-ref)
- Cross-references vs restated facts

#### 3c. Wire conformance

Compare output shapes against proxy recordings. Reference `docs/conformance/` reports if they exist.

- Missing fields the client may depend on
- Extra fields that diverge from real server
- Conditional emission logic (when to include/omit a field)
- Severity: does the client break, show wrong UI, or just ignore?

#### 3d. State machine & business logic

- State transitions — enforced or implicit? Can invalid transitions happen?
- Edge cases — limits, re-entry, already-complete states
- Idempotency — what happens on retry/duplicate calls?

#### 3e. Test coverage (by type)

Flag what's missing AND what type of test is needed:

| Type | What it covers |
|------|---------------|
| Unit | Service logic, pure functions, builders |
| Integration | Handler dispatch end-to-end (EmbeddedChannel), wire round-trip |
| Repository | Persistence round-trip, serialization of nested data |
| Negative | Error paths, invalid input, state violations |
| Conformance | Wire shape matches real server recordings |

Don't say "needs more tests" — say which type is missing and what scenario it would cover.

#### 3f. Code quality

- Duplication that should be extracted
- Types/classes in wrong package
- Leaked state (maps never cleaned up, resources not closed)
- Naming clarity

#### 3g. Documentation hygiene

Apply `docs/principles-documentation.md`:

- **Rule 1:** Seam classes have rationale KDoc?
- **Rule 2:** One source of truth per fact? No restated copies?
- **Rule 3:** Invisible constraints documented in KDoc of enforcing function?
- **Rule 4:** Standalone docs earned (cross-cutting) or should be KDoc?
- **Rule 5:** Implemented plans archived? Investigation notes dated?

### 4. Produce findings table

Every finding gets priority + effort + category. No vague "consider improving."

```
| Priority | Issue | Effort | Category |
|----------|-------|--------|----------|
| HIGH     | ...   | S/M/L  | arch/test/wire/logic/docs |
```

**Priority criteria:**
- **HIGH** — client breaks, wrong behavior, missing critical test, architectural smell that compounds
- **MED** — conformance gap client tolerates, missing negative tests, implicit state machine, duplication
- **LOW** — cosmetic, naming, cleanup, nice-to-have

### 5. Present the review

Structure:
1. **Architecture** — strengths first, then issues
2. **Wire conformance** — comparison table if relevant
3. **Test coverage** — what exists (strengths), what's missing (by type)
4. **Code quality** — only if findings exist
5. **Documentation hygiene** — only if findings exist
6. **Prioritized actions table** — the deliverable

### 6. Improvements pass (optional — on request)

When asked to "simplify" or "what can be improved", do a second pass focused on genuine simplifications. Not shortcuts — real improvements through better abstractions, methods, and types.

**What qualifies:**
- Scattered logic → single authoritative method/property on the type that owns the data
- Duplicated code blocks → extracted helper (only when truly identical purpose)
- Boolean flag combinatorics → simpler API (fewer params, enum, or always-emit)
- Implicit bugs (two calls that should share a value but generate independently)
- State machine limits that should be enforced but aren't
- Semantic misuse (calling a write method when you need a read)

**What does NOT qualify:**
- Renaming for taste
- Adding abstractions "for future extensibility"
- Refactoring working code that isn't duplicated or confusing
- Performance optimizations without evidence of a problem

**Output:** Same prioritized table format. Group tightly-coupled changes (touching same files) vs independent ones — this determines parallelization for implementation.

**Implementation:** Use parallel agents when changes are independent (different files, no shared state). Verify with compile + tests after integration.

## What this is NOT

- Not a pre-merge scan (that's `pre-merge-scan` — hygiene, not architecture)
- Not a line-by-line code review (focus on design, not style)
- Not a test runner — doesn't execute tests, just evaluates coverage
- Doesn't fix anything — produces findings for the human to prioritize
