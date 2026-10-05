package leyline.bridge.coord

import forge.game.combat.Combat
import forge.game.combat.CombatUtil
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.bridge.handoff.GameActionBridge
import leyline.bridge.handoff.OwnerContext
import leyline.bridge.handoff.PlayerAction
import leyline.bridge.handoff.RuntimeHorizonMode
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.testkit.Board
import leyline.testkit.BoardTest
import leyline.testkit.TestActionWindowRuntime

class PriorityLoopCoordinatorTest :
    BoardTest({
        for (timeout in listOf(0L, 10L)) {
            test("must-block timeout $timeout returns on repeated declarations") {
                val board =
                    startWithBoard { _, human, ai ->
                        addCard("Walking Corpse", human, ZoneType.Battlefield)
                        addCard("Centaur Courser", ai, ZoneType.Battlefield)
                    }
                val blocker = board.human.getCardsIn(ZoneType.Battlefield).first()
                val attacker = board.ai.getCardsIn(ZoneType.Battlefield).first()
                val combat = Combat(board.ai)
                combat.addAttacker(attacker, board.human)
                board.game.phaseHandler.setCombat(combat)
                blocker.addMustBlockCard(1L, attacker)
                CombatUtil.validateBlocks(combat, board.human).shouldNotBeNull()
                var notifications = 0
                val coordinator =
                    coordinator(board, GameActionBridge(timeoutMs = timeout, windowRuntime = TestActionWindowRuntime())) {
                        check(++notifications <= 2) { "Timeout retried a required declaration" }
                    }
                repeat(2) { coordinator.declareBlockers(board.human, combat) }
                assertSoftly {
                    notifications shouldBe 2
                    combat.allBlockers.size shouldBe 0
                }
            }
        }

        test("explicit empty declaration retries and illegal pair rolls back its legal prefix") {
            val board =
                startWithBoard { _, human, ai ->
                    addCard("Walking Corpse", human, ZoneType.Battlefield)
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)
                    addCard("Centaur Courser", ai, ZoneType.Battlefield)
                    addCard("Air Elemental", ai, ZoneType.Battlefield)
                }
            val corpse = board.human.getCardsIn(ZoneType.Battlefield).first { it.name == "Walking Corpse" }
            val bear = board.human.getCardsIn(ZoneType.Battlefield).first { it.name == "Grizzly Bears" }
            val courser = board.ai.getCardsIn(ZoneType.Battlefield).first { it.name == "Centaur Courser" }
            val elemental = board.ai.getCardsIn(ZoneType.Battlefield).first { it.name == "Air Elemental" }
            val combat = Combat(board.ai)
            combat.addAttacker(courser, board.human)
            combat.addAttacker(elemental, board.human)
            board.game.phaseHandler.setCombat(combat)
            corpse.addMustBlockCard(1L, courser)
            val actions =
                listOf(
                    PlayerAction.DeclareBlockers(emptyMap()),
                    PlayerAction.DeclareBlockers(
                        linkedMapOf(
                            ForgeCardId(corpse.id) to ForgeCardId(courser.id),
                            ForgeCardId(bear.id) to ForgeCardId(elemental.id),
                        ),
                    ),
                    PlayerAction.DeclareBlockers(mapOf(ForgeCardId(corpse.id) to ForgeCardId(courser.id))),
                )
            var published = 0
            val delegate = TestActionWindowRuntime { token -> actions[token.toInt() - 1] }
            val runtime =
                object : GameActionBridge.ActionWindowRuntime by delegate {
                    override fun publish(pending: GameActionBridge.PendingAction) {
                        check(published < actions.size) { "Legal retry did not finish" }
                        assertSoftly {
                            combat.allBlockers.size shouldBe 0
                            board.game.stack.size() shouldBe 0
                            board.human.life shouldBe 20
                            board.ai.life shouldBe 20
                        }
                        delegate.publish(pending)
                        pending.future.complete(GameActionBridge.ActionSubmission.RuntimeToken((++published).toLong()))
                    }
                }
            coordinator(board, GameActionBridge(windowRuntime = runtime)) {}.declareBlockers(board.human, combat)
            assertSoftly {
                published shouldBe 3
                combat.getBlockers(courser).toList() shouldBe listOf(corpse)
                combat.getBlockers(elemental).size shouldBe 0
            }
        }
    })

private fun coordinator(
    board: Board,
    actions: GameActionBridge,
    notify: () -> Unit,
): PriorityLoopCoordinator =
    PriorityLoopCoordinator(
        owner =
            object : OwnerContext {
                override fun notifyStateChanged() = notify()
            },
        game = board.game,
        player = board.human,
        actionBridge = actions,
        priorityPolicy = PriorityPolicyRuntime(),
        runtimeHorizonMode = RuntimeHorizonMode.Direct,
        smartPhaseSkip = true,
        spellExecutor = SpellExecutor(board.game, board.human, board.bridge.seat(SeatId(1)).prompt),
        interactionRuntime = board.bridge.cutCoordinator,
    )
