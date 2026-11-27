package leyline.session.combat

import forge.game.card.CounterEnumType
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.game.bundle.InvariantCheck
import leyline.game.bundle.InvariantSelection
import leyline.testkit.SessionTest
import leyline.testkit.TestCardInjector
import leyline.testkit.clientMessage
import leyline.tooling.headless.planeswalkerDamageRecipient
import wotc.mtgo.gre.external.messaging.Messages.ClientMessageType
import wotc.mtgo.gre.external.messaging.Messages.DamageRecType
import wotc.mtgo.gre.external.messaging.Messages.DeclareAttackersResp

/**
 * protocol: DeclareAttackersReq.resp-auto — `autoDeclare=true` declares every creature with an ordinary attack row
 * against `autoDeclareDamageRecipient`.
 * protocol: DeclareAttackersReq.echo — the follow-up request carries each declared creature's selected recipient.
 */
class AutoDeclareRecipientTest :
    SessionTest({
        val validation =
            InvariantSelection.protocolFactsExcept(
                "combat playback can skip queued gsIds in some lanes",
                InvariantCheck.GsIdMonotonicity,
            )

        session(
            "attack all declares every attacker against the chosen planeswalker",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Mountain
                humanlibrary=Mountain;Mountain;Mountain
                ailibrary=Mountain;Mountain;Mountain
                """,
            turns = 5,
            validation = validation,
        ) {
            val attackers =
                List(2) {
                    TestCardInjector.inject(bridge, HUMAN_SEAT, "Raging Goblin", ZoneType.Battlefield, sick = false).instanceId
                }
            val planeswalker = TestCardInjector.inject(bridge, OPPONENT_SEAT, "Liliana of the Veil", ZoneType.Battlefield)
            planeswalker.card.setCounters(CounterEnumType.LOYALTY, 2)
            val startTurn = turn()

            val promptSnap = messageSnapshot()
            passUntil(maxPasses = 5) { messagesSince(promptSnap).any { it.hasDeclareAttackersReq() } }.shouldBeTrue()

            val recipient = planeswalkerDamageRecipient(planeswalker.instanceId)
            val echoSnap = messageSnapshot()
            send(
                submitWithGsId(
                    clientMessage(ClientMessageType.DeclareAttackersResp_097b) {
                        setDeclareAttackersResp(
                            DeclareAttackersResp.newBuilder().setAutoDeclare(true).setAutoDeclareDamageRecipient(recipient),
                        )
                    },
                ),
            )
            allMessages.addAll(sink.messages)
            allRawMessages.addAll(sink.rawMessages)
            accumulator.processAll(sink.messages)
            sink.clear()

            val echo = messagesSince(echoSnap).lastOrNull { it.hasDeclareAttackersReq() }?.declareAttackersReq
            withClue("auto-declare produced no DeclareAttackersReq echo") { echo.shouldNotBeNull() }
            val selected = echo!!.attackersList.filter { it.hasSelectedDamageRecipient() }
            assertSoftly {
                selected.map { it.attackerInstanceId } shouldContainExactly attackers
                selected.map { it.selectedDamageRecipient.type }.distinct() shouldContainExactly listOf(DamageRecType.PlanesWalker)
                selected.map { it.selectedDamageRecipient.planeswalkerInstanceId }.distinct() shouldContainExactly
                    listOf(planeswalker.instanceId)
            }

            submitAttackers()
            passThroughCombat(startTurn)

            assertSoftly {
                ai.life shouldBe 20
                ai
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .firstOrNull { it.id == planeswalker.forgeCardId }
                    .shouldBeNull()
            }
        }
    })
