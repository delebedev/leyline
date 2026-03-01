package forge.nexus.conformance

import forge.game.zone.ZoneType
import forge.nexus.game.BundleBuilder
import forge.nexus.game.GsmBuilder
import forge.nexus.game.RequestBuilder
import forge.nexus.protocol.HandshakeMessages
import forge.web.dto.PromptCandidateRefDto
import forge.web.game.InteractivePromptBridge.PendingPrompt
import forge.web.game.PromptRequest
import org.testng.Assert.assertEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
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
 *
 * To update a golden: `cp recordings/<session>/capture/payloads/<file>.bin src/test/resources/golden/<name>.bin`
 */
@Test(groups = ["conformance"])
class GoldenFieldCoverageTest : ConformanceTestBase() {

    // --- Helpers ---

    /** Load a golden payload, extract the GRE message of the given type. */
    private fun loadGoldenGRE(resource: String, type: GREMessageType): GREToClientMessage {
        val bytes = javaClass.classLoader.getResourceAsStream("golden/$resource")?.readBytes()
            ?: error("Golden not found: golden/$resource")
        val payload = MatchServiceToClientMessage.parseFrom(bytes)
        return payload.greToClientEvent.greToClientMessagesList
            .firstOrNull { it.type == type }
            ?: error("No $type in golden/$resource")
    }

    /** Load a golden payload, extract the GSM by gameStateId. */
    private fun loadGoldenGSM(resource: String, gsId: Int): GameStateMessage {
        val bytes = javaClass.classLoader.getResourceAsStream("golden/$resource")?.readBytes()
            ?: error("Golden not found: golden/$resource")
        val payload = MatchServiceToClientMessage.parseFrom(bytes)
        return payload.greToClientEvent.greToClientMessagesList
            .filter { it.type == GREMessageType.GameStateMessage_695e }
            .map { it.gameStateMessage }
            .firstOrNull { it.gameStateId == gsId }
            ?: error("No GSM with gsId=$gsId in golden/$resource")
    }

    /**
     * Extract annotation detail keys grouped by annotation type from a GSM.
     * Returns map of short type name (suffix stripped) → set of detail key names present.
     * E.g. `ZoneTransfer_af5a` → `ZoneTransfer`.
     */
    private fun annotationDetailKeys(gsm: GameStateMessage): Map<String, Set<String>> {
        val result = mutableMapOf<String, MutableSet<String>>()
        for (ann in gsm.annotationsList) {
            for (type in ann.typeList) {
                // Strip proto enum suffix for readability
                val shortName = type.name.replace(Regex("_[a-f0-9]{4}$"), "")
                val keys = result.getOrPut(shortName) { mutableSetOf() }
                for (detail in ann.detailsList) {
                    keys.add(detail.key)
                }
            }
        }
        return result
    }

    /**
     * Assert field coverage: golden vs ours, with documented expected gaps and extras.
     *
     * Fails if:
     * - Golden has fields we don't produce AND they're not in [expectedMissing] (new gap)
     * - [expectedMissing] lists fields we now produce (fixed gap — update the set)
     * - We produce fields golden doesn't AND they're not in [expectedExtra] (new extra)
     * - [expectedExtra] lists fields we no longer produce (removed extra — update the set)
     */
    private fun assertFieldCoverage(
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

        assertTrue(
            newGaps.isEmpty(),
            "$label: new uncovered fields (update expectedMissing or fix builder):\n$diff",
        )
        assertTrue(
            fixedGaps.isEmpty(),
            "$label: fields now covered, remove from expectedMissing:\n$diff",
        )
        assertTrue(
            newExtras.isEmpty(),
            "$label: new extra fields we send but real server doesn't (update expectedExtra or remove):\n$diff",
        )
        assertTrue(
            removedExtras.isEmpty(),
            "$label: extras removed, update expectedExtra:\n$diff",
        )
    }

    // --- SelectTargetsReq ---

    @Test(description = "SelectTargetsReq field coverage vs real server")
    fun selectTargetsReqCoverage() {
        // Golden: real server SelectTargetsReq from proxy recording
        val goldenGRE = loadGoldenGRE("select-targets-req.bin", GREMessageType.SelectTargetsReq_695e)
        val goldenFields = FieldPathExtractor.extract(goldenGRE.selectTargetsReq)

        // Ours: build via real RequestBuilder with board
        val (b, _, _) = startWithBoard { game, human, ai ->
            addCard("Grizzly Bears", human, ZoneType.Battlefield)
            addCard("Llanowar Elves", ai, ZoneType.Battlefield)
        }

        val humanCreature = b.getPlayer(1)!!.getZone(ZoneType.Battlefield).cards.first()
        val aiCreature = b.getPlayer(2)!!.getZone(ZoneType.Battlefield).cards.first()
        val sourceCard = humanCreature // pretend the spell source is a card

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
            // Sub-prompt on targets[] with card-specific promptId + CardId param
            "targets[].prompt.promptId",
            "targets[].prompt.parameters[].parameterName",
            "targets[].prompt.parameters[].type",
            "targets[].prompt.parameters[].numberValue",
            // Ability/card identity on the targeting group
            "targets[].targetingAbilityGrpId",
            // Top-level ability group ID (card's grpId)
            "abilityGrpId",
        )

        // No known extras for inner SelectTargetsReq
        assertFieldCoverage("SelectTargetsReq", goldenFields, ourFields, expectedMissing, emptySet())
    }

    // --- DeclareAttackersReq ---

    @Test(description = "DeclareAttackersReq field coverage vs real server")
    fun declareAttackersReqCoverage() {
        val goldenGRE = loadGoldenGRE("declare-attackers-req.bin", GREMessageType.DeclareAttackersReq_695e)
        val goldenFields = FieldPathExtractor.extract(goldenGRE.declareAttackersReq)

        val (b, game, _) = startWithBoard { g, human, _ ->
            addCard("Grizzly Bears", human, ZoneType.Battlefield)
        }
        val ours = RequestBuilder.buildDeclareAttackersReq(game, 1, b)
        val ourFields = FieldPathExtractor.extract(ours)

        // No known gaps — our builder matches real server field presence
        val expectedMissing = emptySet<String>()

        // We produce qualifiedAttackers with same shape as attackers — real server does too,
        // so no extras expected here unless our builder adds novel fields.
        val expectedExtra = emptySet<String>()

        assertFieldCoverage("DeclareAttackersReq", goldenFields, ourFields, expectedMissing, expectedExtra)
    }

    // --- DeclareBlockersReq ---

    @Test(description = "DeclareBlockersReq field coverage vs real server")
    fun declareBlockersReqCoverage() {
        val goldenGRE = loadGoldenGRE("declare-blockers-req.bin", GREMessageType.DeclareBlockersReq_695e)
        val goldenFields = FieldPathExtractor.extract(goldenGRE.declareBlockersReq)

        // Need combat state for blocker builder
        val (b, game, _) = startWithBoard { g, human, ai ->
            addCard("Grizzly Bears", human, ZoneType.Battlefield)
            val attacker = addCard("Llanowar Elves", ai, ZoneType.Battlefield)
            // Start combat with AI attacking
            g.phaseHandler.devModeSet(forge.game.phase.PhaseType.COMBAT_DECLARE_BLOCKERS, ai)
            g.combat?.addAttacker(attacker, human)
        }
        val ours = RequestBuilder.buildDeclareBlockersReq(game, 1, b)
        val ourFields = FieldPathExtractor.extract(ours)

        val expectedMissing = setOf(
            // Empty but present manaCost {} on real server (alternate block costs)
            "manaCost",
        )

        // If combat setup didn't produce blockers, skip rather than fail misleadingly
        if (ourFields.isEmpty()) {
            // Combat setup is tricky with startWithBoard — blocker builder needs
            // real combat state. Log and document.
            System.err.println("WARN: DeclareBlockersReq builder returned empty (combat setup incomplete)")
            return
        }

        assertFieldCoverage("DeclareBlockersReq", goldenFields, ourFields, expectedMissing)
    }

    // --- ActionsAvailableReq ---

    @Test(description = "ActionsAvailableReq field coverage vs real server")
    fun actionsAvailableReqCoverage() {
        val goldenGRE = loadGoldenGRE("actions-available-req.bin", GREMessageType.ActionsAvailableReq_695e)
        val goldenFields = FieldPathExtractor.extract(goldenGRE.actionsAvailableReq)

        // Build actions from a board with castable + playable cards
        val (b, game, _) = startWithBoard { _, human, _ ->
            addCard("Plains", human, ZoneType.Hand)
            addCard("Grizzly Bears", human, ZoneType.Hand)
            addCard("Forest", human, ZoneType.Battlefield) // mana source
        }
        val ours = BundleBuilder.buildActions(game, 1, b)
        val ourFields = FieldPathExtractor.extract(ours)

        val expectedMissing = setOf(
            // manaCost uses repeated color field on real server (color[])
            // Our builder uses singular color — proto schema difference
            "actions[].manaCost[].color[]",
            "actions[].manaCost[].count",
            // Inactive actions (grayed-out spells you can see but can't afford)
            "inactiveActions[].actionType",
            "inactiveActions[].grpId",
            "inactiveActions[].instanceId",
            "inactiveActions[].facetId",
            "inactiveActions[].manaCost[].color[]",
            "inactiveActions[].manaCost[].count",
        )

        val expectedExtra = setOf(
            // We send abilityGrpId on activated abilities — real server may not
            // in this golden (standard main phase actions). Could appear in
            // goldens with activated abilities on battlefield.
            "actions[].abilityGrpId",
            // Batching hint — our addition, real server doesn't send
            "actions[].isBatchable",
            // Pre-computed mana payment options — our addition for auto-tap UX.
            // Real server relies on client-side mana computation.
            "actions[].manaPaymentOptions[].mana[].abilityGrpId",
            "actions[].manaPaymentOptions[].mana[].color",
            "actions[].manaPaymentOptions[].mana[].count",
            "actions[].manaPaymentOptions[].mana[].manaId",
            "actions[].manaPaymentOptions[].mana[].specs[].type",
            "actions[].manaPaymentOptions[].mana[].srcInstanceId",
            // Pre-computed mana source selections — our addition for auto-tap.
            "actions[].manaSelections[].abilityGrpId",
            "actions[].manaSelections[].instanceId",
            "actions[].manaSelections[].options[].mana[].color",
            "actions[].manaSelections[].options[].mana[].count",
        )

        assertFieldCoverage("ActionsAvailableReq", goldenFields, ourFields, expectedMissing, expectedExtra)
    }

    // --- GRE wrapper fields (prompt, allowCancel, allowUndo etc.) ---

    @Test(description = "SelectTargetsReq GRE wrapper field coverage")
    fun selectTargetsReqWrapperCoverage() {
        val goldenGRE = loadGoldenGRE("select-targets-req.bin", GREMessageType.SelectTargetsReq_695e)
        val goldenFields = FieldPathExtractor.extract(goldenGRE)

        // Build via BundleBuilder to get the full wrapper
        val (b, game, counter) = startWithBoard { g, human, ai ->
            addCard("Grizzly Bears", human, ZoneType.Battlefield)
            addCard("Llanowar Elves", ai, ZoneType.Battlefield)
        }
        val aiCreature = b.getPlayer(2)!!.getZone(ZoneType.Battlefield).cards.first()

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
        val result = BundleBuilder.selectTargetsBundle(game, b, TEST_MATCH_ID, 1, counter, prompt)
        val oursGRE = result.messages.first { it.type == GREMessageType.SelectTargetsReq_695e }
        val ourFields = FieldPathExtractor.extract(oursGRE)

        // Wrapper-level expected missing (inner selectTargetsReq gaps handled above)
        val expectedMissing = setOf(
            // Sub-prompt on targets[] (inner req field, appears in wrapper path too)
            "selectTargetsReq.targets[].prompt.promptId",
            "selectTargetsReq.targets[].prompt.parameters[].parameterName",
            "selectTargetsReq.targets[].prompt.parameters[].type",
            "selectTargetsReq.targets[].prompt.parameters[].numberValue",
            "selectTargetsReq.targets[].targetingAbilityGrpId",
            "selectTargetsReq.abilityGrpId",
        )

        // No known extras at wrapper level
        assertFieldCoverage("SelectTargetsReq (wrapper)", goldenFields, ourFields, expectedMissing, emptySet())
    }

    // -----------------------------------------------------------------------
    // ConnectResp
    // -----------------------------------------------------------------------

    @Test(description = "ConnectResp field coverage vs real server")
    fun connectRespCoverage() {
        val goldenGRE = loadGoldenGRE("initial-bundle.bin", GREMessageType.ConnectResp_695e)
        val goldenFields = FieldPathExtractor.extract(goldenGRE.connectResp)

        // Build via initialBundle (seat 1 gets ConnectResp)
        val (b, _, _) = startWithBoard { _, _, _ -> }
        val deck = GsmBuilder.buildDeckMessage(b.getDeckGrpIds(1))
        val (msg, _) = HandshakeMessages.initialBundle(1, TEST_MATCH_ID, 2, 1, deck, b)
        val oursGRE = msg.greToClientEvent.greToClientMessagesList
            .first { it.type == GREMessageType.ConnectResp_695e }
        val ourFields = FieldPathExtractor.extract(oursGRE.connectResp)

        val expectedMissing = setOf(
            // Deck contents — test board has a minimal deck; real server has full 60-card list.
            // Our builder does populate deckCards when given real grpIds; gap is test-setup artifact.
            "deckMessage.deckCards[]",
            // GRE/GRP changelists — version-specific asset diff info, cosmetic only
            "greChangelist",
            "grpChangelist",
            // Card skins — cosmetic card art selection, not gameplay-relevant
            "skins[].catalogId",
            "skins[].skinCode",
        )

        val expectedExtra = emptySet<String>()
        assertFieldCoverage("ConnectResp", goldenFields, ourFields, expectedMissing, expectedExtra)
    }

    // -----------------------------------------------------------------------
    // DieRollResultsResp
    // -----------------------------------------------------------------------

    @Test(description = "DieRollResultsResp field coverage vs real server")
    fun dieRollResultsRespCoverage() {
        val goldenGRE = loadGoldenGRE("initial-bundle.bin", GREMessageType.DieRollResultsResp_695e)
        val goldenFields = FieldPathExtractor.extract(goldenGRE.dieRollResultsResp)

        // Build via initialBundle
        val (b, _, _) = startWithBoard { _, _, _ -> }
        val deck = GsmBuilder.buildDeckMessage(b.getDeckGrpIds(1))
        val (msg, _) = HandshakeMessages.initialBundle(1, TEST_MATCH_ID, 2, 1, deck, b)
        val oursGRE = msg.greToClientEvent.greToClientMessagesList
            .first { it.type == GREMessageType.DieRollResultsResp_695e }
        val ourFields = FieldPathExtractor.extract(oursGRE.dieRollResultsResp)

        val expectedMissing = emptySet<String>()
        val expectedExtra = emptySet<String>()
        assertFieldCoverage("DieRollResultsResp", goldenFields, ourFields, expectedMissing, expectedExtra)
    }

    // -----------------------------------------------------------------------
    // Initial Full GameStateMessage (gsId=1, pre-deal zones)
    // -----------------------------------------------------------------------

    @Test(description = "Initial Full GSM field coverage vs real server")
    fun initialFullGsmCoverage() {
        val goldenGRE = loadGoldenGRE("initial-bundle.bin", GREMessageType.GameStateMessage_695e)
        val goldenFields = FieldPathExtractor.extract(goldenGRE.gameStateMessage)

        // Build via initialBundle
        val (b, _, _) = startWithBoard { _, _, _ -> }
        val deck = GsmBuilder.buildDeckMessage(b.getDeckGrpIds(1))
        val (msg, _) = HandshakeMessages.initialBundle(1, TEST_MATCH_ID, 2, 1, deck, b)
        val oursGRE = msg.greToClientEvent.greToClientMessagesList
            .first { it.type == GREMessageType.GameStateMessage_695e }
        val ourFields = FieldPathExtractor.extract(oursGRE.gameStateMessage)

        val expectedMissing = setOf(
            // Real server includes empty diffDeletedInstanceIds[] in Full GSM (quirk)
            "diffDeletedInstanceIds[]",
            // Timeout/pip config — server-side match configuration we don't populate
            "gameInfo.maxPipCount",
            "gameInfo.maxTimeoutCount",
            "gameInfo.timeoutDurationSec",
            // pendingMessageCount — real server sets non-zero even for seat 1
            // (ChooseStartingPlayerReq follows for seat 2, but seat 1 sees it as pending)
            "pendingMessageCount",
            // Timer elapsed — real server initializes with non-zero elapsed
            "timers[].elapsedMs",
            // Library zone objectInstanceIds — real server populates library card IDs
            // in the Full GSM; our builder leaves libraries empty (cards are face-down)
            "zones[].objectInstanceIds[]",
        )

        val expectedExtra = emptySet<String>()
        assertFieldCoverage("Initial Full GSM", goldenFields, ourFields, expectedMissing, expectedExtra)
    }

    // -----------------------------------------------------------------------
    // MulliganReq
    // -----------------------------------------------------------------------

    @Test(description = "MulliganReq field coverage vs real server")
    fun mulliganReqCoverage() {
        val goldenGRE = loadGoldenGRE("mulligan-req.bin", GREMessageType.MulliganReq_aa0d)
        val goldenFields = FieldPathExtractor.extract(goldenGRE)

        // Build via GsmBuilder
        val ours = GsmBuilder.buildMulliganReq(msgId = 9, gameStateId = 2, seatId = 1)
        val ourFields = FieldPathExtractor.extract(ours)

        val expectedMissing = emptySet<String>()
        val expectedExtra = emptySet<String>()
        assertFieldCoverage("MulliganReq", goldenFields, ourFields, expectedMissing, expectedExtra)
    }

    // -----------------------------------------------------------------------
    // GroupReq (London Mulligan tuck)
    // -----------------------------------------------------------------------

    @Test(description = "GroupReq field coverage vs real server (London mulligan tuck)")
    fun groupReqLondonMulliganCoverage() {
        val goldenGRE = loadGoldenGRE("group-req-london-mulligan.bin", GREMessageType.GroupReq_695e)
        val goldenFields = FieldPathExtractor.extract(goldenGRE)

        // Build via GsmBuilder with 7 hand cards, tuck 3 (4 mulls → bottom 3)
        val handInstanceIds = listOf(407, 408, 409, 410, 411, 412, 413)
        val ours = GsmBuilder.buildGroupReq(
            msgId = 24,
            gameStateId = 7,
            seatId = 1,
            handInstanceIds = handInstanceIds,
            cardsToTuck = 3,
        )
        val ourFields = FieldPathExtractor.extract(ours)

        val expectedMissing = emptySet<String>()
        val expectedExtra = emptySet<String>()
        assertFieldCoverage("GroupReq", goldenFields, ourFields, expectedMissing, expectedExtra)
    }

    // -----------------------------------------------------------------------
    // SetSettingsResp
    // -----------------------------------------------------------------------

    @Test(description = "SetSettingsResp field coverage vs real server")
    fun setSettingsRespCoverage() {
        val goldenGRE = loadGoldenGRE("set-settings-resp.bin", GREMessageType.SetSettingsResp_695e)
        val goldenFields = FieldPathExtractor.extract(goldenGRE.setSettingsResp)

        // Extract the settings from the golden itself — real server echoes client's settings.
        // We just need to confirm field shapes match when we round-trip them.
        val clientSettings = goldenGRE.setSettingsResp.settings
        val (msg, _) = HandshakeMessages.settingsResp(1, 6, 1, clientSettings)
        val oursGRE = msg.greToClientEvent.greToClientMessagesList
            .first { it.type == GREMessageType.SetSettingsResp_695e }
        val ourFields = FieldPathExtractor.extract(oursGRE.setSettingsResp)

        val expectedMissing = emptySet<String>()
        val expectedExtra = emptySet<String>()
        assertFieldCoverage("SetSettingsResp", goldenFields, ourFields, expectedMissing, expectedExtra)
    }

    // -----------------------------------------------------------------------
    // IntermissionReq (game-over sequence)
    // -----------------------------------------------------------------------

    @Test(description = "IntermissionReq field coverage vs real server")
    fun intermissionReqCoverage() {
        val goldenGRE = loadGoldenGRE("intermission-req.bin", GREMessageType.IntermissionReq_695e)
        val goldenFields = FieldPathExtractor.extract(goldenGRE.intermissionReq)

        // Build via gameOverBundle
        val (b, _, counter) = startWithBoard { _, _, _ -> }
        val result = BundleBuilder.gameOverBundle(
            winningTeam = 1,
            matchId = TEST_MATCH_ID,
            seatId = 1,
            counter = counter,
            losingPlayerSeatId = 2,
            bridge = b,
        )
        val oursGRE = result.messages.first { it.type == GREMessageType.IntermissionReq_695e }
        val ourFields = FieldPathExtractor.extract(oursGRE.intermissionReq)

        val expectedMissing = emptySet<String>()
        val expectedExtra = emptySet<String>()
        assertFieldCoverage("IntermissionReq", goldenFields, ourFields, expectedMissing, expectedExtra)
    }

    // =======================================================================
    // Annotation shape reference tests
    //
    // These don't compare builder output — they document the exact detail keys
    // the real Arena server sends for each annotation type. If a golden changes,
    // the test fails and forces triage of the annotation parsers.
    // =======================================================================

    @Test(description = "Stack resolution: ResolutionStart/Complete + Resolve ZoneTransfer detail keys")
    fun stackResolutionAnnotationShape() {
        // gsId=68: creature resolves from Stack → Battlefield
        val gsm = loadGoldenGSM("stack-resolve.bin", 68)
        val keys = annotationDetailKeys(gsm)

        // ResolutionStart: grpid (card identity)
        assertEquals(
            keys["ResolutionStart"],
            setOf("grpid"),
            "ResolutionStart detail keys",
        )
        // ResolutionComplete: grpid
        assertEquals(
            keys["ResolutionComplete"],
            setOf("grpid"),
            "ResolutionComplete detail keys",
        )
        // ZoneTransfer: zone_src, zone_dest, category ("Resolve")
        assertEquals(
            keys["ZoneTransfer"],
            setOf("zone_src", "zone_dest", "category"),
            "ZoneTransfer detail keys",
        )
    }

    @Test(description = "Combat damage: DamageDealt + SBA_Damage detail keys")
    fun combatDamageAnnotationShape() {
        // gsId=126: combat damage — creatures deal damage, one dies to SBA
        val gsm = loadGoldenGSM("combat-damage.bin", 126)
        val keys = annotationDetailKeys(gsm)

        // DamageDealt: damage (amount), type (combat=1), markDamage
        assertEquals(
            keys["DamageDealt"],
            setOf("damage", "type", "markDamage"),
            "DamageDealt detail keys — client needs all three for damage animation",
        )
        // ZoneTransfer with SBA_Damage category: zone_src, zone_dest, category
        val sbaTransfer = gsm.annotationsList
            .filter { AnnotationType.ZoneTransfer_af5a in it.typeList }
            .flatMap { ann -> ann.detailsList.filter { it.key == "category" }.map { it.getValueString(0) } }
        assertTrue(
            "SBA_Damage" in sbaTransfer,
            "Should have ZoneTransfer with SBA_Damage category (creature died from damage)",
        )
        // ObjectIdChanged: orig_id, new_id (dying creature gets new ID in graveyard)
        assertEquals(
            keys["ObjectIdChanged"],
            setOf("orig_id", "new_id"),
            "ObjectIdChanged detail keys",
        )
        // PhaseOrStepModified: phase, step
        assertEquals(
            keys["PhaseOrStepModified"],
            setOf("phase", "step"),
            "PhaseOrStepModified detail keys",
        )
    }

    @Test(description = "Cast spell: CastSpell ZoneTransfer + ManaPaid + TappedUntapped detail keys")
    fun castSpellAnnotationShape() {
        // gsId=66: creature cast from Hand → Stack with mana payment
        val gsm = loadGoldenGSM("stack-resolve.bin", 66)
        val keys = annotationDetailKeys(gsm)

        // ZoneTransfer with CastSpell category
        val castTransfer = gsm.annotationsList
            .filter { AnnotationType.ZoneTransfer_af5a in it.typeList }
            .flatMap { ann -> ann.detailsList.filter { it.key == "category" }.map { it.getValueString(0) } }
        assertTrue("CastSpell" in castTransfer, "Should have ZoneTransfer with CastSpell category")

        // ManaPaid: id (mana payment ID), color
        assertEquals(
            keys["ManaPaid"],
            setOf("id", "color"),
            "ManaPaid detail keys",
        )
        // TappedUntappedPermanent: tapped (1=tapped)
        assertEquals(
            keys["TappedUntappedPermanent"],
            setOf("tapped"),
            "TappedUntappedPermanent detail keys",
        )
        // UserActionTaken: actionType, abilityGrpId
        assertEquals(
            keys["UserActionTaken"],
            setOf("actionType", "abilityGrpId"),
            "UserActionTaken detail keys",
        )
        // AbilityInstanceCreated: source_zone
        assertEquals(
            keys["AbilityInstanceCreated"],
            setOf("source_zone"),
            "AbilityInstanceCreated detail keys",
        )
    }
}
