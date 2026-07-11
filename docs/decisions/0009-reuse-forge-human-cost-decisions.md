---
summary: "ADR: reuse Forge's human cost visitor and override controller choice hooks for each frontend."
read_when:
  - "changing HumanCostDecision, CostDecision, or PlayerController cost-choice hooks"
  - "adding or migrating sacrifice, discard, exile, tap, or other interactive cost payments"
  - "deciding whether cost interaction belongs in Forge, a controller, or the prompt bridge"
---
# ADR 0009: Reuse Forge Human Cost Decisions

## Status

Accepted for incremental implementation.

## Context

Forge's `HumanCostDecision` and Leyline's `CostDecision` independently implement
the same set of cost visitors. Both select legal candidates, handle automatic
cases, ask a player to choose, and return a `PaymentDecision`. The duplication
makes Leyline a second authority for game rules that Forge already owns.

The payment pipeline already has the right outer shape:

```text
CostPart.accept(decision maker) -> PaymentDecision -> payAsDecided
```

The missing seam is inside `HumanCostDecision`. Some visitors call
`InputSelectCardsFromList`, `InputChooseValue`, `SGuiChoose`, or other desktop UI
classes directly. Others already use overridable `PlayerController` methods,
including `chooseCardsForCost` and `confirmPayment`.

Leyline already subclasses Forge's human controller and overrides those choice
methods to present protocol prompts. Reusing that boundary avoids introducing a
second interaction framework.

## Decision

Make Leyline's `CostDecision` extend Forge's `HumanCostDecision`.

Initially, retain every Leyline visitor override. This inheritance step changes
no behaviour; it only makes deletion possible.

Then migrate one cost family at a time:

1. Refactor the corresponding `HumanCostDecision` visitor to perform shared
   candidate filtering and automatic decisions in Forge.
2. Route only the human choice through an existing overridable
   `PlayerController` method.
3. Preserve the desktop controller's current input and GUI behaviour behind
   that method.
4. Verify Leyline's controller can answer the same choice through its prompt
   bridge.
5. Delete the Leyline visitor override so it inherits Forge's implementation.

Add a new controller method only when an existing method cannot faithfully
express a proven choice shape. Such methods must be narrow and semantic, for
example a weighted card selection or a counter-type-and-amount choice. Do not
add a universal `CostChoicePort` or generic property bag in anticipation of
future visitors.

Start with sacrifice. It is an ordinary card selection already close to the
existing `chooseCardsForCost` contract. Follow with exact-count card-selection
families such as discard, tap, return, unattach, and exile. Migrate weighted,
aggregate, and multi-stage choices only after the ordinary shape is proven.

Keep the visitor and payment flow synchronous. `CostPayment`,
`CostPart.accept`, `PaymentDecision`, and `payAsDecided` remain unchanged.

## Ownership Boundary

Forge owns:

- legal candidate calculation and restrictions;
- automatic and impossible decisions;
- cost-family semantics;
- construction of `PaymentDecision`.

The controller choice method owns:

- presenting one typed choice to a human;
- waiting for and validating the frontend answer against the supplied choices;
- returning only the selected Forge values.

The desktop human controller continues to implement the choice with Forge
inputs. Leyline's human controller continues to implement it through the prompt
bridge. Protocol request construction and response lifecycle stay outside
Forge's cost visitor.

ADR 0005's semantic plans are implemented, but they are not the target
architecture. They currently separate `CostDecision` from prompt metadata while
the duplicated visitors remain. Delete each family plan with the Leyline visitor
that consumes it.

Ordinary families should delegate directly from the shared Forge visitor to the
controller choice method. Protocol semantics and weighted-selection metadata
belong in Leyline's controller adapter or in a narrowly typed choice hook when
the generic hook cannot carry them. Do not replace `CostDecisionPlanner` with a
new generic planner.

## Constraints

- Do not intercept `InputQueue` or adapt concrete `Input` instances. That seam
  is desktop lifecycle plumbing, loses the originating `CostPart` semantics,
  and does not cover direct GUI calls.
- Do not move protocol types or prompt lifecycle state into Forge.
- Do not retain mutable `SpellAbility` or card references beyond the synchronous
  choice call.
- Do not force `AiCostDecision` through the human interaction path. AI payment
  timing and decision policy are separate.
- Do not remove a Leyline override until desktop and Leyline behaviour for that
  family are both characterized.

## Verification

Each migrated family needs shared characterization cases covering:

- legal candidate filtering and minimum/maximum selection;
- automatic selection and refusal paths;
- desktop controller delegation;
- Leyline prompt projection and answer translation;
- the resulting `PaymentDecision` and paid game state.

The first slice is complete when sacrifice uses one Forge visitor, both
frontends retain their current interaction, and Leyline's sacrifice visitor is
deleted.

The migration is complete when `CostDecision` contains only genuinely
bridge-specific behaviour. Delete it if no such behaviour remains. Delete
`CostDecisionPlanner` when its final family plan loses its consumer.

## Consequences

- Cost rules have one authority in Forge.
- Leyline deletes visitors incrementally instead of replacing both copies with
  a third abstraction.
- The corresponding ADR 0005 plan and planner test are deleted with each
  migrated visitor; they are not preserved as an extra protocol layer.
- Desktop input classes may remain inside `HumanCostDecision` until a migrated
  family proves a controller seam; no all-at-once GUI extraction is required.
- The controller API grows only for demonstrated interaction shapes.
- Forge and Leyline changes for each family must land atomically because the
  override removal depends on the new controller seam.

## Alternatives Considered

- **Intercept Forge inputs** — rejected because concrete inputs combine desktop
  UI lifecycle with decisions and do not carry a stable cost contract.
- **Introduce a universal cost planner and frontend port first** — rejected
  because it creates a third model before the repeated choice shapes are known.
- **Use `HumanCostDecision` unchanged** — rejected because its direct desktop UI
  calls cannot be answered by Leyline.
- **Keep parallel visitors** — rejected because it preserves duplicated game
  rules and makes every new cost mechanic a two-implementation change.
