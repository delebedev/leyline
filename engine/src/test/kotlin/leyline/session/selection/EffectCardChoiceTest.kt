package leyline.session.selection

import forge.ai.PlayerControllerAi
import forge.game.card.CardCollectionView
import forge.game.player.Player
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import leyline.testkit.beInZoneOf
import leyline.testkit.beMissingFrom
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

class EffectCardChoiceTest :
    SessionTest({
        session(
            "Single Combat keeps the selected non-first creature and sacrifices the other",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanhand=Single Combat
                humanbattlefield=Plains;Plains;Plains;Plains;Plains;Grizzly Bears;Centaur Courser
                humanlibrary=Island;Island;Island
                aibattlefield=Walking Corpse
                ailibrary=Island;Island;Island
            """,
            fullControl = true,
        ) {
            val first = human.battlefield.iid("Grizzly Bears")
            val chosen = human.battlefield.iid("Centaur Courser")
            val req = castSpellUntilSelectNReq("Single Combat")
            assertSoftly {
                req.idsList shouldContainExactly listOf(first, chosen)
                req.unfilteredIdsList shouldContainExactly req.idsList
                req.minSel shouldBe 1
                req.maxSel shouldBe 1
            }
            respondToSelectN(listOf(chosen))
            passUntilResolved()
            assertSoftly {
                "Centaur Courser" should beInZoneOf(ZoneType.Battlefield, human)
                "Grizzly Bears" should beMissingFrom(ZoneType.Battlefield, human)
                "Grizzly Bears" should beInZoneOf(ZoneType.Graveyard, human)
            }
        }

        session(
            "Dragon's Disciple reveals only the selected non-first Dragon and gains its counter",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanhand=Dragon's Disciple;Shivan Dragon;Volcanic Dragon
                humanbattlefield=Plains;Plains
                humanlibrary=Island;Island;Island
                ailibrary=Island;Island;Island
            """,
            fullControl = true,
        ) {
            val received = mutableListOf<Int>()
            ai.dangerouslySetController(
                object : PlayerControllerAi(game(), ai, ai.lobbyPlayer) {
                    override fun reveal(
                        cards: CardCollectionView,
                        zone: ZoneType,
                        owner: Player,
                        messagePrefix: String?,
                        addMsgSuffix: Boolean,
                    ) {
                        received.addAll(cards.map { it.id })
                        super.reveal(cards, zone, owner, messagePrefix, addMsgSuffix)
                    }
                },
            )
            val chosenForgeId = human.hand.card("Volcanic Dragon").id
            val first = human.hand.iid("Shivan Dragon")
            val chosen = human.hand.iid("Volcanic Dragon")
            val req = castSpellUntilSelectNReq("Dragon's Disciple")
            req.idsList shouldContainExactly listOf(first, chosen)
            respondToSelectN(listOf(chosen))
            passUntilResolved()
            val revealed =
                allMessages
                    .filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.persistentAnnotationsList }
                    .filter { AnnotationType.InstanceRevealedToOpponent in it.typeList }
                    .flatMap { it.affectedIdsList }
                    .toSet()
            assertSoftly {
                received shouldContainExactly listOf(chosenForgeId)
                revealed.shouldBeEmpty()
                human.battlefield.card("Dragon's Disciple").netPower shouldBe 2
                human.battlefield.card("Dragon's Disciple").netToughness shouldBe 4
            }
        }
    })
