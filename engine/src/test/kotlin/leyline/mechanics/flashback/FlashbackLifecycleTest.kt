package leyline.mechanics.flashback

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import leyline.game.mapping.ZoneIds
import leyline.testkit.SessionTest
import leyline.testkit.detailString
import leyline.testkit.gameStateMessages
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

private val PUZZLE =
    """
    [metadata]
    Name:Flashback Think Twice - Full Lifecycle
    Goal:Cast from hand, then flashback from GY. Drawn creature is win condition.
    Turns:5
    Difficulty:Easy

    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=2

    humanhand=Think Twice
    humanbattlefield=Island;Island;Island;Island;Island;Island
    humanlibrary=Coral Merfolk;Plains;Plains;Plains;Plains
    ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain
    """.trimIndent()

class FlashbackLifecycleTest :
    SessionTest({
        session("hand cast goes to graveyard, flashback cast exiles", puzzle = PUZZLE) {
            val handBefore = human.getZone(ZoneType.Hand).size()
            val handCastStart = messageSnapshot()
            castSpellByName("Think Twice").shouldBeTrue()
            passPriority()
            val handResolveMessages = messagesSince(handCastStart)
            val handResolveFrame =
                handResolveMessages
                    .gameStateMessages()
                    .first { gsm ->
                        gsm.annotationsList.any { ann ->
                            AnnotationType.ZoneTransfer_af5a in ann.typeList &&
                                ann.detailString("category") == "Resolve" &&
                                ann.detailsList.any { it.key == "zone_src" && it.getValueInt32(0) == ZoneIds.STACK } &&
                                ann.detailsList.any { it.key == "zone_dest" && it.getValueInt32(0) == ZoneIds.P1_GRAVEYARD }
                        }
                    }
            val graveyardIid =
                handResolveFrame
                    .annotationsList
                    .first { AnnotationType.ZoneTransfer_af5a in it.typeList && it.detailString("category") == "Resolve" }
                    .affectedIdsList
                    .single()
            handResolveFrame.diffDeletedInstanceIdsList shouldNotContain graveyardIid

            assertSoftly {
                human.getZone(ZoneType.Hand).size() shouldBe handBefore
                human
                    .getZone(ZoneType.Hand)
                    .cards
                    .any { it.name == "Coral Merfolk" }
                    .shouldBeTrue()
                human
                    .getZone(ZoneType.Graveyard)
                    .cards
                    .any { it.name == "Think Twice" }
                    .shouldBeTrue()
                human
                    .getZone(ZoneType.Exile)
                    .cards
                    .none { it.name == "Think Twice" }
                    .shouldBeTrue()
            }

            val handBeforeFlashback = human.getZone(ZoneType.Hand).size()
            castFromGraveyard("Think Twice").shouldBeTrue()

            assertSoftly {
                human.getZone(ZoneType.Hand).size() shouldBe handBeforeFlashback + 1
                human
                    .getZone(ZoneType.Exile)
                    .cards
                    .any { it.name == "Think Twice" }
                    .shouldBeTrue()
                human
                    .getZone(ZoneType.Graveyard)
                    .cards
                    .none { it.name == "Think Twice" }
                    .shouldBeTrue()
                human
                    .getZone(ZoneType.Hand)
                    .cards
                    .any { it.name == "Coral Merfolk" }
                    .shouldBeTrue()
            }
        }
    })
