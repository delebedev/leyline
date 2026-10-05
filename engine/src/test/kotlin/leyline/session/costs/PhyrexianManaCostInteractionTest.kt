package leyline.session.costs

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import leyline.testkit.after
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType
import wotc.mtgo.gre.external.messaging.Messages.ManaColor

class PhyrexianManaCostInteractionTest :
    SessionTest({
        val krrik = "K'rrik, Son of Yawgmoth"

        fun puzzle(
            hand: String = krrik,
            battlefield: String = "Swamp;Swamp;Swamp;Swamp;Swamp;Swamp;Swamp",
            life: Int = 20,
            command: String = "",
        ) = """
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=$life
            AILife=20
            humanhand=$hand
            humanbattlefield=$battlefield
            humancommand=$command
            humanlibrary=Forest
            ailibrary=Forest
            """.trimIndent()

        session(
            "accepted black kicker offers life choices for the complete cost",
            puzzle = puzzle(hand = "Duskwalker", battlefield = "$krrik;Mountain;Mountain;Mountain"),
        ) {
            val first = after { castSpellByName("Duskwalker").shouldBeTrue() }.expectOneCastingTimeOptionsReq()
            val kicker = first.castingTimeOptionReqList.first { it.castingTimeOptionType == CastingTimeOptionType.Kicker }
            respondToOptionalCost(kicker.ctoId)
            val mana = lastCastingTimeOptionsReq().castingTimeOptionReqList.filter { it.hasSelectManaTypeReq() }
            mana.size shouldBe 2
            respondToManaTypeChoices(mana.map { it.ctoId to ManaColor.Phyrexian_afc9 })
            passUntilResolved()
            val walker = human.getZone(ZoneType.Battlefield).cards.first { it.name == "Duskwalker" }
            assertSoftly {
                human.life shouldBe 16
                walker.netPower shouldBe 3
                walker.netToughness shouldBe 3
                human.getZone(ZoneType.Battlefield).cards.count { it.isTapped } shouldBe 3
            }
        }

        session(
            "declined black kicker offers only the base life payment",
            puzzle = puzzle(hand = "Duskwalker", battlefield = "$krrik;Mountain;Mountain;Mountain"),
        ) {
            after { castSpellByName("Duskwalker").shouldBeTrue() }.expectOneCastingTimeOptionsReq()
            respondToOptionalCost(0)
            val mana = lastCastingTimeOptionsReq().castingTimeOptionReqList.filter { it.hasSelectManaTypeReq() }
            mana.size shouldBe 1
            respondToManaTypeChoices(mana.map { it.ctoId to ManaColor.Phyrexian_afc9 })
            passUntilResolved()
            assertSoftly {
                human.life shouldBe 18
                human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { it.name == "Duskwalker" }
                    .netPower shouldBe 1
                human.getZone(ZoneType.Battlefield).cards.count { it.isTapped } shouldBe 0
            }
        }

        session(
            "kicker life and mana choices preserve the complete cost",
            puzzle = puzzle(hand = "Duskwalker", battlefield = "$krrik;Mountain;Mountain;Mountain;Swamp"),
        ) {
            val optional = after { castSpellByName("Duskwalker").shouldBeTrue() }.expectOneCastingTimeOptionsReq()
            respondToOptionalCost(
                optional.castingTimeOptionReqList.first { it.castingTimeOptionType == CastingTimeOptionType.Kicker }.ctoId,
            )
            val mana = lastCastingTimeOptionsReq().castingTimeOptionReqList.filter { it.hasSelectManaTypeReq() }
            respondToManaTypeChoices(listOf(mana[0].ctoId to ManaColor.Phyrexian_afc9, mana[1].ctoId to ManaColor.Black_afc9))
            passUntilResolved()
            assertSoftly {
                human.life shouldBe 18
                human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { it.name == "Duskwalker" }
                    .netPower shouldBe 3
                human.getZone(ZoneType.Battlefield).cards.count { it.isTapped } shouldBe 4
            }
        }

        session(
            "unaffordable kicker life choice pays neither life nor mana",
            puzzle = puzzle(hand = "Duskwalker", battlefield = "$krrik;Mountain;Mountain;Mountain", life = 3),
        ) {
            val optional = after { castSpellByName("Duskwalker").shouldBeTrue() }.expectOneCastingTimeOptionsReq()
            respondToOptionalCost(
                optional.castingTimeOptionReqList.first { it.castingTimeOptionType == CastingTimeOptionType.Kicker }.ctoId,
            )
            val mana = lastCastingTimeOptionsReq().castingTimeOptionReqList.filter { it.hasSelectManaTypeReq() }
            respondToManaTypeChoices(mana.map { it.ctoId to ManaColor.Phyrexian_afc9 })
            assertSoftly {
                human.life shouldBe 3
                human.getZone(ZoneType.Hand).cards.map { it.name } shouldContain "Duskwalker"
                human.getZone(ZoneType.Battlefield).cards.count { it.isTapped } shouldBe 0
            }
        }

        session(
            "cancelled kicker payment does not retain the optional selection",
            puzzle = puzzle(hand = "Duskwalker", battlefield = "$krrik;Mountain;Mountain;Mountain"),
        ) {
            val optional = after { castSpellByName("Duskwalker").shouldBeTrue() }.expectOneCastingTimeOptionsReq()
            respondToOptionalCost(
                optional.castingTimeOptionReqList.first { it.castingTimeOptionType == CastingTimeOptionType.Kicker }.ctoId,
            )
            cancelAction()
            assertSoftly {
                human.life shouldBe 20
                human.getZone(ZoneType.Hand).cards.map { it.name } shouldContain "Duskwalker"
                human.getZone(ZoneType.Battlefield).cards.count { it.isTapped } shouldBe 0
            }
            after { castSpellByName("Duskwalker").shouldBeTrue() }.expectOneCastingTimeOptionsReq()
            respondToOptionalCost(0)
            val mana = lastCastingTimeOptionsReq().castingTimeOptionReqList.filter { it.hasSelectManaTypeReq() }
            mana.size shouldBe 1
            respondToManaTypeChoices(mana.map { it.ctoId to ManaColor.Phyrexian_afc9 })
            passUntilResolved()
            assertSoftly {
                human.life shouldBe 18
                human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { it.name == "Duskwalker" }
                    .netPower shouldBe 1
                human.getZone(ZoneType.Battlefield).cards.count { it.isTapped } shouldBe 0
            }
        }

        session(
            "black kicker adds a life choice to a spell with no base black symbol",
            puzzle = puzzle(hand = "Benalish Sleeper", battlefield = "$krrik;Plains;Plains"),
        ) {
            val optional = after { castSpellByName("Benalish Sleeper").shouldBeTrue() }.expectOneCastingTimeOptionsReq()
            respondToOptionalCost(
                optional.castingTimeOptionReqList.first { it.castingTimeOptionType == CastingTimeOptionType.Kicker }.ctoId,
            )
            val mana = lastCastingTimeOptionsReq().castingTimeOptionReqList.filter { it.hasSelectManaTypeReq() }
            mana.size shouldBe 1
            respondToManaTypeChoices(mana.map { it.ctoId to ManaColor.Phyrexian_afc9 })
            passUntil(maxPasses = 8) { allMessages.any { it.hasSelectNReq() } }.shouldBeTrue()
            respondToSelectN(listOf(human.battlefield.iid("Benalish Sleeper")))
            passUntilResolved()
            assertSoftly {
                human.life shouldBe 18
                human.getZone(ZoneType.Graveyard).cards.map { it.name } shouldContain "Benalish Sleeper"
                human.getZone(ZoneType.Battlefield).cards.map { it.name } shouldContain krrik
                human.getZone(ZoneType.Battlefield).cards.count { it.isTapped } shouldBe 2
            }
        }

        session("intrinsic phyrexian choices spend selected life despite available mana", puzzle = puzzle()) {
            val request = after { castSpellByName(krrik).shouldBeTrue() }.expectOneCastingTimeOptionsReq()
            request.castingTimeOptionReqList.map { it.selectManaTypeReq.manaColorsList } shouldBe
                List(3) { listOf(ManaColor.Phyrexian_afc9, ManaColor.Black_afc9) }
            respondToManaTypeChoices(listOf(2 to ManaColor.Phyrexian_afc9, 3 to ManaColor.Black_afc9, 4 to ManaColor.Phyrexian_afc9))
            assertSoftly {
                human.life shouldBe 16
                human.getZone(ZoneType.Battlefield).cards.map { it.name } shouldContain krrik
                human.getZone(ZoneType.Battlefield).cards.count { it.isTapped } shouldBe 5
            }
        }

        session(
            "selected mana does not fall back to life when sources cannot pay",
            puzzle = puzzle(battlefield = "Swamp;Swamp;Swamp;Swamp"),
        ) {
            after { castSpellByName(krrik).shouldBeTrue() }.expectOneCastingTimeOptionsReq()
            respondToManaTypeChoices(listOf(2 to ManaColor.Black_afc9, 3 to ManaColor.Black_afc9, 4 to ManaColor.Black_afc9))
            human.life shouldBe 20
            val unpaid = human.getZone(ZoneType.Hand).cards.first { it.name == krrik }
            unpaid.spellAbilities.any { it.hasParam("AIPhyrexianPayment") } shouldBe false
            human.getZone(ZoneType.Battlefield).cards.count { it.isTapped } shouldBe 0
        }

        session("unaffordable life answer leaves life and mana unchanged", puzzle = puzzle(life = 5)) {
            after { castSpellByName(krrik).shouldBeTrue() }.expectOneCastingTimeOptionsReq()
            respondToManaTypeChoices(listOf(2 to ManaColor.Phyrexian_afc9, 3 to ManaColor.Phyrexian_afc9, 4 to ManaColor.Phyrexian_afc9))
            assertSoftly {
                human.life shouldBe 5
                human.getZone(ZoneType.Hand).cards.map { it.name } shouldContain krrik
                human.getZone(ZoneType.Battlefield).cards.count { it.isTapped } shouldBe 0
            }
        }

        session("unoffered color and incomplete life answer keep the exact choice usable", puzzle = puzzle()) {
            val request = after { castSpellByName(krrik).shouldBeTrue() }.expectOneCastingTimeOptionsReq()
            respondToManaTypeChoices(listOf(2 to ManaColor.Green_afc9, 3 to ManaColor.Black_afc9, 4 to ManaColor.Black_afc9))
            lastCastingTimeOptionsReq() shouldBe request
            respondToManaTypeChoices(listOf(2 to ManaColor.Phyrexian_afc9))
            lastCastingTimeOptionsReq() shouldBe request
            human.life shouldBe 20
            respondToManaTypeChoices(listOf(2 to ManaColor.Phyrexian_afc9, 3 to ManaColor.Black_afc9, 4 to ManaColor.Phyrexian_afc9))
            human.life shouldBe 16
        }

        session("cancelled life choice keeps the card and life", puzzle = puzzle()) {
            after { castSpellByName(krrik).shouldBeTrue() }.expectOneCastingTimeOptionsReq()
            cancelAction()
            human.life shouldBe 20
            human.getZone(ZoneType.Hand).cards.map { it.name } shouldContain krrik
        }

        session(
            "granted black payment chooses life for a nonphyrexian spell",
            puzzle = puzzle(hand = "Phyrexian Obliterator", battlefield = "$krrik;Swamp;Swamp;Swamp;Swamp"),
        ) {
            after { castSpellByName("Phyrexian Obliterator").shouldBeTrue() }.expectOneCastingTimeOptionsReq()
            respondToManaTypeChoices(
                listOf(
                    2 to ManaColor.Phyrexian_afc9,
                    3 to ManaColor.Black_afc9,
                    4 to ManaColor.Phyrexian_afc9,
                    5 to ManaColor.Black_afc9,
                ),
            )
            assertSoftly {
                human.life shouldBe 16
                human.getZone(ZoneType.Battlefield).cards.map { it.name } shouldContain "Phyrexian Obliterator"
                human.getZone(ZoneType.Battlefield).cards.count { it.isTapped } shouldBe 2
            }
        }

        session(
            "commander tax stays payable as mana when all phyrexian symbols use life",
            puzzle = puzzle(hand = "", command = "$krrik|IsCommander|CommanderCast:1"),
        ) {
            val request = after { castSpellByName(krrik, ZoneType.Command).shouldBeTrue() }.expectOneCastingTimeOptionsReq()
            request.castingTimeOptionReqList
                .first()
                .manaCostList
                .first { it.colorList == listOf(ManaColor.Generic) }
                .count shouldBe 6
            respondToManaTypeChoices(listOf(2 to ManaColor.Phyrexian_afc9, 3 to ManaColor.Phyrexian_afc9, 4 to ManaColor.Phyrexian_afc9))
            assertSoftly {
                human.life shouldBe 14
                human.getZone(ZoneType.Battlefield).cards.map { it.name } shouldContain krrik
                human.getZone(ZoneType.Battlefield).cards.count { it.isTapped } shouldBe 6
            }
        }
    })
