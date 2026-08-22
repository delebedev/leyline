package leyline.session.stack

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import leyline.bridge.handoff.PendingActionKind
import leyline.testkit.*
import leyline.testkit.SessionTest
import wotc.mtgo.gre.external.messaging.Messages.AutoPassOption
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType

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

        session("manual synchronization publishes the next Visible action window", puzzle = puzzle, turns = 3) {
            val before = messageSnapshot()

            castSpellByName("Runeclaw Bear").shouldBeTrue()

            assertSoftly {
                observe().pendingActionKind shouldBe PendingActionKind.PRIORITY.name
                observe().pendingSynchronization shouldBe false
                messagesSince(before).any { it.hasActionsAvailableReq() }.shouldBeTrue()
                observe().pendingAction shouldBe true
            }
        }

        session("explicit auto-resolve advances through a second SyncOnly stop without an action request", puzzle = puzzle, turns = 3) {
            val before = messageSnapshot()

            setAutoPass(AutoPassOption.ResolveMyStackEffects)
            castSpellByName("Runeclaw Bear").shouldBeTrue()

            val emitted = messagesSince(before)
            val significant = emitted.filter { it.hasGameStateMessage() || it.hasActionsAvailableReq() }.map { it.type }
            val firstActionRequest = significant.indexOfFirst { it == GREMessageType.ActionsAvailableReq_695e }
            assertSoftly {
                firstActionRequest shouldBeGreaterThanOrEqual 2
                significant
                    .take(firstActionRequest)
                    .all {
                        it == GREMessageType.GameStateMessage_695e
                    }.shouldBeTrue()
                observe().pendingActionKind shouldBe PendingActionKind.PRIORITY.name
                observe().pendingSynchronization shouldBe false
                observe().stackSize shouldBe 0
                human.getZone(ZoneType.Battlefield).cards.map { it.name } shouldContain "Runeclaw Bear"
            }
        }
    })
