# Hickey Review — Wins

Patterns confirmed working. Cite in PR reviews when a change threatens to undo them.

## matchdoor

**gather/compute/apply phases in buildFromGame** (confirmed 2026-03-22)
Three-section structure. COMPUTE is genuinely mutation-free (one pragmatic exception: `reallocInstanceId` in append-only registry, parameterized for tests). APPLY is small. Has held through mana payments, effect tracking, persistent annotations, search. Protect this boundary — when new code sneaks bridge reads into COMPUTE, push back.

**Pure overloads with bridge-delegating wrappers** (confirmed 2026-03-22)
`detectZoneTransfers`, `combatAnnotations`, `mechanicAnnotations` all have pure versions taking function params, wrapped by bridge-param convenience overloads. `PurePipelineTest` exercises pure paths. Right architecture for growing the annotation system.

**PersistentAnnotationStore.computeBatch — gold standard** (confirmed 2026-03-22)
Takes immutable snapshot, returns `BatchResult`, caller applies. Five lifecycle steps all pure operations on the active map. This is the reference implementation for how new pipeline stages should work.

**PendingClientInteraction sealed interface — state machine as data** (confirmed 2026-03-22)
Variants carry exactly the information needed to resume the flow. Created in one method, consumed in another. No enum + mutable fields anti-pattern. Don't flatten this.

**GsmFrame value type** (confirmed 2026-03-22)
Plain data extracted from Game once, threaded as value. Eliminates repeated seat/phase derivation across BundleBuilder.

**Shared MessageCounter via construction** (confirmed 2026-03-22)
One counter passed at creation time. No sync dance, no seeding. Clean lifecycle.

**1:1 method-to-client-message mapping in TargetingHandler** (confirmed 2026-03-22)
One method per client message type maps to the protocol — right seam for a protocol adapter. Preserve this even when extracting message construction out.
