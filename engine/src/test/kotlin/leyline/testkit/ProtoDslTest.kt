package leyline.testkit

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import wotc.mtgo.gre.external.messaging.Messages.ManaInfo
import wotc.mtgo.gre.external.messaging.Messages.ManaPaymentOption

class ProtoDslTest :
    FunSpec({
        tags(UnitTag)

        test("performAction wraps full action payload") {
            val action =
                Action
                    .newBuilder()
                    .setActionType(ActionType.Cast)
                    .setInstanceId(10)
                    .setGrpId(20)
                    .setAbilityGrpId(30)
                    .setAlternativeGrpId(40)
                    .addManaPaymentOptions(
                        ManaPaymentOption
                            .newBuilder()
                            .addMana(
                                ManaInfo
                                    .newBuilder()
                                    .setManaId(50)
                                    .setColor(ManaColor.Green_afc9),
                            ),
                    ).build()

            val wrapped = performAction(action).performActionResp.actionsList.single()

            wrapped shouldBe action
        }
    })
