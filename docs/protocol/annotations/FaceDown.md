---
summary: "FaceDown annotation: persistent reason a card is face down, on the stack, battlefield or in exile; REASON and abilityGrpId pairs for foretell, disguise and manifest dread, envelope and retirement."
entity: annotation/FaceDown
---
# FaceDown

## Meaning

Several mechanics put a card face down: a foretold card waits face down in
exile, a disguised spell is cast face down and resolves as a face-down 2/2,
and manifest dread puts a library card onto the battlefield face down
(CR 708). The object itself carries `isFacedown` and hides its identity from
players who may not see it. `FaceDown` adds why the card is face down, so the
client can present the right mechanic and the right way to turn it up.

## Variants

Persistent only, never combined with another type. `REASON` and
`abilityGrpId` select the path together:

| Path | `REASON` | `abilityGrpId` | Zone of the card |
|---|---|---|---|
| Foretell | `5` | `208` | Exile |
| Disguise | `6` | `307` | Stack, then battlefield |
| Manifest dread | `8` | `351` | Battlefield |

## Fields

| Field | Role |
|---|---|
| `affectorId` | The face-down card's current instance ID. |
| `affectedIds` | The same instance ID, as a one-element list. |
| `REASON` | The face-down path, as above. |
| `abilityGrpId` | The mechanic ability of the path, shared by every card that uses it, not the card's own definition. |

## Identity model

The row names the card's instance, not a separate view. `abilityGrpId` is an
ability definition; it does not reveal which card is face down. A disguised
spell's row follows the card from the stack to the battlefield: the same row
`id` is republished with the battlefield instance ID after the
[ObjectIdChanged](ObjectIdChanged.md) of the resolution.

## Lifecycle

1. The card becomes face down: it is foretold into exile, cast face down, or
   put onto the battlefield face down. The row appears in the same GSM as the
   face-down object.
2. The row stays in `persistentAnnotations` while the card stays face down.
3. When the card is turned face up, the row is deleted in the GSM that
   republishes the object without `isFacedown`. A card that leaves its zone
   loses its row with its instance ID.

## Sibling shapes

- A foretold card in exile also carries `SuppressedPowerAndToughness`, since
  a face-down exiled card has no characteristics to display.
- A disguised spell on the stack also carries
  [CastingTimeOption](CastingTimeOption.md) for its alternative cost.
- A face-down permanent's 2/2 characteristics are on the object; `FaceDown`
  does not carry them.

## Contract

- <a id="FaceDown.persistent"></a>**FaceDown.persistent.**
  `FaceDown` `MUST` be carried in `persistentAnnotations` and `MUST NOT` be
  combined with another annotation type.
- <a id="FaceDown.self-envelope"></a>**FaceDown.self-envelope.**
  `affectorId` `MUST` equal the single entry of `affectedIds`, and that object
  `MUST` carry `isFacedown`.
- <a id="FaceDown.reason-ability"></a>**FaceDown.reason-ability.**
  A row with `REASON` `5`, `6` or `8` `MUST` carry `abilityGrpId` `208`,
  `307` or `351` respectively.
- <a id="FaceDown.disguise-on-stack"></a>**FaceDown.disguise-on-stack.**
  *(provisional)* A spell cast face down for disguise `MUST` carry a `FaceDown`
  row while it is on the stack.
- <a id="FaceDown.turned-up"></a>**FaceDown.turned-up.**
  *(provisional)* When a face-down card is turned face up and stays in its
  zone, its row `MUST` be deleted in the GSM that republishes it without
  `isFacedown`.

## Open points

- Which `REASON` and `abilityGrpId` do morph, manifest and cloak use, and
  effects that turn an existing permanent face down?
