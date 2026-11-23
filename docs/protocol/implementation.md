---
summary: "Implementation map: Leyline owner, status and known exceptions for each protocol entity, plus the shapes Leyline defines itself."
read_when:
  - "checking whether Leyline meets a protocol page's Contract today"
  - "reviewing emission that differs from a protocol page"
  - "adding or changing a Contract statement or a Leyline protocol owner"
---
# Implementation map

This page records how Leyline meets the [protocol reference](README.md). It is
the only place protocol pages' Leyline status appears. One row per entity names
its owner and status. Exceptions list only the statements or branches where
Leyline does something other than the page describes. Everything not listed as
an exception is intended to hold.

Proof coverage is not stored here. `just docs-lint` computes it from
`protocol:` citations in tests and conformance contracts and lists statements
no proof cites yet.

## Status vocabulary

Entity status:

| Status | Meaning |
|---|---|
| `implemented` | Leyline emits or accepts every branch with Contract statements, with no exceptions. |
| `partial` | Leyline implements the entity; at least one exception applies. |
| `compat-only` | Leyline uses the entity only through Leyline-defined shapes or deliberate alternatives. |
| `absent` | Leyline never emits or accepts the entity. |

Exception kinds:

| Kind | Meaning |
|---|---|
| `diverges` | Leyline emits or accepts something the Contract does not allow. A defect. |
| `compat` | A deliberate alternative: Leyline takes a different shape to keep play working where the contract is unknown or cannot be met. Not a defect; reviewers do not report it. |
| `absent` | Leyline never produces the branch or statement. A gap, not a wrong shape. |

## Entities

| Entity | Owner | Status |
|---|---|---|
| [SelectNReq](prompts/SelectNReq.md) | `CardSelectWindowMaterializer`, `StaticChoiceWindowMaterializer`, `RevealChoiceWindowMaterializer`, `SelectNEnvelope`, `SettledPromptOwner` | `partial` |
| [ReplacementEffect](annotations/ReplacementEffect.md) | `AnnotationBuilder.replacementEffect`, `BlockingInteractionMaterializer.etbPayLifeOptional`, `EtbReplacementEffectKind` | `partial` |

## Exceptions

Each exception is written so it can be filed as an issue unchanged: what the
contract says, what Leyline does, and the owner to change.

### SelectNReq

- **`absent`** — [SelectNReq.trigger-order](prompts/SelectNReq.md#SelectNReq.trigger-order),
  [SelectNReq.trigger-order-arbitrary](prompts/SelectNReq.md#SelectNReq.trigger-order-arbitrary)
  - Contract: simultaneous triggers controlled by one player are ordered
    through a `SelectNReq` with `TriggeredAbility` and `Stacking` over the
    pending abilities, and the client may ask for automatic ordering.
  - Leyline: never emits the trigger-ordering family. Forge's existing order
    is kept without a prompt, so the player cannot order their triggers. The
    owner's comment says the protocol has no prompt for this decision, which
    the contract contradicts.
  - Owner: `ClientGuiGame.order`
- **`diverges`** — [SelectNReq.mana-color](prompts/SelectNReq.md#SelectNReq.mana-color) *(provisional statement)*
  - Contract: a color choice made while a mana ability resolves uses
    `ManaFromAbility` with `Resolution`.
  - Leyline: every static choice uses `Resolution` for both contexts,
    including the color choice of a triggered mana ability such as Fertile
    Ground's additional mana.
  - Owner: `StaticChoiceWindowMaterializer.buildRequest`

### ReplacementEffect

- **`absent`** — [Variants](annotations/ReplacementEffect.md#variants)
  - Contract: replacement events carry transient rows, and pending
    replacements carry persistent rows.
  - Leyline: emits `ReplacementEffect` only on the pay-life entry branch. No
    other replacement effect produces a row on either rail.
  - Owner: `BlockingInteractionMaterializer.etbPayLifeOptional`
- **`compat`** — [Open points](annotations/ReplacementEffect.md#open-points), other choices made before a permanent enters
  - Contract: unknown whether choices other than the pay-life entry choice are
    bracketed by a persistent row.
  - Leyline: publishes those choices, for example an optional copy entry, as an
    `OptionalActionMessage` sourced from the host card, with no
    `ReplacementEffect` row.
  - Owner: `PlayerController.confirmReplacementEffect`

## Leyline-defined shapes

Shapes Leyline emits that no protocol page defines. They are normative for
Leyline's heads only. A reviewer checks a change against the description here,
not against a protocol page.

### Named label choice

A Forge choice between labels that has no client prompt, such as a named
option or a finite list of numbers, is sent as a `SelectNReq` with
`Resolution` for both contexts, `listType=Dynamic`,
`idType=PromptParameterIndex` and one-based `ids`. The inner prompt carries
one `NonLocalizedString` parameter named `Choice` per option, aligned with
`ids`. The response selects the option by its index.

- Owner: `StaticChoiceWindowMaterializer.buildRequest`, `StaticChoiceWindowCapture`

## Maintaining this map

When a protocol page adds, removes or changes a Contract statement, or a
change moves the code that emits an entity, update the entity's row and
exceptions in the same change.

1. **Find the owner from code.** For an annotation, the builder that adds its
   `AnnotationType` and the code that retires persistent rows. For a prompt,
   the code that builds the request and the code that admits its response.
   Name classes, or `Class.member` when one member owns the behavior. The
   lint checks every owner symbol exists in main code.
2. **Check each Contract statement against the owner.** Read the code path
   that produces each field, identity and ordering the statement names.
3. **Record only exceptions.** A statement the code meets needs no entry. A
   branch the code never produces is `absent`; a shape the contract rules out
   is `diverges`; a deliberate alternative is `compat`. Never delete a
   protocol statement to clear an exception.
4. **Cite proof where it exists.** Add `protocol: <ID>` to a test or
   conformance contract only when that check would fail if the statement were
   violated. Code reading is not proof; it leaves the statement uncovered.
5. **Set the entity status** from the exceptions.
