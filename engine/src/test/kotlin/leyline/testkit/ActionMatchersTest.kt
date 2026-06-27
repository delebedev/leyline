package leyline.testkit

import io.kotest.assertions.shouldFail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.should
import io.kotest.matchers.shouldNot
import io.kotest.matchers.string.shouldContain
import leyline.UnitTag
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.ActionsAvailableReq
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import wotc.mtgo.gre.external.messaging.Messages.ManaRequirement

private const val FORETELL_GRP = 188_700
private const val OTHER_GRP = 999_999

private fun manaReq(abilityGrpId: Int): ManaRequirement =
    ManaRequirement
        .newBuilder()
        .setAbilityGrpId(abilityGrpId)
        .setCount(1)
        .build()

private fun castOffer(
    altGrpId: Int,
    abilityGrpId: Int = 0,
    manaCostStamp: Int = altGrpId,
    manaCostCount: Int = 1,
): Action {
    val builder =
        Action
            .newBuilder()
            .setActionType(ActionType.Cast)
            .setAlternativeGrpId(altGrpId)
            .setAbilityGrpId(abilityGrpId)
    repeat(manaCostCount) { builder.addManaCost(manaReq(manaCostStamp)) }
    return builder.build()
}

@Suppress(
    // Matcher tests verify failure-message shape via `shouldFail { ... }.message
    // shouldContain "..."`. Detekt doesn't recognize this pattern as equality-shape.
    "WeakAssertionOnly",
    "MissingAssertSoftly",
)
class ActionMatchersTest :
    FunSpec({

        tags(UnitTag)

        test("beAltCostOffer accepts a properly stamped offer") {
            val offer = castOffer(altGrpId = FORETELL_GRP)
            offer should beAltCostOffer(FORETELL_GRP)
        }

        test("beAltCostOffer fails informatively when offer is null") {
            val failure =
                shouldFail {
                    (null as Action?) should beAltCostOffer(FORETELL_GRP)
                }
            failure.message shouldContain "got null"
            failure.message shouldContain FORETELL_GRP.toString()
        }

        test("beAltCostOffer fails when manaCostList is empty") {
            val offer = castOffer(altGrpId = FORETELL_GRP, manaCostCount = 0)
            val failure =
                shouldFail {
                    offer should beAltCostOffer(FORETELL_GRP)
                }
            failure.message shouldContain "manaCostCount=0"
        }

        test("beAltCostOffer fails when manaCost stamp is wrong, naming the diverging values") {
            val offer = castOffer(altGrpId = FORETELL_GRP, manaCostStamp = OTHER_GRP)
            val failure =
                shouldFail {
                    offer should beAltCostOffer(FORETELL_GRP)
                }
            failure.message shouldContain "must all carry abilityGrpId=$FORETELL_GRP"
            failure.message shouldContain OTHER_GRP.toString()
        }

        test("offerAltCost passes when an active offer carries the alt-cost grpId") {
            val req =
                ActionsAvailableReq
                    .newBuilder()
                    .addActions(castOffer(altGrpId = FORETELL_GRP))
                    .build()
            req should offerAltCost(FORETELL_GRP)
        }

        test("offerAltCost passes when only an inactive offer carries the alt-cost grpId") {
            val req =
                ActionsAvailableReq
                    .newBuilder()
                    .addInactiveActions(castOffer(altGrpId = FORETELL_GRP))
                    .build()
            req should offerAltCost(FORETELL_GRP)
        }

        test("offerAltCost matches the abilityGrpId rail too (Escape/Disturb)") {
            val req =
                ActionsAvailableReq
                    .newBuilder()
                    .addActions(
                        castOffer(altGrpId = 0, abilityGrpId = FORETELL_GRP, manaCostStamp = FORETELL_GRP),
                    ).build()
            req should offerAltCost(FORETELL_GRP)
        }

        test("offerAltCost fails informatively when no offer carries the alt-cost grpId") {
            val req =
                ActionsAvailableReq
                    .newBuilder()
                    .addActions(castOffer(altGrpId = OTHER_GRP))
                    .addInactiveActions(castOffer(altGrpId = OTHER_GRP))
                    .build()
            val failure =
                shouldFail {
                    req should offerAltCost(FORETELL_GRP)
                }
            failure.message shouldContain "grpId=$FORETELL_GRP"
            failure.message shouldContain "1 active"
            failure.message shouldContain "1 inactive"
        }

        test("shouldNot offerAltCost reports counts when the offer is unexpectedly present") {
            val req =
                ActionsAvailableReq
                    .newBuilder()
                    .addActions(castOffer(altGrpId = FORETELL_GRP))
                    .build()
            val failure =
                shouldFail {
                    req shouldNot offerAltCost(FORETELL_GRP)
                }
            failure.message shouldContain "found 1 active"
        }

        test("haveManaCost accepts exact color counts") {
            val offer =
                Action
                    .newBuilder()
                    .addManaCost(mana(ManaColor.Generic, 2))
                    .addManaCost(mana(ManaColor.Blue_afc9, 1))
                    .build()

            offer should haveManaCost(generic = 2, blue = 1)
        }

        test("haveManaCost fails informatively on mismatched counts") {
            val offer =
                Action
                    .newBuilder()
                    .addManaCost(mana(ManaColor.Generic, 2))
                    .build()

            val failure =
                shouldFail {
                    offer should haveManaCost(generic = 1)
                }
            failure.message shouldContain "expected mana cost"
            failure.message shouldContain ManaColor.Generic.toString()
        }
    })
