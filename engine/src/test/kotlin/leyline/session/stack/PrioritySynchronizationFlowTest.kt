package leyline.session.stack

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import leyline.bridge.handoff.PendingActionKind
import leyline.bridge.types.SeatId
import leyline.testkit.SessionTest
import wotc.mtgo.gre.external.messaging.Messages.AutoPassOption
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.SettingsMessage

class PrioritySynchronizationFlowTest :
    SessionTest({
        val puzzle =
            """
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20

            humanbattlefield=Forest;Forest
            humanhand=Runeclaw Bear
            humanlibrary=Forest;Forest;Forest
            aibattlefield=Mountain
            ailibrary=Mountain;Mountain;Mountain
            """.trimIndent()

        test("manual synchronization publishes the next Visible action window") {
            startPuzzle(puzzle, name = "Manual synchronization", turns = 3)
            val before = harness.messageSnapshot()

            castSpellByName("Runeclaw Bear").shouldBeTrue()

            val pending = checkNotNull(harness.bridge.actionBridge(SeatId(1)).getPending())
            assertSoftly {
                pending.state.kind shouldBe PendingActionKind.PRIORITY
                harness.messagesSince(before).any { it.hasActionsAvailableReq() }.shouldBeTrue()
                harness.bridge.cutCoordinator
                    .hasCommittedBatches(SeatId(1))
                    .shouldBeFalse()
            }
            harness.bridge.throwIfGameLoopFailed()
        }

        test("explicit auto-resolve advances through a second SyncOnly stop without an action request") {
            startPuzzle(puzzle, name = "Automatic synchronization", turns = 3)
            harness.session.autoPassState.update(
                SettingsMessage
                    .newBuilder()
                    .setAutoPassOption(AutoPassOption.ResolveMyStackEffects)
                    .build(),
            )
            val before = harness.messageSnapshot()

            castSpellByName("Runeclaw Bear").shouldBeTrue()

            val emitted = harness.messagesSince(before)
            val significant = emitted.filter { it.hasGameStateMessage() || it.hasActionsAvailableReq() }.map { it.type }
            val firstActionRequest = significant.indexOfFirst { it == GREMessageType.ActionsAvailableReq_695e }
            assertSoftly {
                firstActionRequest shouldBeGreaterThanOrEqual 2
                significant
                    .take(firstActionRequest)
                    .all {
                        it == GREMessageType.GameStateMessage_695e
                    }.shouldBeTrue()
                harness.bridge
                    .actionBridge(SeatId(1))
                    .getPending()
                    ?.state
                    ?.kind shouldBe PendingActionKind.PRIORITY
                harness.bridge.cutCoordinator
                    .hasCommittedBatches(SeatId(1))
                    .shouldBeFalse()
                game().stack.isEmpty.shouldBeTrue()
                human.getZone(ZoneType.Battlefield).cards.map { it.name } shouldContain "Runeclaw Bear"
            }
            harness.bridge.throwIfGameLoopFailed()
        }
    })
