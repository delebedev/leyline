package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import leyline.bridge.SeatId
import leyline.game.ModalAbilityInfo
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Modal ETB flow tests using [MatchFlowHarness] + modal-etb.pzl puzzle.
 *
 * Trufflesnout (2G, 2/2 Boar) has 2 ETB modal choices:
 *   - Mode 0: Put a +1/+1 counter on Trufflesnout
 *   - Mode 1: You gain 4 life
 *
 * Tests verify that casting Trufflesnout produces a CastingTimeOptionsReq
 * with the correct modal options, and that responding with a modal choice
 * resolves the effect correctly.
 */
class ModalETBFlowTest :
    FunSpec({

        tags(IntegrationTag)

        // Synthetic grpIds for modal options — parent + child abilities
        val parentAbilityGrpId = 99001
        val counterModeGrpId = 99002
        val lifeModeGrpId = 99003

        var harness: MatchFlowHarness? = null

        afterEach {
            harness?.shutdown()
            harness = null
        }

        fun setupModal(): MatchFlowHarness {
            val h = MatchFlowHarness(validating = false)
            harness = h

            // connectAndKeepPuzzle initializes Forge card DB + registers cards
            h.connectAndKeepPuzzle("puzzles/modal-etb.pzl")

            // Register modal options AFTER connect (DB is initialized by now).
            // The modal prompt fires during game play, not during connect.
            val trufflesnoutGrpId = TestCardRegistry.repo.findGrpIdByName("Trufflesnout")!!
            TestCardRegistry.repo.registerModalOptions(
                trufflesnoutGrpId,
                ModalAbilityInfo(
                    parentGrpId = parentAbilityGrpId,
                    childGrpIds = listOf(counterModeGrpId, lifeModeGrpId),
                ),
            )

            return h
        }

        test("modal ETB emits CastingTimeOptionsReq") {
            val h = setupModal()
            val trufflesnoutGrpId = TestCardRegistry.repo.findGrpIdByName("Trufflesnout")!!

            val snap = h.messageSnapshot()
            val cast = h.castSpellByName("Trufflesnout")
            cast.shouldBeTrue()

            // Pass priority to resolve Trufflesnout (stack → battlefield → ETB trigger)
            h.passPriority()

            val msgs = h.messagesSince(snap)
            val ctoReq = msgs.firstOrNull { it.hasCastingTimeOptionsReq() }
            ctoReq.shouldNotBeNull()

            val req = ctoReq.castingTimeOptionsReq
            req.castingTimeOptionReqCount shouldBe 1

            val option = req.getCastingTimeOptionReq(0)
            option.castingTimeOptionType shouldBe CastingTimeOptionType.Modal_a7b4
            option.grpId shouldBe trufflesnoutGrpId
            option.hasModalReq().shouldBeTrue()

            val modalReq = option.modalReq
            modalReq.abilityGrpId shouldBe parentAbilityGrpId
            modalReq.minSel shouldBe 1
            modalReq.maxSel shouldBe 1
            modalReq.modalOptionsCount shouldBe 2
            modalReq.getModalOptions(0).grpId shouldBe counterModeGrpId
            modalReq.getModalOptions(1).grpId shouldBe lifeModeGrpId
        }

        test("modal choice resolves life gain") {
            val h = setupModal()

            val player = h.bridge.getPlayer(SeatId(1))!!
            val startLife = player.life

            h.castSpellByName("Trufflesnout").shouldBeTrue()
            h.passPriority()

            // Choose life gain mode (index 1 → lifeModeGrpId)
            h.respondModalChoice(listOf(lifeModeGrpId))

            // Verify life gain
            val endLife = h.bridge.getPlayer(SeatId(1))!!.life
            (endLife - startLife) shouldBe 4
        }
    })
