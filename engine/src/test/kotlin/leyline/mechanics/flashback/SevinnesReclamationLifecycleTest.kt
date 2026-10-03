package leyline.mechanics.flashback

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import leyline.testkit.detailString
import leyline.testkit.gameStateMessages
import leyline.tooling.headless.MatchFlowHarness
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType

class SevinnesReclamationLifecycleTest :
    SessionTest({
        fun MatchFlowHarness.castAndReachCopyChoice() {
            holdNextOptionalAction()
            castFromGraveyard("Sevinne's Reclamation").shouldBeTrue()
            selectTargets(listOf(human.graveyard.iid("Basilisk Collar")))
        }

        fun MatchFlowHarness.castSpellTransfers(): Int =
            allMessages
                .gameStateMessages()
                .flatMap { it.annotationsList }
                .count {
                    AnnotationType.ZoneTransfer_af5a in it.typeList &&
                        it.detailString("category") == "CastSpell"
                }

        session(
            "accepting the optional copy returns a second target without another cast",
            puzzleFile = "data/puzzles/sevinnes-reclamation-copy.pzl",
        ) {
            castAndReachCopyChoice()

            allMessages.count { it.type == GREMessageType.OptionalActionMessage_695e } shouldBe 1
            respondToOptionalAction(accept = true)
            selectTargets(listOf(human.graveyard.iid("Sol Ring")))
            passPriority()

            val battlefield = human.getZone(ZoneType.Battlefield).cards.map { it.name }
            val exile = human.getZone(ZoneType.Exile).cards.map { it.name }
            assertSoftly {
                battlefield shouldContain "Basilisk Collar"
                battlefield shouldContain "Sol Ring"
                exile shouldContain "Sevinne's Reclamation"
                castSpellTransfers() shouldBe 1
            }
        }

        session(
            "declining the optional copy returns only the original target",
            puzzleFile = "data/puzzles/sevinnes-reclamation-copy.pzl",
        ) {
            castAndReachCopyChoice()

            val beforeDecline = allMessages.size
            allMessages.count { it.type == GREMessageType.OptionalActionMessage_695e } shouldBe 1
            respondToOptionalAction(accept = false)

            val battlefield = human.getZone(ZoneType.Battlefield).cards.map { it.name }
            val graveyard = human.getZone(ZoneType.Graveyard).cards.map { it.name }
            val laterMessages = allMessages.drop(beforeDecline)
            assertSoftly {
                battlefield shouldContain "Basilisk Collar"
                battlefield shouldNotContain "Sol Ring"
                graveyard shouldContain "Sol Ring"
                laterMessages.none { it.hasSelectTargetsReq() }.shouldBeTrue()
                castSpellTransfers() shouldBe 1
            }
        }
    })
