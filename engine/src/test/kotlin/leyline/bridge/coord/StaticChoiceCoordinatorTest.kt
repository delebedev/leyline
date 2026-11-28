package leyline.bridge.coord

import forge.game.player.PlayerController.BinaryChoiceType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import leyline.bridge.forge.PlayerController
import leyline.bridge.handoff.AutomaticAnswerRule
import leyline.bridge.handoff.PromptSideEffect
import leyline.bridge.handoff.PublishedStaticChoiceInteraction
import leyline.bridge.handoff.ResolvedPromptRoute
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.bridge.types.StaticChoiceIds
import leyline.testkit.BoardTest
import leyline.testkit.selectNResp
import wotc.mtgo.gre.external.messaging.Messages.StaticList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class StaticChoiceCoordinatorTest :
    BoardTest({
        val puzzle =
            """
            [metadata]
            Name:static type choice
            Goal:Win
            Turns:1

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanbattlefield=Island
            humanlibrary=Forest
            ailibrary=Forest
            """.trimIndent()

        data class Case(
            val domain: String,
            val options: List<String>,
            val list: StaticList,
            val ids: List<Int>,
            val optional: Boolean = false,
        )

        val cases =
            listOf(
                Case("Card", listOf("Land", "Creature"), StaticList.CardTypes, listOf(5, 2)),
                Case("Card", listOf("land", "Creature"), StaticList.CardTypes, listOf(5, 2)),
                Case("Card", listOf("Stickers", "Land", "Creature"), StaticList.CardTypes, listOf(5, 2)),
                Case("Card", listOf("Land"), StaticList.CardTypes, listOf(5), optional = true),
                Case("Creature", listOf("Goblin", "Human"), StaticList.SubTypes, listOf(34, 39)),
                Case(
                    "Basic Land",
                    listOf("Forest", "Island"),
                    StaticList.SubTypes,
                    listOf(StaticChoiceIds.subtypeIdFor("Forest")!!, StaticChoiceIds.subtypeIdFor("Island")!!),
                ),
            )

        for (case in cases) {
            test("${case.domain} choice preserves exact offered values ${case.options} with optional=${case.optional}") {
                val board = startPuzzleAtMain1(puzzle)
                val coordinator = board.bridge.cutCoordinator
                coordinator.drain(SeatId(1))
                val controller = board.human.controller as PlayerController
                val source = board.human.battlefield.card("Island")
                val journal = board.bridge.promptBridge(SeatId(1)).journal
                journal.record(
                    PromptSideEffect.RevealStarted(
                        listOf(
                            ForgeCardId(
                                board.ai.library
                                    .card("Forest")
                                    .id,
                            ),
                        ),
                        SeatId(2),
                    ),
                )
                val unrelatedReveal = journal.activeRevealEntry()
                val result = AtomicReference<String?>()
                val failure = AtomicReference<Throwable?>()
                val finished = CountDownLatch(1)
                Thread {
                    try {
                        board.bridge.promptBridge(SeatId(1)).setDiagnosticContext(board.game, Thread.currentThread())
                        result.set(controller.chooseSomeType(case.domain, source.spellAbilities.first(), case.options, case.optional))
                    } catch (error: Throwable) {
                        failure.set(error)
                    } finally {
                        finished.countDown()
                    }
                }.start()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                var published: PublishedStaticChoiceInteraction? = coordinator.staticChoices.current()
                while (published == null && System.nanoTime() < deadline) {
                    Thread.onSpinWait()
                    published = coordinator.staticChoices.current()
                }
                val interaction = checkNotNull(published)
                val req =
                    coordinator
                        .drain(SeatId(1))
                        .flatten()
                        .single { it.hasSelectNReq() }
                        .selectNReq
                val selected = if (case.optional) emptyList() else listOf(case.ids.last())
                assertSoftly {
                    req.staticList shouldBe case.list
                    req.idsList shouldBe case.ids
                    req.minSel shouldBe if (case.optional) 0 else 1
                    req.maxSel shouldBe 1
                    req.unfilteredIdsList shouldBe emptyList()
                    coordinator.acceptSettled(
                        selectNResp(
                            listOf(
                                if (case.list ==
                                    StaticList.CardTypes
                                ) {
                                    10
                                } else {
                                    9999
                                },
                            ),
                        ),
                        interaction.gameStateId,
                    ) shouldBe
                        false
                    coordinator.acceptSettled(selectNResp(selected), interaction.gameStateId) shouldBe true
                    finished.await(3, TimeUnit.SECONDS) shouldBe true
                    failure.get() shouldBe null
                    result.get() shouldBe if (case.optional) null else case.options.last()
                    coordinator.staticChoices.current() shouldBe null
                    journal.activeRevealEntry() shouldBe unrelatedReveal
                }
            }
        }

        for ((selectedId, expected) in listOf(1 to true, 2 to false)) {
            test("sourceless confirmation asks Yes or No as a named choice and returns option $selectedId") {
                val board = startPuzzleAtMain1(puzzle)
                val coordinator = board.bridge.cutCoordinator
                coordinator.drain(SeatId(1))
                val controller = board.human.controller as PlayerController
                val result = AtomicReference<Boolean?>()
                val failure = AtomicReference<Throwable?>()
                val finished = CountDownLatch(1)
                Thread {
                    try {
                        board.bridge.promptBridge(SeatId(1)).setDiagnosticContext(board.game, Thread.currentThread())
                        result.set(controller.confirmAction(null, null, "Continue?", null, null, null))
                    } catch (error: Throwable) {
                        failure.set(error)
                    } finally {
                        finished.countDown()
                    }
                }.start()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                var published = coordinator.staticChoices.current()
                while (published == null && System.nanoTime() < deadline) {
                    Thread.onSpinWait()
                    published = coordinator.staticChoices.current()
                }
                val interaction = checkNotNull(published)
                val req =
                    coordinator
                        .drain(SeatId(1))
                        .flatten()
                        .single { it.hasSelectNReq() }
                        .selectNReq
                assertSoftly {
                    req.prompt.parametersList.map { it.stringValue } shouldBe listOf("Yes", "No")
                    req.idsList shouldBe listOf(1, 2)
                    req.minSel shouldBe 1
                    req.maxSel shouldBe 1
                    coordinator.acceptSettled(selectNResp(listOf(selectedId)), interaction.gameStateId) shouldBe true
                    finished.await(3, TimeUnit.SECONDS) shouldBe true
                    failure.get() shouldBe null
                    result.get() shouldBe expected
                    board.bridge
                        .promptBridge(SeatId(1))
                        .defaultHistory
                        .none { it.route is ResolvedPromptRoute.AutoResolve } shouldBe true
                }
            }
        }

        for (selectedId in listOf(0, 1)) {
            test("confirmation materializes parity order and returns the selected Boolean for static id $selectedId") {
                val board = startPuzzleAtMain1(puzzle)
                val coordinator = board.bridge.cutCoordinator
                coordinator.drain(SeatId(1))
                val controller = board.human.controller as PlayerController
                val source = board.human.battlefield.card("Island")
                val result = AtomicReference<Boolean?>()
                val failure = AtomicReference<Throwable?>()
                val finished = CountDownLatch(1)
                Thread {
                    try {
                        board.bridge.promptBridge(SeatId(1)).setDiagnosticContext(board.game, Thread.currentThread())
                        result.set(
                            controller.confirmAction(
                                source.spellAbilities.first(),
                                null,
                                "Parity?",
                                mutableListOf("Odd", "Even"),
                                source,
                                null,
                            ),
                        )
                    } catch (error: Throwable) {
                        failure.set(error)
                    } finally {
                        finished.countDown()
                    }
                }.start()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                var published = coordinator.staticChoices.current()
                while (published == null && System.nanoTime() < deadline) {
                    Thread.onSpinWait()
                    published = coordinator.staticChoices.current()
                }
                val interaction = checkNotNull(published)
                val req =
                    coordinator
                        .drain(SeatId(1))
                        .flatten()
                        .single { it.hasSelectNReq() }
                        .selectNReq
                assertSoftly {
                    req.staticList shouldBe StaticList.Parities
                    // Parity offers the implicit complete static domain, not an explicit ID subset.
                    req.idsList shouldBe emptyList()
                    req.sourceId shouldBe board.human.battlefield.iid("Island")
                    req.minSel shouldBe 1
                    req.maxSel shouldBe 1
                    coordinator.acceptSettled(selectNResp(listOf(9999)), interaction.gameStateId) shouldBe false
                    coordinator.acceptSettled(selectNResp(listOf(selectedId)), interaction.gameStateId) shouldBe true
                    finished.await(3, TimeUnit.SECONDS) shouldBe true
                    failure.get() shouldBe null
                    result.get() shouldBe (selectedId == 1)
                    coordinator.staticChoices.current() shouldBe null
                }
            }
        }

        test("a coin flip call answers without a prompt under its named rule") {
            val board = startPuzzleAtMain1(puzzle)
            val controller = board.human.controller as PlayerController
            val bridge = board.bridge.promptBridge(SeatId(1))
            val before = bridge.history.size
            controller.chooseBinary(null, "Call coin flip", BinaryChoiceType.HeadsOrTails, true) shouldBe true
            assertSoftly {
                (
                    bridge.history
                        .drop(before)
                        .single()
                        .route as ResolvedPromptRoute.AutoResolve
                ).rule shouldBe
                    AutomaticAnswerRule.CoinFlipCall
                board.bridge.cutCoordinator.staticChoices
                    .current() shouldBe null
            }
        }

        test("on the engine thread a native type choice that shows one enum value answers it without a prompt") {
            val board = startPuzzleAtMain1(puzzle)
            val controller = board.human.controller as PlayerController
            val sa =
                board.human.battlefield
                    .card("Island")
                    .spellAbilities
                    .first()
            val bridge = board.bridge.promptBridge(SeatId(1))
            val before = bridge.history.size
            val result = AtomicReference<String?>()
            val failure = AtomicReference<Throwable?>()
            val worker =
                Thread {
                    try {
                        bridge.setDiagnosticContext(board.game, Thread.currentThread())
                        result.set(controller.chooseSomeType("Card", sa, listOf("Stickers", "Land"), false))
                    } catch (error: Throwable) {
                        failure.set(error)
                    }
                }
            worker.start()
            worker.join(TimeUnit.SECONDS.toMillis(3))
            assertSoftly {
                failure.get() shouldBe null
                result.get() shouldBe "Land"
                board.bridge.cutCoordinator.staticChoices
                    .current() shouldBe null
                (
                    bridge.history
                        .drop(before)
                        .single()
                        .route as? ResolvedPromptRoute.AutoResolve
                )?.rule shouldBe
                    AutomaticAnswerRule.NoChoiceAmongShownOptions
            }
        }

        test("a required type choice that shows no type answers Forge's first type, not the sorted first") {
            val board = startPuzzleAtMain1(puzzle)
            val controller = board.human.controller as PlayerController
            val sa =
                board.human.battlefield
                    .card("Island")
                    .spellAbilities
                    .first()
            controller.chooseSomeType("Creature", sa, listOf("Zorblax", "Abcdex"), false) shouldBe "Zorblax"
        }

        test("a type choice without a published window falls back to the first type with an enum value") {
            val board = startPuzzleAtMain1(puzzle)
            val controller = board.human.controller as PlayerController
            val sa =
                board.human.battlefield
                    .card("Island")
                    .spellAbilities
                    .first()
            val bridge = board.bridge.promptBridge(SeatId(1))
            val before = bridge.history.size
            controller.chooseSomeType("Card", sa, listOf("Stickers", "Land", "Creature"), false) shouldBe "Land"
            bridge.history
                .drop(before)
                .single()
                .result shouldBe listOf(1)
        }

        test("a type choice with one shown value or none answers without a prompt under its named rule") {
            val board = startPuzzleAtMain1(puzzle)
            val controller = board.human.controller as PlayerController
            val source = board.human.battlefield.card("Island")
            val sa = source.spellAbilities.first()
            val bridge = board.bridge.promptBridge(SeatId(1))
            val before = bridge.history.size
            assertSoftly {
                controller.chooseSomeType("Card", sa, listOf("Stickers", "Land"), false) shouldBe "Land"
                controller.chooseSomeType("Card", sa, listOf("Creature"), false) shouldBe "Creature"
                controller.chooseSomeType("Card", sa, listOf("Stickers"), true) shouldBe null
                controller.chooseSomeType("Card", sa, listOf("Goblin", "Human"), false) shouldBe "Goblin"
                controller.chooseSomeType("Card", sa, emptyList(), false) shouldBe null
                board.bridge.cutCoordinator.staticChoices
                    .current() shouldBe null
                bridge.history.drop(before).map { (it.route as? ResolvedPromptRoute.AutoResolve)?.rule } shouldBe
                    List(5) { AutomaticAnswerRule.NoChoiceAmongShownOptions }
            }
        }
    })
