---
summary: "Bounded protocol contracts over deterministic acceptance scenarios, with mutation checks and a CI lane."
read_when:
  - "running or extending protocol conformance checks"
  - "deciding which lifecycle assertions can replace duplicate session tests"
---
# Protocol conformance

Run `just test-conformance` for authored protocol obligations over the emitted
GRE stream. The extended CI job runs the same task and propagates failures.
JUnit output lives under `engine/build/test-results/testConformance/`.

Conformance runs immediately after Build in Extended, before the longer
integration, simclient, and acceptance suites. It shares Forge setup with those
suites and executes its own scenarios, without depending on another task's
output. A failure fails the required Extended check. Its check name stays stable
for branch rules.

[`ProtocolConformanceTest`](../engine/src/test/kotlin/leyline/behavior/conformance/ProtocolConformanceTest.kt)
reuses `MatchdoorAcceptanceExecutor` and the existing scenario YAML:

| Scenario | Protocol obligations |
|---|---|
| `warmup/land-spell-face` | Cast and resolution framing, source and target identity, exact damage count and typed detail values, object reallocation, target-row retirement. |
| `mechanics-warmup/reconfigure-attach-unattach` | Ability and targeting order, target-group cardinality and bounds, prompt flags, source binding, submitted target identity, target-row retirement. |

Scenario YAML owns gameplay intent. The Kotlin assertions own protocol
expectations. Changing an emitted field requires checking its protocol meaning
before changing the expectation. A green gameplay scenario alone does not justify
loosening a protocol assertion.

Runtime card and ability identifiers use the Forge catalog. Contracts relate
those identifiers within one interaction rather than pinning catalog-dependent
numbers. Stable protocol values, detail keys, counts, and ordering are explicit.
This suite proves the listed interactions, not catalog-wide identity parity or
live-client presentation.

Each contract also rejects mutations of the same project-generated output:
duplicate or wrong damage, premature retirement, empty target groups, incorrect
source or undo flags, and missing retirement. Mutation checks add no extra games.

Keep mechanism, concurrency, cancellation, transport, and head presentation
tests. Remove a duplicate lifecycle test only after a contract proves every
distinct obligation that test protects. Add a general contract interpreter only
when native assertions become a demonstrated maintenance cost.
