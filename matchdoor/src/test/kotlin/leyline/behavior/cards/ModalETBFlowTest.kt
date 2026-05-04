package leyline.behavior.cards

import forge.game.card.CounterEnumType
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.IntegrationTag
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.testkit.MatchFlowHarness
import leyline.testkit.TestCardRegistry
import leyline.testkit.gsm
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Modal ETB flow tests using [MatchFlowHarness] + puzzle files.
 *
 * Trufflesnout (2G, 2/2 Boar) has 2 ETB modal choices:
 *   - Mode 0: Put a +1/+1 counter on Trufflesnout
 *   - Mode 1: You gain 4 life
 *
 * Charming Prince (1W, 1/1 Human Noble) has 3 ETB modal choices:
 *   - Mode 0: Scry 2
 *   - Mode 1: You gain 3 life
 *   - Mode 2: Exile another target creature you own, return at next end step
 *
 * Tests verify that casting these produces a CastingTimeOptionsReq with the
 * correct wire shape — particularly that ETB modals reference the ability
 * instanceId (not the card), as the protocol requires.
 */
class ModalETBFlowTest :
    FunSpec({

        tags(IntegrationTag)

        // Trufflesnout modal ability ids — match `trufflesnout.yaml` fixture.
        val parentAbilityGrpId = 137979
        val counterModeGrpId = 60590
        val lifeModeGrpId = 121504

        // Charming Prince modal ability ids — match `charming_prince.yaml` fixture.
        val princeAbilityGrpId = 136341
        val princeLifeModeGrpId = 26167

        var harness: MatchFlowHarness? = null

        afterEach {
            harness?.shutdown()
            harness = null
        }

        fun setupTrufflesnout(): MatchFlowHarness {
            val h = MatchFlowHarness(validating = false)
            harness = h
            h.connectAndKeepPuzzle("puzzles/modal-etb.pzl")
            return h
        }

        fun setupPrince(): MatchFlowHarness {
            val h = MatchFlowHarness(validating = false)
            harness = h
            h.connectAndKeepPuzzle("puzzles/prince-etb.pzl")
            return h
        }

        test("modal ETB emits CastingTimeOptionsReq") {
            val h = setupTrufflesnout()
            val req = h.castSpellUntilCastingTimeOptionsReq("Trufflesnout")
            req.castingTimeOptionReqCount shouldBe 1

            val option = req.getCastingTimeOptionReq(0)
            option.castingTimeOptionType shouldBe CastingTimeOptionType.Modal_a7b4
            // ETB trigger: grpId is the ability grpId, not the card grpId
            assertSoftly {
                option.grpId shouldBe parentAbilityGrpId
                option.ctoId shouldBe 2
                option.hasModalReq().shouldBeTrue()
            }

            val modalReq = option.modalReq
            assertSoftly {
                modalReq.abilityGrpId shouldBe parentAbilityGrpId
                modalReq.minSel shouldBe 1
                modalReq.maxSel shouldBe 1
                modalReq.modalOptionsCount shouldBe 2
                modalReq.getModalOptions(0).grpId shouldBe counterModeGrpId
                modalReq.getModalOptions(1).grpId shouldBe lifeModeGrpId
            }
        }

        test("modal choice resolves life gain") {
            val h = setupTrufflesnout()

            val player = h.bridge.getPlayer(SeatId(1))!!
            val startLife = player.life

            h.castSpellUntilCastingTimeOptionsReq("Trufflesnout")

            // Choose life gain mode (index 1 → lifeModeGrpId)
            h.respondModalChoice(listOf(lifeModeGrpId))

            // Verify life gain
            val endLife = h.bridge.getPlayer(SeatId(1))!!.life
            (endLife - startLife) shouldBe 4
        }

        test("modal choice resolves +1/+1 counter") {
            val h = setupTrufflesnout()

            h.castSpellUntilCastingTimeOptionsReq("Trufflesnout")

            // Choose counter mode (index 0 → counterModeGrpId)
            h.respondModalChoice(listOf(counterModeGrpId))

            // Find Trufflesnout on battlefield — should have a +1/+1 counter
            val player = h.bridge.getPlayer(SeatId(1))!!
            val trufflesnout =
                player
                    .getZone(forge.game.zone.ZoneType.Battlefield)
                    .cards
                    .firstOrNull { it.name == "Trufflesnout" }
            trufflesnout.shouldNotBeNull()
            trufflesnout.getCounters(CounterEnumType.P1P1) shouldBeGreaterThan 0
        }

        test("Charming Prince ETB modal uses ability instanceId, not card instanceId") {
            val h = setupPrince()

            val req = h.castSpellUntilCastingTimeOptionsReq("Charming Prince")
            req.castingTimeOptionReqCount shouldBe 1

            val option = req.getCastingTimeOptionReq(0)
            option.castingTimeOptionType shouldBe CastingTimeOptionType.Modal_a7b4

            // Protocol: grpId is the ability grpId (136341), not the card grpId
            option.grpId shouldBe princeAbilityGrpId

            // Protocol: affectedId/affectorId reference the ability on the
            // stack, not the card. The ability instanceId is allocated against
            // [FrameIdResolver.triggerStackAbilityForgeId] keyed on the
            // trigger SA id and lives in the stack-ability surrogate range —
            // [FrameIdResolver.isStackAbilityForgeId] guards iids vs source
            // card iids on the same battlefield. Asserting an exact iid here
            // is brittle: the SA id Forge assigns depends on internal
            // allocation order, and the SA isn't on the stack at CTO emission
            // time (still in the cost-prep phase).
            val princeCard =
                h.bridge
                    .getPlayer(SeatId(1))!!
                    .getZone(forge.game.zone.ZoneType.Battlefield)
                    .cards
                    .first { it.name == "Charming Prince" }
            val princeCardIid =
                h.bridge.getOrAllocInstanceId(ForgeCardId(princeCard.id)).value

            assertSoftly {
                option.affectedId shouldNotBe princeCardIid
                option.affectorId shouldNotBe princeCardIid
                option.affectedId shouldBe option.affectorId
                // Protocol: ctoId=2 for both spell-time and ETB modals
                option.ctoId shouldBe 2
                // Protocol: playerIdToPrompt is set
                option.playerIdToPrompt shouldBe 1
            }

            // Modal options should be correct. Charming Prince has 3 ETB modes;
            // the third mode (Exile another creature you own) is legality-filtered
            // when no second creature is on the battlefield (puzzle setup), so it
            // surfaces in `excludedOptions[]` rather than `modalOptions[]`. The
            // total mode count is still 3.
            val modalReq = option.modalReq
            assertSoftly {
                modalReq.abilityGrpId shouldBe princeAbilityGrpId
                modalReq.minSel shouldBe 1
                modalReq.maxSel shouldBeGreaterThan 0
                (modalReq.modalOptionsCount + modalReq.excludedOptionsCount) shouldBe 3
            }
        }

        test("ETB modal GSM has ability on stack and pendingMessageCount") {
            val h = setupTrufflesnout()

            val snapshot = h.messageSnapshot()
            h.castSpellUntilCastingTimeOptionsReq("Trufflesnout")
            val msgs = h.messagesSince(snapshot)

            // Find the GSM that accompanies the CTO
            val ctoIdx = msgs.indexOfFirst { it.type == GREMessageType.CastingTimeOptionsReq_695e }
            ctoIdx shouldBeGreaterThan 0 // GSM must come before CTO

            val gsm = msgs[ctoIdx - 1].gameStateMessage
            gsm.shouldNotBeNull()

            // Must have pendingMessageCount=1 (signals CTO follows)
            gsm.pendingMessageCount shouldBe 1

            // Must have the ability on the stack
            val stackZone = gsm.zonesList.find { it.type == ZoneType.Stack }
            stackZone.shouldNotBeNull()
            stackZone.objectInstanceIdsList.shouldNotBeEmpty()

            // Must have a GameObjectType_Ability in the game objects
            val abilityObj = gsm.gameObjectsList.find { it.type == GameObjectType.Ability }
            abilityObj.shouldNotBeNull()
            abilityObj.zoneId shouldBe stackZone.zoneId

            // The CTO affectedId must match the ability instanceId
            val cto = msgs[ctoIdx].castingTimeOptionsReq
            val affectedId = cto.getCastingTimeOptionReq(0).affectedId
            abilityObj.instanceId shouldBe affectedId
        }

        test("ETB ability object has correct parentId and objectSourceGrpId") {
            val h = setupTrufflesnout()
            val trufflesnoutGrpId = TestCardRegistry.repo.findGrpIdByName("Trufflesnout")!!

            val snapshot = h.messageSnapshot()
            h.castSpellUntilCastingTimeOptionsReq("Trufflesnout")
            val msgs = h.messagesSince(snapshot)

            val ctoIdx = msgs.indexOfFirst { it.type == GREMessageType.CastingTimeOptionsReq_695e }
            val gsm = msgs[ctoIdx - 1].gameStateMessage
            val abilityObj = gsm.gameObjectsList.first { it.type == GameObjectType.Ability }

            // parentId = source card instanceId on the battlefield
            val trufflesnoutCard =
                h.bridge
                    .getPlayer(SeatId(1))!!
                    .getZone(forge.game.zone.ZoneType.Battlefield)
                    .cards
                    .first { it.name == "Trufflesnout" }
            val cardInstanceId = h.bridge.getOrAllocInstanceId(ForgeCardId(trufflesnoutCard.id)).value

            abilityObj.parentId shouldBe cardInstanceId
            // objectSourceGrpId = card grpId (not ability grpId)
            abilityObj.objectSourceGrpId shouldBe trufflesnoutGrpId
            // grpId = ability grpId
            abilityObj.grpId shouldBe parentAbilityGrpId
        }

        // DISABLED — the original `stackEmpty || abilityDeleted` predicate
        // against `gsms.last()` was passing only because the trailing empty
        // echo GSM has no zonesList (stackZone == null short-circuits the
        // OR). Investigation during leyline-sxpo's D fold-in showed the
        // engine never actually emits an explicit stack-zone cleanup diff
        // after a modal resolution: the running-state accumulator still
        // lists the ability iid in `zones[ZoneIds.STACK].objectInstanceIds`
        // post-respond. So tightening to "any GSM with explicit Stack
        // cleanup" or "accumulator stack empty" both fail on the actual
        // wire output, not on a test bug. Re-enable once leyline-l1tc
        // ships the missing cleanup signal.
        xtest("synthesized ability cleaned up after modal resolves (leyline-l1tc)") {
            val h = setupTrufflesnout()

            h.castSpellUntilCastingTimeOptionsReq("Trufflesnout")

            val snapshot = h.messageSnapshot()
            h.respondModalChoice(listOf(lifeModeGrpId))
            val msgs = h.messagesSince(snapshot)

            val gsms = msgs.filter { it.hasGameStateMessage() }.map { it.gameStateMessage }
            gsms.shouldNotBeEmpty()

            // Target shape once l1tc lands: any GSM in the post-respond
            // batch should carry either an explicit Stack zone diff
            // (post-resolution contents) or the ability iid in
            // diffDeletedInstanceIdsList. Empty echoes don't count.
            val cleaned =
                gsms.any { gs ->
                    val stackZone = gs.zonesList.find { it.type == ZoneType.Stack }
                    val stackExplicitlyEmpty = stackZone != null && stackZone.objectInstanceIdsList.isEmpty()
                    val abilityDeleted = gs.diffDeletedInstanceIdsList.isNotEmpty()
                    stackExplicitlyEmpty || abilityDeleted
                }
            cleaned shouldBe true
        }

        test("Charming Prince gain 3 life mode resolves") {
            val h = setupPrince()

            val player = h.bridge.getPlayer(SeatId(1))!!
            val startLife = player.life

            h.castSpellUntilCastingTimeOptionsReq("Charming Prince")
            h.respondModalChoice(listOf(princeLifeModeGrpId))

            val endLife = h.bridge.getPlayer(SeatId(1))!!.life
            (endLife - startLife) shouldBe 3
        }
    })
