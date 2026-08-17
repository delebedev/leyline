package leyline.session.costs

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import leyline.testkit.after
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import forge.game.zone.ZoneType as ForgeZoneType

class HybridManaCostInteractionTest :
    SessionTest({
        val tawnybackState =
            """
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20

            humanhand=Temur Tawnyback
            humanbattlefield=Island;Mountain;Plains;Swamp
            humanlibrary=Forest
            aibattlefield=
            ailibrary=Forest
            """.trimIndent()

        session("hybrid two-or-color pips emit ManaType CTOs and honor printed-order choices", puzzle = tawnybackState) {
            val cto =
                after { castSpellByName("Temur Tawnyback").shouldBeTrue() }
                    .expectOneCastingTimeOptionsReq()
            val options = cto.castingTimeOptionReqList

            assertSoftly {
                options shouldHaveSize 3
                options.map { it.ctoId } shouldBe listOf(2, 3, 4)
                options.map { it.castingTimeOptionType } shouldBe List(3) { CastingTimeOptionType.ManaType }
                options.map { it.selectManaTypeReq.manaColorsList } shouldBe
                    listOf(
                        listOf(ManaColor.TwoGeneric, ManaColor.Green_afc9),
                        listOf(ManaColor.TwoGeneric, ManaColor.Blue_afc9),
                        listOf(ManaColor.TwoGeneric, ManaColor.Red_afc9),
                    )
            }

            respondToManaTypeChoices(
                listOf(
                    2 to ManaColor.TwoGeneric,
                    3 to ManaColor.Blue_afc9,
                    4 to ManaColor.Red_afc9,
                ),
            )

            human.getZone(ForgeZoneType.Battlefield).cards.map { it.name } shouldContain "Temur Tawnyback"
        }
    })
