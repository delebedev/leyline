package leyline.mechanics.omen

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import leyline.bridge.handoff.PendingActionState
import leyline.bridge.handoff.PlayerAction
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.mapping.buildPriorityActionsForTest
import leyline.game.mapping.projectPriorityWindowForTest
import leyline.game.snapshot.SnapshotCapture
import leyline.testkit.BoardTest
import leyline.testkit.humanPlayer
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Omen face-cast action — `CastOmen` (action type 24).
 *
 * Omen cards have a Secondary state (subtype "Omen") with its own spell that
 * the player may cast for the Omen mana cost. Each emit carries
 * `actionType + instanceId + manaCost` only — Omen identity is encoded by
 * `actionType` alone (no grpId / facetId / abilityGrpId / alternativeGrpId).
 * Both the main cast and the Omen cast are offered simultaneously when their
 * costs are payable.
 *
 * Test card: Riling Dawnbreaker (grpId 95536) — main face {4}{W} 3/4 Dragon,
 * Omen face Signaling Roar {1}{W} sorcery (create a 2/2 Soldier token, then
 * shuffle into library).
 */
@Suppress("WeakAssertionOnly")
class OmenActionTest :
    BoardTest({

        fun omenOffers(
            actions: List<Action>,
            iid: Int,
        ): List<Action> = actions.filter { it.actionType == ActionType.CastOmen && it.instanceId == iid }

        test("Omen card in hand with both costs payable → both Cast and CastOmen offered") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    repeat(5) { addCard("Plains", human, ZoneType.Battlefield) }
                    addCard("Riling Dawnbreaker", human, ZoneType.Hand)
                }
            val human = game.humanPlayer
            val card = human.getZone(ZoneType.Hand).cards.first { it.isAdventureCard || it.name == "Riling Dawnbreaker" }
            val iid = b.getOrAllocInstanceId(ForgeCardId(card.id)).value

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val actionBridge = b.seat(SeatId(1)).action
            val selected =
                CompletableFuture.supplyAsync {
                    actionBridge.awaitAction(
                        PendingActionState("Main1", 1, activePlayerId = 1, priorityPlayerId = 1),
                    )
                }
            val pending =
                (1..10_000)
                    .asSequence()
                    .map {
                        Thread.yield()
                        actionBridge.getPending()
                    }.filterNotNull()
                    .firstOrNull()
                    .shouldNotBeNull()
            val projection = projectPriorityWindowForTest(1, snap, b)
            val actions = projection.actions

            val mainCast = actions.actionsList.firstOrNull { it.actionType == ActionType.Cast && it.instanceId == iid }
            val omen = omenOffers(actions.actionsList, iid).firstOrNull()
            val omenOffer = projection.offers.single { it.action == omen }
            actionBridge.bindActionCatalog(pending.actionId, 12, projection.offers) shouldBe true
            actionBridge.submitActionToken(pending.actionId, omenOffer.token) shouldBe true
            val command = selected.get(2, TimeUnit.SECONDS).shouldBeInstanceOf<PlayerAction.CastSpell>()
            assertSoftly {
                mainCast shouldNotBe null
                omen shouldNotBe null
                // Minimal envelope — Omen face is encoded by actionType alone.
                omen!!.grpId shouldBe 0
                omen.facetId shouldBe 0
                omen.abilityGrpId shouldBe 0
                omen.alternativeGrpId shouldBe 0
                omen.manaCostCount shouldNotBe 0
                omenOffer.spellGrpId shouldBe 95537
                command.ability shouldNotBe null
                command.ability?.cardStateName shouldBe forge.card.CardStateName.Secondary
            }
        }

        test("Omen card with only Omen cost payable → only CastOmen offered (active), main Cast inactive") {
            // 2 Plains: Omen {1}{W} payable, main {4}{W} not.
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Plains", human, ZoneType.Battlefield)
                    addCard("Plains", human, ZoneType.Battlefield)
                    addCard("Riling Dawnbreaker", human, ZoneType.Hand)
                }
            val human = game.humanPlayer
            val iid = human.hand.iid("Riling Dawnbreaker")

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val actions = buildPriorityActionsForTest(1, snap, b)

            val activeOmen =
                actions.actionsList.firstOrNull { it.actionType == ActionType.CastOmen && it.instanceId == iid }
            val activeMain =
                actions.actionsList.firstOrNull { it.actionType == ActionType.Cast && it.instanceId == iid }
            val inactiveMain =
                actions.inactiveActionsList.firstOrNull { it.actionType == ActionType.Cast && it.instanceId == iid }
            assertSoftly {
                activeOmen shouldNotBe null
                activeMain shouldBe null
                inactiveMain shouldNotBe null
            }
        }

        test("Omen card in graveyard → no CastOmen offer") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    repeat(5) { addCard("Plains", human, ZoneType.Battlefield) }
                    addCard("Riling Dawnbreaker", human, ZoneType.Graveyard)
                }
            val human = game.humanPlayer
            val iid = human.graveyard.iid("Riling Dawnbreaker")

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val actions = buildPriorityActionsForTest(1, snap, b)

            omenOffers(actions.actionsList, iid).shouldBeEmpty()
            omenOffers(actions.inactiveActionsList, iid).shouldBeEmpty()
        }

        test("snapshot exposes isOmenCard flag") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Riling Dawnbreaker", human, ZoneType.Hand)
                }
            val card =
                game.humanPlayer
                    .getZone(ZoneType.Hand)
                    .cards
                    .first()

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val cardSnap = snap.objects[ForgeCardId(card.id)]
            assertSoftly {
                cardSnap shouldNotBe null
                cardSnap!!.isOmenCard shouldBe true
            }
        }
    })
