package leyline.session.replacement

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.should
import leyline.bridge.coord.GameLoopPoller
import leyline.testkit.SessionTest
import leyline.testkit.beMissingFrom
import leyline.testkit.beOnBattlefieldOf
import leyline.testkit.hasCard
import leyline.tooling.headless.HeadlessResponseMode

class OptionalEtbCopyInteractionTest :
    SessionTest({
        session(
            "accepting an optional ETB copy replacement waits for the permanent choice",
            puzzleFile = "data/puzzles/sculpting-steel-copy.pzl",
            responseMode = HeadlessResponseMode.PolicyVisible,
        ) {
            holdNextOptionalAction()

            castSpellByName("Sculpting Steel").shouldBeTrue()
            passUntil(maxPasses = 8) { allMessages.any { it.hasOptionalActionMessage() } }.shouldBeTrue()

            respondToOptionalAction(accept = true)
            GameLoopPoller.awaitCondition(timeoutMs = 5_000) {
                drainSink()
                allMessages.any { it.hasSelectNReq() }
            }
            val req =
                allMessages.lastOrNull { it.hasSelectNReq() }?.selectNReq
                    ?: error(
                        "No SelectNReq; messages=${allMessages.takeLast(12).map { it.type }} " +
                            "nonAction=${bridge.hasPendingNonActionInteraction()}",
                    )
            val solRing = instanceIdOf("Sol Ring", zone = ZoneType.Battlefield)
            req.idsList shouldContain solRing
            respondToSelectN(listOf(solRing))

            assertSoftly {
                "Sol Ring" should beOnBattlefieldOf(human, count = 2)
                "Sculpting Steel" should beMissingFrom(ZoneType.Battlefield, human)
            }
        }

        session(
            "declining an optional ETB copy replacement keeps the original permanent",
            puzzleFile = "data/puzzles/sculpting-steel-copy.pzl",
            responseMode = HeadlessResponseMode.PolicyVisible,
        ) {
            holdNextOptionalAction()

            castSpellByName("Sculpting Steel").shouldBeTrue()
            passUntil(maxPasses = 8) { allMessages.any { it.hasOptionalActionMessage() } }.shouldBeTrue()
            respondToOptionalAction(accept = false)
            GameLoopPoller.awaitCondition(timeoutMs = 5_000) {
                drainSink()
                human.hasCard("Sculpting Steel", ZoneType.Battlefield)
            }

            assertSoftly {
                "Sculpting Steel" should beOnBattlefieldOf(human)
                allMessages.none { it.hasSelectNReq() }.shouldBeTrue()
            }
        }
    })
