package leyline.bridge.coord

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import leyline.bridge.forge.PlayerController
import leyline.bridge.handoff.PromptSideEffect
import leyline.bridge.handoff.PublishedStaticChoiceInteraction
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
                Case("Card", listOf("Creature"), StaticList.CardTypes, listOf(2)),
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

        test("unmapped offered values retain whole-domain fallback without a partial prompt") {
            val board = startPuzzleAtMain1(puzzle)
            val controller = board.human.controller as PlayerController
            val source = board.human.battlefield.card("Island")
            val sa = source.spellAbilities.first()
            assertSoftly {
                controller.chooseSomeType("Card", sa, listOf("Stickers", "Land"), false) shouldBe "Stickers"
                controller.chooseSomeType("Card", sa, listOf("Land", "Stickers"), true) shouldBe null
                controller.chooseSomeType("Card", sa, listOf("Goblin", "Human"), false) shouldBe "Goblin"
                controller.chooseSomeType("Card", sa, emptyList(), false) shouldBe null
                board.bridge.cutCoordinator.staticChoices
                    .current() shouldBe null
            }
        }
    })
