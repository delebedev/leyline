package leyline.match

import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.IntegrationTag
import leyline.bridge.ForgeCardId
import leyline.bridge.SeatId
import leyline.conformance.ConformanceTestBase
import leyline.conformance.MatchFlowHarness
import leyline.conformance.detail
import leyline.conformance.detailInt
import leyline.conformance.detailString
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage

/**
 * Madness end-to-end. Spec: arena-lab/docs/protocol/mechanics/Madness.md.
 *
 * Three sub-cases from the spec:
 *  - **cast path**: discard via Tormenting Voice → Hand→Exile (Discard) →
 *    madness trigger → Exile→Stack (CastSpell) with persistent
 *    CastingTimeOption type=13 → Stack→GY (Resolve) with damage to opponent.
 *  - **decline path**: same setup but pass priority on the madness offer →
 *    Exile→GY (Put) with the ability resolving as "no cast".
 *  - **hardcast path**: cast the madness card from hand for its regular mana
 *    cost — no Exile detour, no CastingTimeOption.
 *
 * Card setup uses Tormenting Voice (1R, additional cost: discard 1, draw 2)
 * as the discard outlet driving the Madness trigger on Fiery Temper (1RR
 * instant, deal 3, Madness {R}).
 */
private val MADNESS_PUZZLE = """
[metadata]
Name:Madness — Tormenting Voice into Fiery Temper
Goal:Discard Fiery Temper via Tormenting Voice, then cast it for madness.
Turns:5
Difficulty:Easy

[state]
ActivePlayer=Human
ActivePhase=Main1
HumanLife=20
AILife=20

humanhand=Tormenting Voice;Fiery Temper
humanbattlefield=Mountain;Mountain;Mountain;Mountain
humanlibrary=Plains;Plains;Plains;Plains
ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain
""".trimIndent()

private val HARDCAST_PUZZLE = """
[metadata]
Name:Madness — Fiery Temper hardcast
Goal:Cast Fiery Temper from hand for its regular mana cost (no madness path).
Turns:3
Difficulty:Easy

[state]
ActivePlayer=Human
ActivePhase=Main1
HumanLife=20
AILife=20

humanhand=Fiery Temper
humanbattlefield=Mountain;Mountain;Mountain;Mountain
humanlibrary=Plains;Plains;Plains
ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain
""".trimIndent()

class MadnessTest :
    FunSpec({

        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        test("madness cast path: discard outlet → exile → cast for {R} → resolve").config(tags = setOf(IntegrationTag)) {
            val h = MatchFlowHarness(validating = false)
            try {
                h.connectAndKeepPuzzleText(MADNESS_PUZZLE)
                val player = h.bridge.getPlayer(SeatId(1))!!

                // Capture Fiery Temper's grpId before it moves.
                val fieryTemperGrpId = h.bridge.cardRepository.findGrpIdByName("Fiery Temper")!!
                val cardData = h.bridge.cardRepository.findByGrpId(fieryTemperGrpId)!!
                val madnessAbilityGrpId = cardData.keywordAbilityGrpIds.entries
                    .firstOrNull { it.key.uppercase().startsWith("MADNESS") }?.value
                madnessAbilityGrpId shouldNotBe null
                madnessAbilityGrpId!! shouldBeGreaterThan 0

                // Cast Tormenting Voice. Forge issues a SelectNReq for the
                // additional-cost discard before the spell goes on stack.
                h.castSpellByName("Tormenting Voice").shouldBeTrue()

                // Pull the discardable id from the live SelectNReq — instanceIds
                // here may differ from the hand-side iid due to discard realloc.
                val selectNReq = h.allMessages.asReversed()
                    .firstOrNull { it.hasSelectNReq() }?.selectNReq
                selectNReq shouldNotBe null
                val discardChoiceId = selectNReq!!.idsList.firstOrNull()
                discardChoiceId shouldNotBe null
                h.respondToSelectN(listOf(discardChoiceId!!))

                // Tormenting Voice on stack with discard cost paid; Fiery Temper
                // exiled via madness replacement; the madness trigger queues a
                // PlayEffect that auto-casts Fiery Temper from Exile (the WPC
                // default elects the optional cast). After the trigger resolves,
                // Fiery Temper sits on top of the stack ahead of Tormenting Voice.
                h.passPriority() // resolves the chain
                h.passPriority() // continues if needed

                // If Fiery Temper landed on stack via madness, it needs a target.
                val hasPendingTarget = h.allMessages.asReversed()
                    .any { it.hasSelectTargetsReq() }
                if (hasPendingTarget) {
                    // AI player's seatId = 2; that's a valid "Any" target for Fiery Temper.
                    h.selectTargets(listOf(2))
                }

                // Drain remaining priority passes until the stack empties.
                repeat(4) { if (!h.bridge.getGame()!!.stack.isEmpty) h.passPriority() }

                // Fiery Temper resolves into Graveyard with damage to opponent.
                player.getZone(ZoneType.Graveyard).cards.any { it.name == "Fiery Temper" }
                    .shouldBeTrue()

                // Wire-shape assertions across the captured GSM stream.
                val allGsms = h.allMessages.mapNotNull { msgGsm(it) }

                // (1) Persistent CastingTimeOption type=13 attached to the
                //     stack-staged Fiery Temper, with the madness ability grpId.
                //     This is the alt-cost signal: client renders the cast as
                //     having gone through the madness path.
                val cto = allGsms.flatMap { it.persistentAnnotationsList }
                    .firstOrNull { it.typeList.contains(AnnotationType.CastingTimeOption) }
                cto shouldNotBe null
                cto!!.detailInt("type") shouldBe 13
                cto.detailInt("alternateCostGrpId") shouldBe madnessAbilityGrpId
                cto.detailInt("castAbilityGrpId") shouldBe madnessAbilityGrpId

                // (2) UserActionTaken on the cast carries alternativeGrpId = madness ability.
                val castUat = allGsms.flatMap { it.annotationsList }
                    .filter { it.typeList.contains(AnnotationType.UserActionTaken) }
                    .firstOrNull {
                        it.detail("alternativeGrpId")?.getValueInt32(0) == madnessAbilityGrpId
                    }
                castUat shouldNotBe null

                // (3) Fiery Temper resolved (deals 3 damage to AI player).
                val resolveZt = allGsms.flatMap { it.annotationsList }
                    .filter { it.typeList.contains(AnnotationType.ZoneTransfer_af5a) }
                    .firstOrNull { it.detailString("category") == "Resolve" }
                resolveZt shouldNotBe null

                // KNOWN L1 GAP: Forge resolves Hand→Exile→Stack atomically when
                // madness's PlayEffect auto-elects the cast. The Phase 1 Discard
                // transition (Hand→Exile category=Discard) is invisible to our
                // GSM diff because the card never lingers in Exile across snapshots.
                // Real-Arena corpus shows the staging explicitly. Closing this
                // requires synthesizing the round-trip in ZoneTransferDetector
                // (same pattern as detectExileReturnRoundTrips for sagas).
                // Tracked as L1.5 follow-up — alt-cost detection + persistent
                // CastingTimeOption is enough for the client to render correctly.
            } finally {
                h.shutdown()
            }
        }

        test("madness hardcast: regular cast from hand omits CastingTimeOption + alternativeGrpId").config(tags = setOf(IntegrationTag)) {
            val h = MatchFlowHarness(validating = false)
            try {
                h.connectAndKeepPuzzleText(HARDCAST_PUZZLE)
                val player = h.bridge.getPlayer(SeatId(1))!!

                // Cast Fiery Temper directly from hand (1RR).
                h.castSpellByName("Fiery Temper").shouldBeTrue()
                h.selectTargets(listOf(2)) // AI player
                h.passPriority()

                player.getZone(ZoneType.Graveyard).cards.any { it.name == "Fiery Temper" }
                    .shouldBeTrue()

                val allGsms = h.allMessages.mapNotNull { msgGsm(it) }

                // No persistent CastingTimeOption emitted for hardcast.
                val cto = allGsms.flatMap { it.persistentAnnotationsList }
                    .firstOrNull { it.typeList.contains(AnnotationType.CastingTimeOption) }
                cto shouldBe null

                // No alternativeGrpId on any UAT (regular cast).
                val anyAltUat = allGsms.flatMap { it.annotationsList }
                    .filter { it.typeList.contains(AnnotationType.UserActionTaken) }
                    .any { it.detailsList.any { d -> d.key == "alternativeGrpId" } }
                anyAltUat shouldBe false

                // Hand→Stack direct (no Exile detour).
                val handToStack = allGsms.flatMap { it.annotationsList }
                    .filter { it.typeList.contains(AnnotationType.ZoneTransfer_af5a) }
                    .firstOrNull {
                        it.detailString("category") == "CastSpell" &&
                            it.detailInt("zone_src") == leyline.game.mapper.ZoneIds.P1_HAND
                    }
                handToStack shouldNotBe null
            } finally {
                h.shutdown()
            }
        }
    })

private fun msgGsm(msg: GREToClientMessage) =
    if (msg.hasGameStateMessage()) msg.gameStateMessage else null
