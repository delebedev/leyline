package leyline.session.zones

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.game.mapping.PromptIds
import leyline.testkit.SessionTest
import leyline.testkit.annotations
import leyline.testkit.detailIntList
import leyline.testkit.detailString
import wotc.mtgo.gre.external.messaging.Messages.*
import forge.game.zone.ZoneType as ForgeZoneType

/**
 * Library ordering interactions — surveil and scry.
 *
 * Both mechanics use GroupReq/GroupResp: surveil offers Library/Top vs Graveyard,
 * scry offers Library/Top vs Library/Bottom.
 *
 * Annotation-heavy tests (PlayLand, CastSpell, Resolve pipeline) stay in
 * ScryETBFlowTest — they test the annotation pipeline, not scry itself.
 */
class LibraryOrderInteractionTest :
    SessionTest({

        // --- Surveil 1 (Wary Thespian: ETB surveil 1) ---

        val surveil1State =
            """
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20

            humanbattlefield=Forest;Forest
            humanhand=Wary Thespian
            humanlibrary=Grizzly Bears;Forest;Forest;Forest;Forest
            aibattlefield=Mountain
            ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain
            """.trimIndent()

        fun startSurveil1() = startPuzzle(surveil1State, name = "Surveil 1")

        test("surveil 1 — GroupReq shape and revealed card") {
            startSurveil1()

            val req = castSpellUntilGroupReq("Wary Thespian")
            assertSoftly {
                req.context shouldBe GroupingContext.Surveil
                req.instanceIdsList shouldHaveSize 1
                req.groupSpecsList shouldHaveSize 2
                req.groupSpecsList[0].zoneType shouldBe ZoneType.Library
                req.groupSpecsList[0].subZoneType shouldBe SubZoneType.Top
                req.groupSpecsList[1].zoneType shouldBe ZoneType.Graveyard
                req.sourceId shouldNotBe 0
            }

            // Revealed card must be library top (Grizzly Bears)
            cardName(req.instanceIdsList.first()) shouldBe "Grizzly Bears"
        }

        test("surveil 1 — keep on top leaves card on library top") {
            startSurveil1()

            val cardIds = castSpellUntilGroupReq("Wary Thespian").instanceIdsList

            respondToGroupReq(awayInstanceIds = emptyList(), allInstanceIds = cardIds)

            human
                .getZone(ForgeZoneType.Library)
                .cards
                .first()
                .name shouldBe "Grizzly Bears"
        }

        test("grouping prompt rejects SelectTargetsResp without consuming the pending route") {
            startSurveil1()
            val cardIds = castSpellUntilGroupReq("Wary Thespian").instanceIdsList
            val promptBefore =
                harness.bridge
                    .cutCoordinator
                    .grouping
                    .current()
                    .shouldNotBeNull()

            selectTargetsIterative(emptyList())

            harness.bridge
                .cutCoordinator
                .grouping
                .current()
                ?.interactionId shouldBe promptBefore.interactionId
            respondToGroupReq(awayInstanceIds = emptyList(), allInstanceIds = cardIds)
            human
                .getZone(ForgeZoneType.Library)
                .cards
                .first()
                .name shouldBe "Grizzly Bears"
        }

        // Suspected flaky in CI — passes locally, null annotation intermittently on GH runners
        test("surveil 1 — put in graveyard moves card and produces Surveil annotation") {
            startSurveil1()

            val snap = messageSnapshot()
            val cardIds = castSpellUntilGroupReq("Wary Thespian").instanceIdsList

            respondToGroupReq(awayInstanceIds = cardIds, allInstanceIds = cardIds)

            // Grizzly Bears in graveyard
            val gyBears =
                human
                    .getZone(ForgeZoneType.Graveyard)
                    .cards
                    .filter { it.name == "Grizzly Bears" }
            gyBears shouldHaveSize 1

            // Library top is now Forest (Bears was removed)
            human
                .getZone(ForgeZoneType.Library)
                .cards
                .first()
                .name shouldBe "Forest"

            // ZoneTransfer annotation with Surveil category and non-zero affectorId
            val annotations = annotationsSince(snap)
            val surveilZt =
                annotations.firstOrNull { ann ->
                    ann.typeList.any { it == AnnotationType.ZoneTransfer_af5a } &&
                        ann.detailString("category") == "Surveil"
                }
            assertSoftly {
                surveilZt.shouldNotBeNull()
                surveilZt.affectedIdsList shouldHaveSize 1
                surveilZt.affectorId shouldNotBe 0
            }

            assertAccumulatorConsistent("after surveil to graveyard")
        }

        // --- Surveil 2 (Sterling Hound: ETB surveil 2) ---

        test("surveil 2 — multi-card to graveyard") {
            startPuzzle(
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Sterling Hound
                humanbattlefield=Plains;Plains;Plains
                humanlibrary=Mountain;Forest;Island;Swamp;Plains
                ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain
                """,
                name = "Surveil 2",
            )

            val groupReq = castSpellUntilGroupReq("Sterling Hound")
            groupReq.context shouldBe GroupingContext.Surveil
            groupReq.instanceIdsList shouldHaveSize 2

            val allIds = groupReq.instanceIdsList
            respondToGroupReq(awayInstanceIds = allIds, allInstanceIds = allIds)

            // Sterling Hound resolved to BF, 2 surveiled cards in graveyard
            human.getZone(ForgeZoneType.Graveyard).size() shouldBe 2
        }

        test("surveil 2 — keep both on top emits OrderReq and applies order") {
            startPuzzle(
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Sterling Hound
                humanbattlefield=Plains;Plains;Plains
                humanlibrary=Mountain;Forest;Island;Swamp;Plains
                ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain
                """,
                name = "Surveil 2 top order",
            )

            val groupReq = castSpellUntilGroupReq("Sterling Hound")
            val groupIds = groupReq.instanceIdsList
            groupIds shouldHaveSize 2

            val orderMsg =
                after {
                    respondToGroupReq(awayInstanceIds = emptyList(), allInstanceIds = groupIds)
                }.messages.last { it.hasOrderReq() }
            val orderReq = orderMsg.orderReq
            val orderedIds = orderReq.idsList.reversed()
            val orderedNames = orderedIds.map(::cardName)

            assertSoftly {
                orderMsg.type shouldBe GREMessageType.OrderReq_695e
                orderMsg.prompt.promptId shouldBe PromptIds.ORDER_LIBRARY_TOP
                orderReq.orderingContext shouldBe OrderingContext.None_a89f
                orderReq.idsList.map(::cardName) shouldBe groupIds.map(::cardName)
            }

            respondToOrder(orderedIds)

            val annotation =
                allMessages
                    .flatMap { if (it.hasGameStateMessage()) it.gameStateMessage.annotationsList else emptyList() }
                    .last { AnnotationType.Scry_af5a in it.typeList }
            assertSoftly {
                human
                    .getZone(ForgeZoneType.Library)
                    .cards
                    .take(2)
                    .map { it.name } shouldBe orderedNames
                annotation.detailIntList("topIds") shouldBe orderedIds
                annotation.detailIntList("bottomIds") shouldBe emptyList()
            }
        }

        // --- Scry 1 (Wall of Runes: ETB scry 1) ---

        val scryState =
            """
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20

            humanhand=Wall of Runes
            humanbattlefield=Island
            humanlibrary=Grizzly Bears;Forest;Forest;Forest;Forest
            aibattlefield=Plains
            ailibrary=Plains;Plains;Plains;Plains;Plains
            """.trimIndent()

        fun startScry1() = startPuzzle(scryState, name = "Scry 1")

        test("scry 1 — GroupReq shape") {
            startScry1()

            val req = castSpellUntilGroupReq("Wall of Runes")
            assertSoftly {
                req.context shouldBe GroupingContext.Scry_a0f6
                req.instanceIdsList shouldHaveSize 1
                req.groupSpecsList shouldHaveSize 2
                req.groupSpecsList[0].zoneType shouldBe ZoneType.Library
                req.groupSpecsList[0].subZoneType shouldBe SubZoneType.Top
                req.groupSpecsList[1].zoneType shouldBe ZoneType.Library
                req.groupSpecsList[1].subZoneType shouldBe SubZoneType.Bottom
            }
        }

        test("scry 1 — put on bottom") {
            startScry1()

            val cardIds = castSpellUntilGroupReq("Wall of Runes").instanceIdsList

            respondToScry(bottomInstanceIds = cardIds, allInstanceIds = cardIds)

            // Wall of Runes on battlefield
            human
                .getZone(ForgeZoneType.Battlefield)
                .cards
                .filter { it.name == "Wall of Runes" } shouldHaveSize 1

            // Scry annotation emitted
            val scryAnn =
                allMessages
                    .flatMap { if (it.hasGameStateMessage()) it.gameStateMessage.annotationsList else emptyList() }
                    .firstOrNull { ann -> ann.typeList.any { it == AnnotationType.Scry_af5a } }
            assertSoftly {
                scryAnn.shouldNotBeNull()
                scryAnn.affectedIdsList shouldBe cardIds
                scryAnn.detailIntList("topIds") shouldBe emptyList()
                scryAnn.detailIntList("bottomIds") shouldBe cardIds
            }

            // Grizzly Bears moved to bottom — library top is now Forest
            human
                .getZone(ForgeZoneType.Library)
                .cards
                .first()
                .name shouldBe "Forest"
        }

        test("scry 1 — keep on top") {
            startScry1()

            val cardIds = castSpellUntilGroupReq("Wall of Runes").instanceIdsList

            respondToScry(bottomInstanceIds = emptyList(), allInstanceIds = cardIds)

            // Grizzly Bears still on library top
            human
                .getZone(ForgeZoneType.Library)
                .cards
                .first()
                .name shouldBe "Grizzly Bears"
        }

        test("scry 2 — keep both on top emits OrderReq and applies order") {
            startPuzzle(
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Witching Well
                humanbattlefield=Island
                humanlibrary=Mountain;Forest;Island;Swamp;Plains
                ailibrary=Plains;Plains;Plains;Plains;Plains
                """,
                name = "Scry 2 top order",
            )

            val groupReq = castSpellUntilGroupReq("Witching Well")
            val groupIds = groupReq.instanceIdsList
            groupIds shouldHaveSize 2

            val orderMsg =
                after {
                    respondToScry(bottomInstanceIds = emptyList(), allInstanceIds = groupIds)
                }.messages.last { it.hasOrderReq() }
            val orderReq = orderMsg.orderReq
            val orderedIds = orderReq.idsList.reversed()
            val orderedNames = orderedIds.map(::cardName)

            assertSoftly {
                orderMsg.type shouldBe GREMessageType.OrderReq_695e
                orderMsg.prompt.promptId shouldBe PromptIds.ORDER_LIBRARY_TOP
                orderReq.orderingContext shouldBe OrderingContext.None_a89f
                orderReq.idsList.map(::cardName) shouldBe groupIds.map(::cardName)
            }

            respondToOrder(orderedIds)

            val annotation =
                allMessages
                    .flatMap { if (it.hasGameStateMessage()) it.gameStateMessage.annotationsList else emptyList() }
                    .last { AnnotationType.Scry_af5a in it.typeList }
            assertSoftly {
                human
                    .getZone(ForgeZoneType.Library)
                    .cards
                    .take(2)
                    .map { it.name } shouldBe orderedNames
                annotation.detailIntList("topIds") shouldBe orderedIds
                annotation.detailIntList("bottomIds") shouldBe emptyList()
            }
        }
    })
