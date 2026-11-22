# Review guide

What to flag on a Leyline pull request, and what to leave alone. It applies to
every reviewer, human or tool. [AGENTS.md](AGENTS.md) owns the build, test,
proof-scope and content rules; this file says how to judge a change against
them.

Leyline is the shared gameplay engine behind its protocol heads: Forge bridge,
match runtime, state projection and protocol emission. Priority order is
playable behavior, then protocol fidelity, then broader conformance.

## How to review

- Treat the PR's **What changes** and **Evidence** sections as claims, not
  proof. Each Claim needs a repository handle (test, puzzle, acceptance
  scenario, conformance contract) that would fail without the change and ran
  on the reviewed commit, or the gap is disclosed. A claim that has no handle
  and is absent from **Not verified** is a finding.
- When the linked issue lists **Done when**, the PR has one Claim per item it
  finishes; tick each Claim against its item. Items under **Left out** are
  deliberately excluded. `Closes` belongs only on the PR that finishes the
  issue; partial or stacked work uses `Refs`.
- When the change implements externally defined behavior (card rules text,
  Comprehensive Rules, Forge behavior), check it against that source. Issue
  text, PR prose and tests often repeat one paraphrase, so their agreement
  proves nothing. Restate the source and the code as one concrete before/after
  and compare them. Small wording changes invert behavior: actor versus target,
  "can't act" versus "can't be acted on", may versus must.
- Read whole changed files plus their callers, twins and tests. Do not infer a
  bug from a suspicious shape; trace the failing path and state the consequence.
- Read the `AGENTS.md` nearest each changed file, then the authority for the
  touched owner:
  - module boundaries and ownership: `docs/architecture.md`,
    `docs/principles-design.md`, `docs/decisions/`;
  - engine and threading: `engine/AGENTS.md`, `docs/bridge-threading.md`,
    `docs/forge-api-concepts.md`;
  - tests: `engine/src/test/kotlin/leyline/AGENTS.md`;
  - documentation: `docs/principles-documentation.md`;
  - protocol obligations: `docs/protocol-conformance.md` and
    `conformance/contracts/`.

## Severity

- **Blocking:** fix before merge. Security exposure, data loss and a broadly
  broken shipped path are the extreme of this level, not a separate one.
- **Should fix:** a concrete cost to proof, change cost or clarity. Report it;
  the author may defer it with a stated reason.
- Nothing below that. Where a tool has Important and Nit levels, Blocking is
  Important and Should fix is Nit.

Each finding names the file and line, the failing scenario, its consequence and
the smallest credible fix. One root cause gets one finding. Zero findings is a
valid review.

## Blocking

- **Wrong behavior.** Logic errors, null paths, double processing, leaked
  state. Behavior that contradicts the source rules or the PR's Claim. Requested
  behavior that is missing or only partly delivered and not listed under Left
  out.
- **Lifecycle holes.** Retry, duplicate submission, already-resolved state,
  timeout, cancellation, teardown and late responses. One owner controls each
  pending interaction; a failure must not release or mutate another
  interaction's lifecycle. State that crosses engine, coordinator, session or
  transport follows `docs/bridge-threading.md`.
- **Twins that drift.** A change breaks the contract a matched pair shares and
  leaves the counterpart behind: checked and permissive builders, fast and slow
  paths, encode and decode, request and response, forward and inverse maps. A
  one-sided change that keeps or restores the shared contract is fine.
- **Boundary breaks.** Dependencies against the module direction that
  `PackageLayeringTest` enforces. Transport identity inside the engine. Live
  Forge objects escaping the bridge instead of immutable projected values. Game
  rules re-implemented outside Forge.
- **Protocol drift.** A new or changed prompt, action, annotation or object
  matches its siblings in field presence, identity and instance-id
  reallocation, ordering, lifecycle and retirement, not only in type name.
  Loosening a conformance contract to make it pass is blocking.
- **Disabled stream invariants.** A test that turns off a
  `ValidatingMessageSink` invariant without saying why that invariant is out of
  scope.
- **Unproved load-bearing claim.** The only proof of a Claim cannot fail for the
  claimed reason: the assertion is never reached, an empty collection is
  iterated, or a missing fixture skips silently. A feature that does not work
  end to end is not finished, whatever the tests say.
- **Content rules.** Anything that breaks the AGENTS.md public-repository rules,
  in code, docs, commit messages or PR text.

## Should fix

### Scope and seams

- A card- or scenario-specific fix where the shared seam stays broken. Ask for
  sibling coverage or an explicitly bounded scope.
- Unrelated changes that hide a second product decision or make the PR hard to
  review. Unrequested code is fine when it adds no risk.

### Tests as proof

Name the regression a missing or weak test would catch. Never write "needs more
tests".

- Stale enumerations: a parity test loops over a hand-written list that omits
  the new case.
- Migration loss: calls move, old assertions disappear, the test name stays
  green.
- Missing negative or lifecycle case on the actual regression path; a
  bidirectional fix tested in one direction; order-sensitive behavior asserted
  as unordered contents.
- Wrong lane for the claim, per the test guide: a session test for a
  projected-state check, or a unit test claiming an integration outcome.
- Redundant tests guarding the same regression at the same layer while a
  distinct path stays unproved. Trade one for the other.
- Tests coupled to implementation structure, heavy mocks, or setup far larger
  than the assertion.

### Codebase coherence

Before accepting a new helper, type, wrapper or special case, find the existing
owner of that responsibility. Flag:

- a capability the repository already has, re-implemented under a new name;
- one rule, protocol semantic or state transition encoded in two owners;
- a wrapper, interface, factory or option with one caller and no invariant to
  own;
- defensive or compatibility branches for states no caller produces.
  Compatibility belongs only at real version, persistence or protocol
  boundaries;
- a half-finished migration that keeps old and new paths or terms alive,
  including tests that keep the old path alive;
- a caller-side symptom fix that bypasses the owner of the rule;
- API or visibility wider than current callers need.

Similar syntax alone is not duplication. Ask whether the codebase would get
simpler or harder to change if the next hundred changes followed this pattern.

### Documentation

Apply `docs/principles-documentation.md`: an authority the change made
inaccurate, a missing rationale at a seam whose constraint the types do not
enforce, a plan that outlived its work, or comments that narrate history or
restate the code.

## Do not report

- Anything CI enforces: formatting, ktlint, detekt, docs links, package
  layering.
- Naming preference, import order, speculative performance, future
  extensibility, or a design-smell label without a concrete cost.
- Issues the change neither introduces nor exposes.
- Missing live-client or full-game proof when the AGENTS.md proof scope does
  not require it.
- Generated or third-party content: `forge/` submodule internals (review only
  the pin change and its stated reason) and
  `proto/src/main/proto/messages.proto` (review `proto/rename-map.sed`
  instead).
- Who or what wrote the code. Judge the change, not its authorship.
