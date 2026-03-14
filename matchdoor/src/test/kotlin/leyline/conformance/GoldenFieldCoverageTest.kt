package leyline.conformance

import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import leyline.ConformanceTag
import leyline.bridge.InteractivePromptBridge.PendingPrompt
import leyline.bridge.PromptCandidateRefDto
import leyline.bridge.PromptRequest
import leyline.bridge.SeatId
import leyline.game.BundleBuilder
import leyline.game.GsmBuilder
import leyline.game.RequestBuilder
import leyline.protocol.HandshakeMessages
import wotc.mtgo.gre.external.messaging.Messages.*
import java.util.concurrent.CompletableFuture

/**
 * Golden field coverage: compares field presence in our builder output
 * against real Arena server recordings.
 *
 * Each test:
 * 1. Parses a golden `.bin` (proxy capture) → extracts field paths
 * 2. Builds our proto via real builders + minimal board → extracts field paths
 * 3. Diffs: `expectedMissing` documents known gaps, test fails on NEW gaps or FIXED gaps
 *
 * `expectedMissing` IS the living documentation of what we don't produce yet.
 * When a gap is fixed: remove from `expectedMissing` → test validates the fix.
 * When a new golden has unknown fields: test fails → forced triage.
 */
class GoldenFieldCoverageTest :
    FunSpec({

        tags(ConformanceTag)

        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        // --- Helpers ---

        fun loadGoldenGRE(resource: String, type: GREMessageType): GREToClientMessage {
            val bytes = javaClass.classLoader.getResourceAsStream("golden/$resource")?.readBytes()
                ?: error("Golden not found: golden/$resource")
            val payload = MatchServiceToClientMessage.parseFrom(bytes)
            return payload.greToClientEvent.greToClientMessagesList
                .firstOrNull { it.type == type }
                ?: error("No $type in golden/$resource")
        }

        fun loadGoldenGSM(resource: String, gsId: Int): GameStateMessage {
            val bytes = javaClass.classLoader.getResourceAsStream("golden/$resource")?.readBytes()
                ?: error("Golden not found: golden/$resource")
            val payload = MatchServiceToClientMessage.parseFrom(bytes)
            return payload.greToClientEvent.greToClientMessagesList
                .filter { it.type == GREMessageType.GameStateMessage_695e }
                .map { it.gameStateMessage }
                .firstOrNull { it.gameStateId == gsId }
                ?: error("No GSM with gsId=$gsId in golden/$resource")
        }

        fun annotationDetailKeys(gsm: GameStateMessage): Map<String, Set<String>> {
            val result = mutableMapOf<String, MutableSet<String>>()
            for (ann in gsm.annotationsList) {
                for (type in ann.typeList) {
                    val shortName = type.name.replace(Regex("_[a-f0-9]{4}$"), "")
                    val keys = result.getOrPut(shortName) { mutableSetOf() }
                    for (detail in ann.detailsList) {
                        keys.add(detail.key)
                    }
                }
            }
            return result
        }

        fun assertFieldCoverage(
            label: String,
            golden: Set<String>,
            ours: Set<String>,
            expectedMissing: Set<String>,
            expectedExtra: Set<String> = emptySet(),
        ) {
            val missing = golden - ours
            val extra = ours - golden
            val newGaps = missing - expectedMissing
            val fixedGaps = expectedMissing - missing
            val newExtras = extra - expectedExtra
            val removedExtras = expectedExtra - extra

            val diff = FieldPathExtractor.formatDiff(golden, ours, expectedMissing)

            newGaps.shouldBeEmpty()
            fixedGaps.shouldBeEmpty()
            newExtras.shouldBeEmpty()
            removedExtras.shouldBeEmpty()
        }

        // --- SelectTargetsReq ---

        test("SelectTargetsReq field coverage vs real server") {
            val goldenGRE = loadGoldenGRE("select-targets-req.bin", GREMessageType.SelectTargetsReq_695e)
            val goldenFields = FieldPathExtractor.extract(goldenGRE.selectTargetsReq)

            val (b, _, _) = base.startWithBoard { game, human, ai ->
                base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
                base.addCard("Llanowar Elves", ai, ZoneType.Battlefield)
            }

            val humanCreature = b.getPlayer(SeatId(1))!!.getZone(ZoneType.Battlefield).cards.first()
            val aiCreature = b.getPlayer(SeatId(2))!!.getZone(ZoneType.Battlefield).cards.first()
            val sourceCard = humanCreature

            val prompt = PendingPrompt(
                promptId = "test",
                request = PromptRequest(
                    promptType = "choose_cards",
                    message = "Choose target creature",
                    options = listOf("Target"),
                    min = 1,
                    max = 1,
                    candidateRefs = listOf(
                        PromptCandidateRefDto(0, "card", aiCreature.id, "Battlefield"),
                        PromptCandidateRefDto(1, "card", humanCreature.id, "Battlefield"),
                    ),
                    sourceEntityId = sourceCard.id,
                ),
                future = CompletableFuture(),
            )

            val ours = RequestBuilder.buildSelectTargetsReq(prompt, b)
            val ourFields = FieldPathExtractor.extract(ours)

            val expectedMissing = setOf(
                "targets[].prompt.promptId",
                "targets[].prompt.parameters[].parameterName",
                "targets[].prompt.parameters[].type",
                "targets[].prompt.parameters[].numberValue",
                "targets[].targetingAbilityGrpId",
                "abilityGrpId",
            )

            assertFieldCoverage("SelectTargetsReq", goldenFields, ourFields, expectedMissing, emptySet())
        }

        // --- DeclareAttackersReq ---

        test("DeclareAttackersReq field coverage vs real server") {
            val goldenGRE = loadGoldenGRE("declare-attackers-req.bin", GREMessageType.DeclareAttackersReq_695e)
            val goldenFields = FieldPathExtractor.extract(goldenGRE.declareAttackersReq)

            val (b, game, _) = base.startWithBoard { g, human, _ ->
                base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
            }
            val ours = RequestBuilder.buildDeclareAttackersReq(game, 1, b)
            val ourFields = FieldPathExtractor.extract(ours)

            val expectedMissing = emptySet<String>()
            val expectedExtra = emptySet<String>()

            assertFieldCoverage("DeclareAttackersReq", goldenFields, ourFields, expectedMissing, expectedExtra)
        }

        // --- DeclareBlockersReq ---

        // TODO: combat setup incomplete — devModeSet to COMBAT_DECLARE_BLOCKERS
        //  doesn't initialize Forge's Combat object, so addAttacker returns null
        //  and buildDeclareBlockersReq produces empty output. Needs proper combat
        //  initialization or a MatchFlowHarness-based test with real combat.
        xtest("DeclareBlockersReq field coverage vs real server") {
            val goldenGRE = loadGoldenGRE("declare-blockers-req.bin", GREMessageType.DeclareBlockersReq_695e)
            val goldenFields = FieldPathExtractor.extract(goldenGRE.declareBlockersReq)

            val (b, game, _) = base.startWithBoard { g, human, ai ->
                base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
                val attacker = base.addCard("Llanowar Elves", ai, ZoneType.Battlefield)
                g.phaseHandler.devModeSet(forge.game.phase.PhaseType.COMBAT_DECLARE_BLOCKERS, ai)
                g.combat?.addAttacker(attacker, human)
            }
            val ours = RequestBuilder.buildDeclareBlockersReq(game, 1, b)
            val ourFields = FieldPathExtractor.extract(ours)

            val expectedMissing = setOf("manaCost")

            ourFields.shouldNotBeEmpty()
            assertFieldCoverage("DeclareBlockersReq", goldenFields, ourFields, expectedMissing)
        }

        // --- ActionsAvailableReq ---

        test("ActionsAvailableReq field coverage vs real server") {
            val goldenGRE = loadGoldenGRE("actions-available-req.bin", GREMessageType.ActionsAvailableReq_695e)
            val goldenFields = FieldPathExtractor.extract(goldenGRE.actionsAvailableReq)

            val (b, game, _) = base.startWithBoard { _, human, _ ->
                base.addCard("Plains", human, ZoneType.Hand)
                base.addCard("Grizzly Bears", human, ZoneType.Hand)
                base.addCard("Forest", human, ZoneType.Battlefield)
            }
            val ours = BundleBuilder.buildActions(game, 1, b)
            val ourFields = FieldPathExtractor.extract(ours)

            val expectedMissing = setOf(
                "actions[].manaCost[].color[]",
                "actions[].manaCost[].count",
                "inactiveActions[].actionType",
                "inactiveActions[].grpId",
                "inactiveActions[].instanceId",
                "inactiveActions[].facetId",
                "inactiveActions[].manaCost[].color[]",
                "inactiveActions[].manaCost[].count",
            )

            val expectedExtra = setOf(
                "actions[].abilityGrpId",
                "actions[].isBatchable",
                "actions[].manaPaymentOptions[].mana[].abilityGrpId",
                "actions[].manaPaymentOptions[].mana[].color",
                "actions[].manaPaymentOptions[].mana[].count",
                "actions[].manaPaymentOptions[].mana[].manaId",
                "actions[].manaPaymentOptions[].mana[].specs[].type",
                "actions[].manaPaymentOptions[].mana[].srcInstanceId",
                "actions[].manaSelections[].abilityGrpId",
                "actions[].manaSelections[].instanceId",
                "actions[].manaSelections[].options[].mana[].color",
                "actions[].manaSelections[].options[].mana[].count",
            )

            assertFieldCoverage("ActionsAvailableReq", goldenFields, ourFields, expectedMissing, expectedExtra)
        }

        // --- GRE wrapper fields ---

        test("SelectTargetsReq GRE wrapper field coverage") {
            val goldenGRE = loadGoldenGRE("select-targets-req.bin", GREMessageType.SelectTargetsReq_695e)
            val goldenFields = FieldPathExtractor.extract(goldenGRE)

            val (b, game, counter) = base.startWithBoard { g, human, ai ->
                base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
                base.addCard("Llanowar Elves", ai, ZoneType.Battlefield)
            }
            val aiCreature = b.getPlayer(SeatId(2))!!.getZone(ZoneType.Battlefield).cards.first()

            val prompt = PendingPrompt(
                promptId = "test",
                request = PromptRequest(
                    promptType = "choose_cards",
                    message = "Choose target",
                    options = listOf("Target"),
                    min = 1,
                    max = 1,
                    candidateRefs = listOf(
                        PromptCandidateRefDto(0, "card", aiCreature.id, "Battlefield"),
                    ),
                    sourceEntityId = aiCreature.id,
                ),
                future = CompletableFuture(),
            )
            val result = BundleBuilder.selectTargetsBundle(game, b, ConformanceTestBase.TEST_MATCH_ID, 1, counter, prompt)
            val oursGRE = result.messages.first { it.type == GREMessageType.SelectTargetsReq_695e }
            val ourFields = FieldPathExtractor.extract(oursGRE)

            val expectedMissing = setOf(
                "selectTargetsReq.targets[].prompt.promptId",
                "selectTargetsReq.targets[].prompt.parameters[].parameterName",
                "selectTargetsReq.targets[].prompt.parameters[].type",
                "selectTargetsReq.targets[].prompt.parameters[].numberValue",
                "selectTargetsReq.targets[].targetingAbilityGrpId",
                "selectTargetsReq.abilityGrpId",
            )

            assertFieldCoverage("SelectTargetsReq (wrapper)", goldenFields, ourFields, expectedMissing, emptySet())
        }

        // --- ConnectResp ---

        test("ConnectResp field coverage vs real server") {
            val goldenGRE = loadGoldenGRE("initial-bundle.bin", GREMessageType.ConnectResp_695e)
            val goldenFields = FieldPathExtractor.extract(goldenGRE.connectResp)

            val (b, _, _) = base.startWithBoard { _, _, _ -> }
            val deck = GsmBuilder.buildDeckMessage(b.getDeckGrpIds(1))
            val (msg, _) = HandshakeMessages.initialBundle(1, ConformanceTestBase.TEST_MATCH_ID, 2, 1, deck, b)
            val oursGRE = msg.greToClientEvent.greToClientMessagesList
                .first { it.type == GREMessageType.ConnectResp_695e }
            val ourFields = FieldPathExtractor.extract(oursGRE.connectResp)

            val expectedMissing = setOf(
                "deckMessage.deckCards[]",
                "greChangelist",
                "grpChangelist",
                "skins[].catalogId",
                "skins[].skinCode",
            )

            assertFieldCoverage("ConnectResp", goldenFields, ourFields, expectedMissing, emptySet())
        }

        // --- DieRollResultsResp ---

        test("DieRollResultsResp field coverage vs real server") {
            val goldenGRE = loadGoldenGRE("initial-bundle.bin", GREMessageType.DieRollResultsResp_695e)
            val goldenFields = FieldPathExtractor.extract(goldenGRE.dieRollResultsResp)

            val (b, _, _) = base.startWithBoard { _, _, _ -> }
            val deck = GsmBuilder.buildDeckMessage(b.getDeckGrpIds(1))
            val (msg, _) = HandshakeMessages.initialBundle(1, ConformanceTestBase.TEST_MATCH_ID, 2, 1, deck, b)
            val oursGRE = msg.greToClientEvent.greToClientMessagesList
                .first { it.type == GREMessageType.DieRollResultsResp_695e }
            val ourFields = FieldPathExtractor.extract(oursGRE.dieRollResultsResp)

            assertFieldCoverage("DieRollResultsResp", goldenFields, ourFields, emptySet(), emptySet())
        }

        // --- Initial Full GameStateMessage ---

        test("Initial Full GSM field coverage vs real server") {
            val goldenGRE = loadGoldenGRE("initial-bundle.bin", GREMessageType.GameStateMessage_695e)
            val goldenFields = FieldPathExtractor.extract(goldenGRE.gameStateMessage)

            val (b, _, _) = base.startWithBoard { _, _, _ -> }
            val deck = GsmBuilder.buildDeckMessage(b.getDeckGrpIds(1))
            val (msg, _) = HandshakeMessages.initialBundle(1, ConformanceTestBase.TEST_MATCH_ID, 2, 1, deck, b)
            val oursGRE = msg.greToClientEvent.greToClientMessagesList
                .first { it.type == GREMessageType.GameStateMessage_695e }
            val ourFields = FieldPathExtractor.extract(oursGRE.gameStateMessage)

            val expectedMissing = setOf(
                "diffDeletedInstanceIds[]",
                "gameInfo.maxPipCount",
                "gameInfo.maxTimeoutCount",
                "gameInfo.timeoutDurationSec",
                "pendingMessageCount",
                "timers[].elapsedMs",
                "zones[].objectInstanceIds[]",
            )

            assertFieldCoverage("Initial Full GSM", goldenFields, ourFields, expectedMissing, emptySet())
        }

        // --- MulliganReq ---

        test("MulliganReq field coverage vs real server") {
            val goldenGRE = loadGoldenGRE("mulligan-req.bin", GREMessageType.MulliganReq_aa0d)
            val goldenFields = FieldPathExtractor.extract(goldenGRE)

            val ours = GsmBuilder.buildMulliganReq(msgId = 9, gameStateId = 2, seatId = 1)
            val ourFields = FieldPathExtractor.extract(ours)

            assertFieldCoverage("MulliganReq", goldenFields, ourFields, emptySet(), emptySet())
        }

        // --- GroupReq ---

        test("GroupReq field coverage vs real server (London mulligan tuck)") {
            val goldenGRE = loadGoldenGRE("group-req-london-mulligan.bin", GREMessageType.GroupReq_695e)
            val goldenFields = FieldPathExtractor.extract(goldenGRE)

            val handInstanceIds = listOf(407, 408, 409, 410, 411, 412, 413)
            val ours = GsmBuilder.buildGroupReq(
                msgId = 24,
                gameStateId = 7,
                seatId = 1,
                handInstanceIds = handInstanceIds,
                cardsToTuck = 3,
            )
            val ourFields = FieldPathExtractor.extract(ours)

            assertFieldCoverage("GroupReq", goldenFields, ourFields, emptySet(), emptySet())
        }

        // --- SetSettingsResp ---

        test("SetSettingsResp field coverage vs real server") {
            val goldenGRE = loadGoldenGRE("set-settings-resp.bin", GREMessageType.SetSettingsResp_695e)
            val goldenFields = FieldPathExtractor.extract(goldenGRE.setSettingsResp)

            val clientSettings = goldenGRE.setSettingsResp.settings
            val (msg, _) = HandshakeMessages.settingsResp(1, 6, 1, clientSettings)
            val oursGRE = msg.greToClientEvent.greToClientMessagesList
                .first { it.type == GREMessageType.SetSettingsResp_695e }
            val ourFields = FieldPathExtractor.extract(oursGRE.setSettingsResp)

            assertFieldCoverage("SetSettingsResp", goldenFields, ourFields, emptySet(), emptySet())
        }

        // --- IntermissionReq ---

        test("IntermissionReq field coverage vs real server") {
            val goldenGRE = loadGoldenGRE("intermission-req.bin", GREMessageType.IntermissionReq_695e)
            val goldenFields = FieldPathExtractor.extract(goldenGRE.intermissionReq)

            val (b, _, counter) = base.startWithBoard { _, _, _ -> }
            val result = BundleBuilder.gameOverBundle(
                winningTeam = 1,
                matchId = ConformanceTestBase.TEST_MATCH_ID,
                seatId = 1,
                counter = counter,
                losingPlayerSeatId = 2,
                bridge = b,
            )
            val oursGRE = result.messages.first { it.type == GREMessageType.IntermissionReq_695e }
            val ourFields = FieldPathExtractor.extract(oursGRE.intermissionReq)

            assertFieldCoverage("IntermissionReq", goldenFields, ourFields, emptySet(), emptySet())
        }

        // =======================================================================
        // Annotation shape reference tests
        // =======================================================================

        test("stack resolution annotation shape") {
            val gsm = loadGoldenGSM("stack-resolve.bin", 68)
            val keys = annotationDetailKeys(gsm)

            keys["ResolutionStart"] shouldBe setOf("grpid")
            keys["ResolutionComplete"] shouldBe setOf("grpid")
            keys["ZoneTransfer"] shouldBe setOf("zone_src", "zone_dest", "category")
        }

        test("combat damage annotation shape") {
            val gsm = loadGoldenGSM("combat-damage.bin", 126)
            val keys = annotationDetailKeys(gsm)

            keys["DamageDealt"] shouldBe setOf("damage", "type", "markDamage")

            val sbaTransfer = gsm.annotationsList
                .filter { AnnotationType.ZoneTransfer_af5a in it.typeList }
                .flatMap { ann -> ann.detailsList.filter { it.key == "category" }.map { it.getValueString(0) } }
            ("SBA_Damage" in sbaTransfer).shouldBeTrue()

            keys["ObjectIdChanged"] shouldBe setOf("orig_id", "new_id")
            keys["PhaseOrStepModified"] shouldBe setOf("phase", "step")
        }

        test("cast spell annotation shape") {
            val gsm = loadGoldenGSM("stack-resolve.bin", 66)
            val keys = annotationDetailKeys(gsm)

            val castTransfer = gsm.annotationsList
                .filter { AnnotationType.ZoneTransfer_af5a in it.typeList }
                .flatMap { ann -> ann.detailsList.filter { it.key == "category" }.map { it.getValueString(0) } }
            ("CastSpell" in castTransfer).shouldBeTrue()

            keys["ManaPaid"] shouldBe setOf("id", "color")
            keys["TappedUntappedPermanent"] shouldBe setOf("tapped")
            keys["UserActionTaken"] shouldBe setOf("actionType", "abilityGrpId")
            keys["AbilityInstanceCreated"] shouldBe setOf("source_zone")
        }
    })
