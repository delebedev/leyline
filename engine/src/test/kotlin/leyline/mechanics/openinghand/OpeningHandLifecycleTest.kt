package leyline.mechanics.openinghand

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import leyline.game.mapping.ZoneIds
import leyline.testkit.SessionTest
import leyline.testkit.allAnnotations
import leyline.testkit.detailInt
import leyline.testkit.detailString
import leyline.testkit.gameStateMessages
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

class OpeningHandLifecycleTest :
    SessionTest({
        session(
            "opening-hand battlefield put uses its dedicated action lifecycle",
            deckList = "4 Leyline Axe\n56 Plains",
            seed = 1L,
        ) {
            val gsm =
                allMessages
                    .gameStateMessages()
                    .first {
                        it.annotationsList.any { annotation ->
                            AnnotationType.UserActionTaken in annotation.typeList &&
                                annotation.detailInt("actionType") == ActionType.OpeningHandAction.number
                        }
                    }
            val annotations = gsm.annotationsList
            val action =
                annotations.first {
                    AnnotationType.UserActionTaken in it.typeList &&
                        it.detailInt("actionType") == ActionType.OpeningHandAction.number
                }
            val abilityId = action.affectedIdsList.single()
            val created =
                annotations.first {
                    AnnotationType.AbilityInstanceCreated in it.typeList && abilityId in it.affectedIdsList
                }
            val transfer =
                annotations.first {
                    AnnotationType.ZoneTransfer_af5a in it.typeList &&
                        it.detailString("category") == "Put"
                }
            val identityChange =
                annotations.first {
                    AnnotationType.ObjectIdChanged in it.typeList && it.affectorId == abilityId
                }
            val deleted =
                annotations.first {
                    AnnotationType.AbilityInstanceDeleted in it.typeList && abilityId in it.affectedIdsList
                }
            val lifecycle =
                annotations.mapNotNull { annotation ->
                    when {
                        AnnotationType.AbilityInstanceCreated in annotation.typeList -> "created"
                        AnnotationType.ResolutionStart in annotation.typeList -> "resolving"
                        AnnotationType.ObjectIdChanged in annotation.typeList -> "identity"
                        AnnotationType.ZoneTransfer_af5a in annotation.typeList -> "transfer"
                        AnnotationType.ResolutionComplete in annotation.typeList -> "resolved"
                        AnnotationType.AbilityInstanceDeleted in annotation.typeList -> "deleted"
                        AnnotationType.UserActionTaken in annotation.typeList -> "action"
                        else -> null
                    }
                }

            assertSoftly {
                human.battlefield.card("Leyline Axe").name shouldBe "Leyline Axe"
                action.affectorId shouldBe 1
                action.detailInt("abilityGrpId") shouldBe 175903
                created.detailInt("source_zone") shouldBe ZoneIds.P1_HAND
                transfer.affectorId shouldBe abilityId
                transfer.detailInt("zone_src") shouldBe ZoneIds.P1_HAND
                transfer.detailInt("zone_dest") shouldBe ZoneIds.BATTLEFIELD
                identityChange.detailInt("orig_id") shouldBe created.affectorId
                identityChange.detailInt("new_id") shouldBe transfer.affectedIdsList.single()
                deleted.affectorId shouldBe created.affectorId
                lifecycle shouldBe listOf("created", "resolving", "identity", "transfer", "resolved", "deleted", "action")
                annotations.filter { AnnotationType.ManaPaid in it.typeList }.shouldBeEmpty()
                annotations
                    .filter { AnnotationType.UserActionTaken in it.typeList }
                    .map { it.detailInt("actionType") } shouldContain ActionType.OpeningHandAction.number
            }
        }

        session(
            "AI opening-hand battlefield put reaches the battlefield",
            deckList = "60 Plains",
            opponentDeckList = "60 Leyline Axe",
            seed = 1L,
        ) {
            assertSoftly {
                ai.battlefield.card("Leyline Axe").name shouldBe "Leyline Axe"
                game().phaseHandler.phase.name shouldBe "MAIN1"
            }
        }

        session(
            "ordinary Leyline Axe cast stays on the generic cast path",
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Leyline Axe
                humanbattlefield=Plains;Plains;Plains;Plains
                humanlibrary=Plains;Plains;Plains
                ailibrary=Plains;Plains;Plains
                """,
        ) {
            castSpellByName("Leyline Axe").shouldBeTrue()
            passUntilResolved()

            val annotations = allMessages.allAnnotations()
            val castAction =
                annotations.last {
                    AnnotationType.UserActionTaken in it.typeList &&
                        it.detailInt("actionType") == ActionType.Cast.number
                }
            assertSoftly {
                castAction.detailInt("abilityGrpId") shouldBe 0
                annotations
                    .filter { AnnotationType.UserActionTaken in it.typeList }
                    .map { it.detailInt("actionType") } shouldNotContain ActionType.OpeningHandAction.number
                annotations.any { AnnotationType.ManaPaid in it.typeList }.shouldBeTrue()
            }
        }
    })
