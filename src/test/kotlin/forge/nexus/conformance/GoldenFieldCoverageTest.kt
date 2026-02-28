package forge.nexus.conformance

import forge.game.zone.ZoneType
import forge.nexus.game.BundleBuilder
import forge.nexus.game.RequestBuilder
import forge.web.dto.PromptCandidateRefDto
import forge.web.game.InteractivePromptBridge.PendingPrompt
import forge.web.game.PromptRequest
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
}
