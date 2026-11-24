---
summary: "ChoiceResult annotation: transient record of a value a player chose for an effect; object, static-list and declined branches, Choice_Domain, sentiment and ordering."
entity: annotation/ChoiceResult
---
# ChoiceResult

## Meaning

Many effects ask a player to choose something as they resolve or as a
permanent enters: an object to sacrifice, a color, a creature type, a card
name. `ChoiceResult` publishes the committed choice in the GSM where it takes
effect, so the client can show what was chosen. The prompt response carries
the same value earlier; the annotation is the public record of it.

## Variants

Transient only, never combined with another type.

| Branch | Discriminator | `Choice_Value` |
|---|---|---|
| Object choice | No `Choice_Domain` | The chosen object's instance ID. |
| Static-list choice | `Choice_Domain` present | A value of the list named by `Choice_Domain`, such as a `CardColor` or a subtype. |
| No choice | `Choice_Value` `-1` | Nothing was chosen, for example a declined optional choice. |

One choice of several values produces one row per value.

## Fields

| Field | Role |
|---|---|
| `affectorId` | The card or permanent whose effect asked for the choice. A triggered ability's source card is named, not the ability instance. |
| `affectedIds` | The seat of the player who chose, as a one-element list. |
| `Choice_Value` | The chosen value, in the domain of its branch. |
| `Choice_Domain` | A `StaticList` value naming the list the value comes from: `1` CardColors, `5` SubTypes, `6` Colors, `9` BasicLandTypes, `11` CounterTypes, `13` CardNames, `14` Parities. |
| `Choice_Sentiment` | A small integer classifying the choice. Static-list rows carry `2`; object rows carry `1` or `2`. |
| `Choice_Options` | Rare; see Open points. |

## Identity model

`affectedIds` holds a seat ID, never an instance ID. `Choice_Value` is an
instance ID on the object branch and a list value on the static-list branch;
the two are told apart only by `Choice_Domain`. A static-list value is not an
instance ID even when it is a large number, such as a card title for
`CardNames`. `Choice_Domain` uses the same `StaticList` values as
[SelectNReq](../prompts/SelectNReq.md)'s `staticList`.

## Lifecycle

1. A prompt such as [SelectNReq](../prompts/SelectNReq.md) or `PromptReq`
   offers the candidates.
2. The player answers.
3. The next GSM carries `ChoiceResult`, normally as its first annotation and
   before the zone transfers, counters, layered effects and
   [ResolutionComplete](ResolutionComplete.md) that apply the choice.
4. The row lasts for that GSM only.

For example, an exploit creature's controller sacrificing a creature produces
a row with the exploit creature as `affectorId`, the controller's seat in
`affectedIds` and the sacrificed creature's instance ID as `Choice_Value`.

## Sibling shapes

`ObjectsSelected` is a persistent marker that a selection was committed; it
does not carry the value. A prompt response carries the submitted values
before the result GSM but is not part of the state stream.

## Contract

- <a id="ChoiceResult.transient"></a>**ChoiceResult.transient.**
  `ChoiceResult` `MUST` be carried in `annotations`, never in
  `persistentAnnotations`.
- <a id="ChoiceResult.chooser-seat"></a>**ChoiceResult.chooser-seat.**
  `affectedIds` `MUST` contain exactly one entry, the seat ID of the player
  who chose.
- <a id="ChoiceResult.domain-static-list"></a>**ChoiceResult.domain-static-list.**
  When the choice answered a `SelectNReq` with a `staticList`, the row `MUST`
  carry `Choice_Domain` equal to that list's value.
- <a id="ChoiceResult.domain-sentiment"></a>**ChoiceResult.domain-sentiment.**
  *(provisional)* A row with `Choice_Domain` `MUST` carry `Choice_Sentiment`
  `2`.
- <a id="ChoiceResult.before-resolution"></a>**ChoiceResult.before-resolution.**
  *(provisional)* When the GSM also carries `ResolutionComplete`,
  `ChoiceResult` `MUST` precede it.

## Open points

- What does `Choice_Sentiment` classify, and when is it `5`?
- What does `Choice_Options` carry?
- Which declined choices produce a `-1` row, and which produce no row?
- Why do a few rows follow other effect annotations in their GSM?
