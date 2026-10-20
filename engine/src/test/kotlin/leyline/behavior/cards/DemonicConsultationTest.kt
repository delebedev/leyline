package leyline.behavior.cards

import forge.game.player.GameLossReason
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import leyline.tooling.headless.clientMessage
import wotc.mtgo.gre.external.messaging.Messages.*

class DemonicConsultationTest :
    SessionTest({
        val puzzle =
            """
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanbattlefield=Swamp
            humanhand=Demonic Consultation;Forest
            humanlibrary=Plains;Plains;Plains;Plains;Plains;Plains;Plains;Grizzly Bears;Mountain;Mountain
            ailibrary=Plains;Plains;Plains;Plains;Plains
            """.trimIndent()

        for (name in listOf("Grizzly Bears", "Plains", "Island")) {
            session("Consultation resolves the explicit name $name before continued play", puzzle = puzzle, fullControl = true, turns = 5) {
                castSpellByName("Demonic Consultation") shouldBe true
                passUntil(maxPasses = 4) { allMessages.any { it.hasStringInputReq() } } shouldBe true
                val prompt = allMessages.last { it.hasStringInputReq() }
                assertSoftly {
                    cardName(prompt.stringInputReq.sourceId) shouldBe "Demonic Consultation"
                    prompt.allowCancel shouldBe AllowCancel.No_a526
                    human.getZone(ZoneType.Library).size() shouldBe 10
                    human.getZone(ZoneType.Exile).size() shouldBe 0
                }
                if (name == "Grizzly Bears") {
                    for ((seat, state, responseId) in listOf(
                        Triple(2, prompt.gameStateId, prompt.msgId),
                        Triple(1, prompt.gameStateId - 1, prompt.msgId),
                        Triple(1, prompt.gameStateId, prompt.msgId - 1),
                    )) {
                        send(
                            clientMessage(ClientMessageType.StringInputResp_097b) {
                                systemSeatId = seat
                                gameStateId = state
                                respId = responseId
                                setStringInputResp(StringInputResp.newBuilder().setInputValue("Island"))
                            },
                        )
                        drainSink()
                        human.getZone(ZoneType.Library).size() shouldBe 10
                        allMessages.count { it.hasStringInputReq() } shouldBe 1
                    }
                }
                send(
                    clientMessage(ClientMessageType.StringInputResp_097b) {
                        systemSeatId = 1
                        gameStateId = prompt.gameStateId
                        respId = prompt.msgId
                        setStringInputResp(StringInputResp.newBuilder().setInputValue(name))
                    },
                )
                drainSink()
                passUntilResolved()
                val found = name != "Island"
                val remaining =
                    when (name) {
                        "Grizzly Bears" -> listOf("Mountain", "Mountain")
                        "Plains" -> listOf("Grizzly Bears", "Mountain", "Mountain")
                        else -> emptyList()
                    }
                assertSoftly {
                    human.getZone(ZoneType.Library).cards.map { it.name } shouldBe remaining
                    human.getZone(ZoneType.Exile).size() shouldBe
                        when (name) {
                            "Grizzly Bears" -> 7
                            "Plains" -> 6
                            else -> 10
                        }
                    human.getZone(ZoneType.Hand).cards.map { it.name } shouldBe if (found) listOf("Forest", name) else listOf("Forest")
                    human.getZone(ZoneType.Graveyard).cards.map { it.name } shouldBe listOf("Demonic Consultation")
                    game().isGameOver shouldBe false
                }
                assertSoftly {
                    playLand("Forest") shouldBe true
                    human.battlefield.card("Forest").name shouldBe "Forest"
                }
                if (name == "Island") {
                    passUntil(maxPasses = 40) { game().isGameOver } shouldBe true
                    assertSoftly {
                        human.hasLost() shouldBe true
                        human.outcome.lossState shouldBe GameLossReason.Milled
                    }
                }
            }
        }

        session("Invalid names re-prompt without selecting a default or changing the library", puzzle = puzzle, fullControl = true) {
            castSpellByName("Demonic Consultation") shouldBe true
            passUntil(maxPasses = 4) { allMessages.any { it.hasStringInputReq() } } shouldBe true
            for (name in listOf("", "Not a real card name", "Lost Mine of Phandelver")) {
                val prompt = allMessages.last { it.hasStringInputReq() }
                val priorCount = allMessages.count { it.hasStringInputReq() }
                send(
                    clientMessage(ClientMessageType.StringInputResp_097b) {
                        systemSeatId = 1
                        gameStateId = prompt.gameStateId
                        respId = prompt.msgId
                        setStringInputResp(StringInputResp.newBuilder().setInputValue(name))
                    },
                )
                drainSink()
                assertSoftly {
                    allMessages.count { it.hasStringInputReq() } shouldBe priorCount + 1
                    human.getZone(ZoneType.Library).size() shouldBe 10
                    human.getZone(ZoneType.Exile).size() shouldBe 0
                }
            }
            val prompt = allMessages.last { it.hasStringInputReq() }
            send(
                clientMessage(ClientMessageType.StringInputResp_097b) {
                    systemSeatId = 1
                    gameStateId = prompt.gameStateId
                    respId = prompt.msgId
                    setStringInputResp(StringInputResp.newBuilder().setInputValue("Grizzly Bears"))
                },
            )
            drainSink()
            passUntilResolved()
            human.hand.card("Grizzly Bears").name shouldBe "Grizzly Bears"
        }
    })
