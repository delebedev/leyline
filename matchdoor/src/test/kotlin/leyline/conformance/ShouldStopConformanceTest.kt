package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import leyline.ConformanceTag
import leyline.game.mapper.ShouldStopEvaluator
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Validates [ShouldStopEvaluator] against real Arena ActionsAvailableReq
 * messages captured from proxy recordings.
 *
 * Each golden `.bin` is a raw [MatchServiceToClientMessage] containing a GRE
 * bundle with an [ActionsAvailableReq]. The test extracts every action and
 * asserts our evaluator matches the real shouldStop value.
 */
class ShouldStopConformanceTest :
    FunSpec({

        tags(ConformanceTag)

        fun loadAAR(resource: String): ActionsAvailableReq {
            val bytes = javaClass.classLoader.getResourceAsStream("golden/$resource")?.readBytes()
                ?: error("Golden not found: golden/$resource")
            val payload = MatchServiceToClientMessage.parseFrom(bytes)
            return payload.greToClientEvent.greToClientMessagesList
                .first { it.type == GREMessageType.ActionsAvailableReq_695e }
                .actionsAvailableReq
        }

        fun assertShouldStop(label: String, aar: ActionsAvailableReq) {
            aar.actionsList.shouldNotBeEmpty()
            val mismatches = mutableListOf<String>()
            for (action in aar.actionsList) {
                val expected = ShouldStopEvaluator.shouldStop(action.actionType)
                if (expected != action.shouldStop) {
                    mismatches.add(
                        "${action.actionType.name}: evaluator=$expected, arena=${action.shouldStop}" +
                            " (instanceId=${action.instanceId}, grpId=${action.grpId})",
                    )
                }
            }
            mismatches shouldBe emptyList<String>()
        }

        test("diverse main phase — Cast + Play + Activate + ActivateMana + Pass + FloatMana") {
            val aar = loadAAR("aar-diverse-main-phase.bin")
            assertShouldStop("diverse", aar)
        }

        test("activate-only — Activate + ActivateMana + Pass + FloatMana") {
            val aar = loadAAR("aar-activate-only.bin")
            assertShouldStop("activate-only", aar)
        }

        test("minimal — Activate + Pass") {
            val aar = loadAAR("aar-minimal-activate-pass.bin")
            assertShouldStop("minimal", aar)
        }
    })
