package leyline.mechanics.cycling

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import leyline.bridge.coord.resolveActionOffer
import leyline.bridge.handoff.ActionResponseKey
import leyline.bridge.handoff.PlayerAction
import leyline.bridge.types.SeatId
import leyline.game.bundle.StateFrameInputCapture
import leyline.game.event.FrameEventLog
import leyline.testkit.SessionTest
import leyline.testkit.allGameObjects
import leyline.testkit.annotationsOfType
import leyline.testkit.beInGraveyardOf
import leyline.testkit.beInHandOf
import leyline.testkit.detailInt
import leyline.testkit.hand
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType

private val CYCLE_MISCALCULATION_PUZZLE =
    """
    [metadata]
    Name:Cycle Miscalculation
    Goal:Cycle and draw
    Turns:1
    Difficulty:Easy

    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=20

    humanhand=Miscalculation
    humanbattlefield=Island;Island
    humanlibrary=Lightning Bolt;Island
    ailibrary=Mountain
    """.trimIndent()

@Suppress("MissingAssertSoftly") // intentional fail-fast — passUntil depends on activation succeeding first
class CyclingPuzzleTest :
    SessionTest({
        session(
            "first cycling with Gavi is offered without mana and pays discard before drawing",
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanhand=Miscalculation;Remote Isle;Ornithopter
                humanbattlefield=Gavi, Nest Warden
                humanlibrary=Forest;Island;Mountain;Forest;Island;Mountain
                ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain;Mountain
                """.trimIndent(),
            turns = 4,
        ) {
            val cardIid = human.hand.iid("Miscalculation")
            val offers =
                allMessages
                    .last { it.hasActionsAvailableReq() }
                    .actionsAvailableReq.actionsList
                    .filter { it.actionType == ActionType.Activate_add3 && it.instanceId == cardIid }
            offers.size shouldBe 1
            val offer = offers.single()
            offer.manaCostCount shouldBe 0
            submitAction(offer)
            passUntilResolved()
            assertSoftly {
                "Miscalculation" should beInGraveyardOf(human)
                "Forest" should beInHandOf(human)
                "Ornithopter" should beInHandOf(human)
                human.numDrawnThisTurn shouldBe 1
            }
            val secondIid = human.hand.iid("Remote Isle")
            assertSoftly {
                allMessages
                    .last { it.hasActionsAvailableReq() }
                    .actionsAvailableReq.actionsList
                    .count { it.actionType == ActionType.Activate_add3 && it.instanceId == secondIid } shouldBe 0
                "Remote Isle" should beInHandOf(human)
            }
            passUntil(maxPasses = 80) {
                game().phaseHandler.turn >= 3 &&
                    allMessages
                        .last { it.hasActionsAvailableReq() }
                        .actionsAvailableReq.actionsList
                        .any { it.actionType == ActionType.Activate_add3 && it.instanceId == secondIid && it.manaCostCount == 0 }
            }.shouldBeTrue()
            game().phaseHandler.turn shouldBe 3
            val resetOffer =
                allMessages
                    .last { it.hasActionsAvailableReq() }
                    .actionsAvailableReq.actionsList
                    .single { it.actionType == ActionType.Activate_add3 && it.instanceId == secondIid }
            submitAction(resetOffer)
            passUntilResolved()
            assertSoftly {
                "Remote Isle" should beInGraveyardOf(human)
                human.numDrawnThisTurn shouldBe 2
                human.getCardsIn(ZoneType.Graveyard).size shouldBe 2
            }
            castSpellByName("Ornithopter").shouldBeTrue()
            passUntilResolved()
        }

        session(
            "free and paid cycling retain distinct offered costs and the second activation pays normally",
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanhand=Miscalculation;Remote Isle;Ornithopter
                humanbattlefield=Gavi, Nest Warden;Island;Island
                humanlibrary=Forest;Mountain;Plains;Forest;Mountain
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
        ) {
            val firstIid = human.hand.iid("Miscalculation")
            val firstOffers =
                allMessages
                    .last { it.hasActionsAvailableReq() }
                    .actionsAvailableReq.actionsList
                    .filter { it.actionType == ActionType.Activate_add3 && it.instanceId == firstIid }
            val window = checkNotNull(bridge.actionBridge(SeatId(1)).getPending()).actionId
            val retained =
                bridge.cutCoordinator.actions
                    .actionOffersForTest(window)
                    .filter { it.action.instanceId == firstIid && it.command is PlayerAction.ActivateAbility }
            val sourceRows =
                allMessages
                    .flatMap { it.gameStateMessage.gameObjectsList }
                    .last { it.instanceId == firstIid }
                    .uniqueAbilitiesList
            assertSoftly {
                firstOffers.size shouldBe 2
                firstOffers.count { it.manaCostCount == 0 } shouldBe 1
                firstOffers.count { it.manaCostCount > 0 } shouldBe 1
                firstOffers
                    .map { action ->
                        listOf(
                            action.actionType.number,
                            action.instanceId,
                            action.grpId,
                            action.facetId,
                            action.abilityGrpId,
                            action.uniqueAbilityId,
                            action.sourceId,
                            action.alternativeGrpId,
                            action.alternativeSourceZcid,
                        )
                    }.distinct()
                    .size shouldBe 2
                retained.size shouldBe 2
                firstOffers.forEach { action ->
                    sourceRows.count { it.id == action.uniqueAbilityId && it.grpId == action.abilityGrpId } shouldBe 1
                }
                val abilities = retained.map { checkNotNull((it.command as PlayerAction.ActivateAbility).ability) }
                (abilities[0] === abilities[1]) shouldBe false
                abilities.map { it.payCosts.totalMana.toString() }.distinct().size shouldBe 2
                abilities.all { it.payCosts.costParts.any { part -> part is forge.game.cost.CostDiscard } } shouldBe true
                val catalog =
                    retained
                        .mapIndexed { index, offer -> index.toLong() to offer }
                        .groupBy { ActionResponseKey.from(it.second.action) }
                retained.forEach { offer ->
                    val response =
                        offer.action
                            .toBuilder()
                            .clearManaCost()
                            .clearAutoTapSolution()
                            .build()
                    val selected = checkNotNull(resolveActionOffer(catalog, response)).second
                    val selectedAbility = (selected.command as PlayerAction.ActivateAbility).ability
                    val offeredAbility = (offer.command as PlayerAction.ActivateAbility).ability
                    (selectedAbility === offeredAbility) shouldBe true
                }
            }
            submitAction(
                firstOffers
                    .single { it.manaCostCount == 0 }
                    .toBuilder()
                    .clearManaCost()
                    .clearAutoTapSolution()
                    .build(),
            )
            passUntilResolved()
            assertSoftly {
                "Miscalculation" should beInGraveyardOf(human)
                "Forest" should beInHandOf(human)
                human.getCardsIn(ZoneType.Battlefield).count { it.isLand && it.isTapped } shouldBe 0
            }
            val secondIid = human.hand.iid("Remote Isle")
            val secondOffers =
                allMessages
                    .last { it.hasActionsAvailableReq() }
                    .actionsAvailableReq.actionsList
                    .filter { it.actionType == ActionType.Activate_add3 && it.instanceId == secondIid }
            assertSoftly {
                secondOffers.size shouldBe 1
                secondOffers.single().manaCostCount shouldBeGreaterThan 0
            }
            submitAction(secondOffers.single())
            passUntilResolved()
            assertSoftly {
                "Remote Isle" should beInGraveyardOf(human)
                "Mountain" should beInHandOf(human)
                human.getCardsIn(ZoneType.Battlefield).count { it.isLand && it.isTapped } shouldBe 2
                human.numDrawnThisTurn shouldBe 2
            }
            castSpellByName("Ornithopter").shouldBeTrue()
            passUntilResolved()
        }

        session(
            "the paid first cycling variant executes its own cost when a free sibling is also offered",
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanhand=Miscalculation;Remote Isle;Ornithopter
                humanbattlefield=Gavi, Nest Warden;Island;Island
                humanlibrary=Forest;Mountain;Plains;Forest;Mountain
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
        ) {
            val iid = human.hand.iid("Miscalculation")
            val offers =
                allMessages
                    .last { it.hasActionsAvailableReq() }
                    .actionsAvailableReq.actionsList
                    .filter { it.actionType == ActionType.Activate_add3 && it.instanceId == iid }
            offers.size shouldBe 2
            val paid = offers.single { it.manaCostCount > 0 }
            submitAction(
                paid
                    .toBuilder()
                    .clearManaCost()
                    .clearAutoTapSolution()
                    .build(),
            )
            passUntilResolved()
            assertSoftly {
                "Miscalculation" should beInGraveyardOf(human)
                "Forest" should beInHandOf(human)
                human.getCardsIn(ZoneType.Battlefield).count { it.isLand && it.isTapped } shouldBe 2
                human.numDrawnThisTurn shouldBe 1
            }
            val secondIid = human.hand.iid("Remote Isle")
            allMessages
                .last { it.hasActionsAvailableReq() }
                .actionsAvailableReq.actionsList
                .count { it.actionType == ActionType.Activate_add3 && it.instanceId == secondIid } shouldBe 0
            castSpellByName("Ornithopter").shouldBeTrue()
            passUntilResolved()
        }

        listOf(false, true).forEach { paid ->
            session(
                "granted cycling ${if (paid) "paid" else "free"} variant pays its retained cost and continues priority",
                puzzle =
                    """
                    ActivePlayer=Human
                    ActivePhase=Main1
                    HumanLife=20
                    AILife=20
                    humanhand=Mountain;Ornithopter
                    humanbattlefield=Tectonic Reformation;Gavi, Nest Warden;Mountain
                    humanlibrary=Forest;Plains;Island;Forest
                    ailibrary=Mountain;Mountain;Mountain
                    """.trimIndent(),
            ) {
                val iid = human.hand.iid("Mountain")
                val actions =
                    allMessages
                        .last { it.hasActionsAvailableReq() }
                        .actionsAvailableReq.actionsList
                        .filter { it.instanceId == iid && it.actionType == ActionType.Activate_add3 }
                actions.size shouldBe 2
                actions.map { it.uniqueAbilityId }.distinct().size shouldBe 2
                val chosen = actions.single { (it.manaCostCount > 0) == paid }
                submitAction(
                    chosen
                        .toBuilder()
                        .clearManaCost()
                        .clearAutoTapSolution()
                        .build(),
                )
                passUntilResolved()
                assertSoftly {
                    "Mountain" should beInGraveyardOf(human)
                    "Forest" should beInHandOf(human)
                    human.getCardsIn(ZoneType.Battlefield).count { it.isLand && it.isTapped } shouldBe if (paid) 1 else 0
                    human.numDrawnThisTurn shouldBe 1
                }
                castSpellByName("Ornithopter").shouldBeTrue()
                passUntilResolved()
            }
        }

        session(
            "cycling without a replacement cost remains unavailable when its mana cannot be paid",
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanhand=Miscalculation;Ornithopter
                humanlibrary=Forest;Island;Mountain
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
        ) {
            val iid = human.hand.iid("Miscalculation")
            val actions = allMessages.last { it.hasActionsAvailableReq() }.actionsAvailableReq
            assertSoftly {
                actions.actionsList.count { it.actionType == ActionType.Activate_add3 && it.instanceId == iid } shouldBe 0
                actions.inactiveActionsList.count { it.actionType == ActionType.Activate_add3 && it.instanceId == iid } shouldBe 1
                "Miscalculation" should beInHandOf(human)
                human.numDrawnThisTurn shouldBe 0
            }
            castSpellByName("Ornithopter").shouldBeTrue()
            passUntilResolved()
        }

        session(
            "Miscalculation cycle from hand draws + discards",
            puzzle = CYCLE_MISCALCULATION_PUZZLE,
        ) {
            // Pre-cycle invariants
            human
                .getZone(ZoneType.Hand)
                .cards
                .any { it.name == "Miscalculation" }
                .shouldBeTrue()
            val handBefore = human.getZone(ZoneType.Hand).size()
            val gyBefore = human.getZone(ZoneType.Graveyard).size()
            val cardIid = human.hand.iid("Miscalculation")
            val offer =
                allMessages
                    .last { it.hasActionsAvailableReq() }
                    .actionsAvailableReq.actionsList
                    .single { it.actionType == ActionType.Activate_add3 && it.instanceId == cardIid }
            offer.abilityGrpId shouldBeGreaterThan 0

            // Cycle Miscalculation — same path as Channel.
            activateAbilityFromHand("Miscalculation").shouldBeTrue()
            // Wait for the Cycling AB to resolve (Discard is part of the cost,
            // Draw is the resolve effect — we need both to land before asserting).
            passUntil(maxPasses = 10) {
                human.getZone(ZoneType.Graveyard).cards.any { it.name == "Miscalculation" } &&
                    human.getZone(ZoneType.Hand).cards.any { it.name == "Lightning Bolt" }
            }.shouldBeTrue()

            assertSoftly {
                human
                    .getZone(ZoneType.Graveyard)
                    .cards
                    .any { it.name == "Miscalculation" }
                    .shouldBeTrue()
                // Hand size: -1 (cycled) +1 (drew Lightning Bolt) = same
                human.getZone(ZoneType.Hand).size() shouldBe handBefore
                human
                    .getZone(ZoneType.Hand)
                    .cards
                    .any { it.name == "Lightning Bolt" }
                    .shouldBeTrue()
                human.getZone(ZoneType.Graveyard).size() shouldBe gyBefore + 1
            }
        }

        session(
            "Remote Isle cycling retains identity without an action projection",
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanhand=Remote Isle
                humanbattlefield=Island;Island
                humanlibrary=Lightning Bolt;Island
                ailibrary=Mountain
                """.trimIndent(),
        ) {
            val cardIid = human.hand.iid("Remote Isle")
            val offer =
                allMessages
                    .last { it.hasActionsAvailableReq() }
                    .actionsAvailableReq.actionsList
                    .single { it.actionType == ActionType.Activate_add3 && it.instanceId == cardIid }
            bridge.clearAbilityRegistryCacheForTesting()
            StateFrameInputCapture(bridge, "cycling-identity", 1).captureNeutral(
                game = game(),
                gameStateId = 99,
                revealForSeat = null,
                events = StateFrameInputCapture.Events.Supplied(FrameEventLog(emptyList())),
            )
            val start = messageSnapshot()

            submitAction(offer)
            passUntilResolved()

            val action =
                messagesSince(start)
                    .annotationsOfType(AnnotationType.UserActionTaken)
                    .single { it.detailInt("actionType") == ActionType.Activate_add3.number }
            val abilityGrpIds =
                messagesSince(start)
                    .allGameObjects()
                    .filter { it.type == GameObjectType.Ability && it.parentId == cardIid }
                    .map { it.grpId }
                    .distinct()
            assertSoftly {
                offer.abilityGrpId shouldBeGreaterThan 0
                action.detailInt("abilityGrpId") shouldBe offer.abilityGrpId
                abilityGrpIds shouldBe listOf(offer.abilityGrpId)
            }
        }
    })
