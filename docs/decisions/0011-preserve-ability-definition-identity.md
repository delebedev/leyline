---
summary: "ADR: preserve stable ability definition identity separately from runtime invocation identity across events, stack projection, and prompts."
read_when:
  - "changing AbilityRegistry, GameEventCollector, StackAbilityGrpIdResolver, or abilityGrpId resolution"
  - "projecting triggered or activated abilities onto the stack"
  - "building targeted keyword prompts or TargetSpec annotations"
  - "adding Forge SpellAbility copy or event identity fields"
---
# ADR 0011: Preserve Ability Definition Identity

## Status

Accepted for incremental implementation.

## Context

Leyline needs two different facts about a Forge ability:

- which runtime invocation is currently on the stack;
- which stable ability definition maps to the client's `abilityGrpId`.

Those facts currently share names and integer fields even though they have
different lifetimes. Forge assigns a new `SpellAbility.id` to most copied
abilities. That runtime id correctly distinguishes repeated trigger firings and
stack entries, but it no longer matches the original `SpellAbility` id indexed
by `AbilityRegistry`.

Priority action projection now retains the exact offered ability and its client
identity. Before executing an activated ability, `ActionPerformer` records the
offered `forgeAbilityId -> abilityGrpId` pair. Execution may then copy the
ability and assign a different runtime id, so later event and stack consumers
cannot reliably use that pair.

Several consumers compensate independently:

- `GameEventCollector` recognizes Backup and Mentor from trigger-description
  prefixes, then matches activated abilities by API and rendered cost;
- `StackAbilityGrpIdResolver` repeats the same keyword recognition and
  activated-ability shape matching;
- `RequestBuilder` recognizes Backup and Mentor again while constructing target
  prompts;
- `TargetingCoordinator` recognizes Mentor again for `TargetSpec` metadata.

These are repeated attempts to recover definition identity after the exact
Forge context has been replaced by a runtime copy.

## Decision

Represent ability definition identity separately from runtime invocation
identity.

Conceptually:

```text
AbilityRuntimeRef
  source Forge card id
  runtime SpellAbility id

AbilityDefinitionRef
  trait kind: SpellAbility | Trigger | StaticAbility
  stable Forge definition id

ProjectedAbilityIdentity
  runtime ref
  definition ref
  client abilityGrpId
  structured keyword family, when prompt presentation needs it
```

The exact type names may follow existing package vocabulary. The separation is
the invariant.

Forge ability copies preserve a stable definition reference while continuing
to mint unique runtime `SpellAbility.id` values. Triggered abilities use their
originating `Trigger.id`; static abilities use `StaticAbility.id`; spells and
activated abilities use the original `SpellAbility` definition id.

`AbilityRegistry` is the authority for mapping a structured definition
reference to client ability identity for one card. Resolution may return both
the card-specific client row and a structured keyword family when the protocol
shape needs both. It must not compare description text, rendered costs, or
loosely similar ability shapes.

Resolve identity at each lifecycle boundary where the exact Forge ability is
available, then carry the resulting value:

- event creation records identity on immutable event facts;
- pending stack context associates one runtime invocation with that identity;
- stack projection consumes the pending identity instead of rediscovering it;
- prompt creation stores identity on the pending prompt and target record;
- request and annotation builders consume the stored identity without
  inspecting a live `SpellAbility` for classification.

## Identity And Lifetime

Runtime and definition ids are not interchangeable:

- the runtime id identifies one invocation and remains the key for synthesized
  stack object identity, event correlation, and resolution lifecycle;
- the definition reference identifies one ability definition and remains the
  key for `AbilityRegistry` lookup;
- the client `abilityGrpId` is presentation identity and does not identify a
  Forge object;
- the source Forge card id identifies the engine card and is translated to a
  client instance id only during frame projection.

Back-to-back firings of one trigger therefore share a definition reference and
client row but retain distinct runtime ids and stack object ids.

Identity values may outlive the callback or event that produced them. Mutable
`SpellAbility` objects must not be retained for later reconstruction.

## Event And Stack Boundary

`GameEventCollector` resolves one identity when a spell or ability enters its
event lifecycle. `PendingStackAbilityRegistry` retains that identity under the
runtime id until resolution. `GameEvent.SpellCast`, `SpellResolved`, and related
facts carry the already-resolved fields needed by projection.

Stack snapshots first consume this runtime-keyed identity. Test-only or wrapped
stack entries that bypass the normal event lifecycle may call the same
structured registry resolver directly. Such a fallback must remain explicit;
it must not restore API-plus-cost matching.

This removes activated shape matching and Backup/Mentor description checks from
both `GameEventCollector` and `StackAbilityGrpIdResolver`.

## Prompt Boundary

Prompt identity is fixed while the exact callback `SpellAbility` is available.
`PromptRequest` and `PendingTarget` carry the resolved identity needed by
`RequestBuilder`, `TargetSpecContributor`, and stack-surrogate selection.

Ability identity does not decide prompt policy. Prompt semantics still decide
the protocol request family, prompt id, target restrictions, and response
mapping. Structured keyword family may select a keyword-specific presentation
shape, but builders must not infer that family from description text.

Backup demonstrates why both values can matter: its outer ability row may be
card-specific while the target group uses the shared Backup family id. The
resolver supplies those structured facts once; request construction only
projects them.

## Relationship To ADR 0010

[ADR 0010](0010-bind-priority-actions-at-projection-source.md) remains
authoritative for priority action candidates, executable commands, and pending
window ownership. Its action offers already preserve identity at the action
source. This ADR continues the identity contract after Forge begins execution
and across independent event, stack, and prompt lifecycles.

The action catalog should adopt the shared identity value when useful, but this
work must not reopen action candidate enumeration or response binding.

## Migration

1. Characterize current identity across ability copy, event, stack, prompt, and
   `TargetSpec` lifecycles.
2. Add a stable definition reference to Forge ability copies and the narrow
   event/view surfaces that cross into Leyline.
3. Extend `AbilityRegistry` with one structured resolution path for spell,
   trigger, and static definition references.
4. Move event and stack projection to runtime-keyed resolved identity; delete
   activated shape matching and keyword description checks there.
5. Store resolved identity on pending prompts and target records; delete Forge
   description checks from request and annotation construction.
6. Remove obsolete identity maps, helpers, and fallback branches after their
   final consumer is gone.

Migration may proceed event/stack first and prompts second. A family must not
retain both structured and heuristic production resolution after cutover.

## Required Invariants

- Every projected stack ability retains a unique runtime invocation id.
- Runtime copies of one definition resolve to the same client ability row.
- A definition reference is typed; trigger, spell, and static ids are not
  searched as an unqualified integer across all maps.
- Event, stack, prompt, and `TargetSpec` projection agree on client ability
  identity for the same invocation.
- Prompt presentation consumes structured identity but remains separate from
  identity resolution.
- Missing identity is explicit and observable; it never silently selects a
  description or shape-based guess.

## Verification

- Forge copy tests prove that runtime ids change while definition identity is
  preserved across copy chains.
- Registry tests cover spell, trigger, static, keyword, and interleaved ability
  slots.
- Two activated abilities with the same API and rendered cost resolve by
  definition, not list position or shape.
- Backup and Mentor integration tests assert one identity across event facts,
  stack objects, target requests, and `TargetSpec` annotations.
- Repeated firings of one trigger share a client row but receive distinct stack
  object ids.
- Source searches find no Backup/Mentor description-prefix recognition or
  activated API-plus-cost matching in the migrated event, stack, and prompt
  paths.

## Consequences

- Ability identity is resolved at the strongest available Forge context.
- Runtime lifecycle and client presentation can evolve independently without
  overloading one integer id.
- Adding a keyword prompt no longer requires parallel recognizers in event,
  stack, request, and targeting code.
- The change requires a small Forge identity seam plus Leyline migration, but
  removes repeated shape recognition rather than centralizing it.

## Alternatives Considered

- **Use runtime `SpellAbility.id` everywhere** — rejected because copied
  abilities receive new ids that are absent from the card definition registry.
- **Use definition id as the stack key** — rejected because repeated
  invocations of one definition need distinct stack identities.
- **Move recognizers into one helper** — rejected because it centralizes the
  guess while preserving loss of source identity.
- **Create a universal ability-projection service** — rejected because identity
  mapping, prompt policy, stack object construction, and annotation emission
  have different responsibilities.
- **Retain mutable `SpellAbility` references** — rejected because stack, event,
  and prompt values can outlive the safe mutation window of the originating
  object.
