package leyline.conformance

import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import leyline.ConformanceTag
import leyline.bridge.InteractivePromptBridge.PendingPrompt
import leyline.bridge.PromptCandidateRefDto
import leyline.bridge.PromptRequest
import leyline.bridge.SeatId
import leyline.game.GsmBuilder
import leyline.game.RequestBuilder
import leyline.protocol.HandshakeMessages
import wotc.mtgo.gre.external.messaging.Messages.*
import java.util.concurrent.CompletableFuture

/**
 * Golden field coverage: compares field presence in our builder output
 * against hardcoded field sets extracted from real Arena server recordings.
 *
 * Each test:
 * 1. Defines the golden field set (from proxy capture)
 * 2. Builds our proto via real builders + minimal board -> extracts field paths
 * 3. Diffs: `expectedMissing` documents known gaps, test fails on NEW gaps or FIXED gaps
 *
 * `expectedMissing` IS the living documentation of what we don't produce yet.
 * When a gap is fixed: remove from `expectedMissing` -> test validates the fix.
 * When a new golden has unknown fields: test fails -> forced triage.
 */
class GoldenFieldCoverageTest :
    FunSpec({

        tags(ConformanceTag)

        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        // --- Arena field reference sets ---
        // Source: Arena proxy recording 2026-02-28, documented in #142

        val goldenSelectTargetsReqInner = setOf(
            "abilityGrpId", "sourceId", "targets[].maxTargets", "targets[].minTargets",
            "targets[].prompt.parameters[].numberValue", "targets[].prompt.parameters[].parameterName",
            "targets[].prompt.parameters[].type", "targets[].prompt.promptId", "targets[].targetIdx",
            "targets[].targetingAbilityGrpId", "targets[].targetingPlayer",
            "targets[].targets[].highlight", "targets[].targets[].legalAction",
            "targets[].targets[].targetInstanceId",
        )

        val goldenDeclareAttackersReq = setOf(
            "attackers[].attackerInstanceId",
            "attackers[].legalDamageRecipients[].playerSystemSeatId",
            "attackers[].legalDamageRecipients[].type",
            "canSubmitAttackers",
            "qualifiedAttackers[].attackerInstanceId",
            "qualifiedAttackers[].legalDamageRecipients[].playerSystemSeatId",
            "qualifiedAttackers[].legalDamageRecipients[].type",
        )

        val goldenDeclareBlockersReq = setOf(
            "blockers[].attackerInstanceIds[]",
            "blockers[].blockerInstanceId",
            "blockers[].maxAttackers",
        )

        val goldenActionsAvailableReq = setOf(
            "actions[].actionType", "actions[].facetId", "actions[].grpId", "actions[].instanceId",
            "actions[].manaCost[].color[]", "actions[].manaCost[].count", "actions[].shouldStop",
            "inactiveActions[].actionType", "inactiveActions[].facetId", "inactiveActions[].grpId",
            "inactiveActions[].instanceId", "inactiveActions[].manaCost[].color[]",
            "inactiveActions[].manaCost[].count",
        )

        val goldenConnectResp = setOf(
            "deckMessage.deckCards[]", "greChangelist", "greVersion.buildVersion", "greVersion.majorVersion",
            "greVersion.minorVersion", "grpChangelist", "grpVersion.buildVersion", "grpVersion.majorVersion",
            "grpVersion.minorVersion", "protoVer", "settings.autoOptionalPaymentCancellationSetting",
            "settings.autoPassOption", "settings.autoTapStopsSetting", "settings.defaultAutoPassOption",
            "settings.graveyardOrder", "settings.manaSelectionType", "settings.smartStopsSetting",
            "settings.stackAutoPassOption", "settings.stops[].appliesTo", "settings.stops[].status",
            "settings.stops[].stopType", "settings.transientStops[].appliesTo",
            "settings.transientStops[].status", "settings.transientStops[].stopType",
            "skins[].catalogId", "skins[].skinCode", "status",
        )

        val goldenMulliganReq = setOf(
            "gameStateId", "msgId", "mulliganReq.mulliganType",
            "prompt.parameters[].numberValue", "prompt.parameters[].parameterName",
            "prompt.parameters[].type", "prompt.promptId", "systemSeatIds[]", "type",
        )

        val goldenGroupReq = setOf(
            "allowCancel", "gameStateId", "groupReq.context", "groupReq.groupSpecs[].lowerBound",
            "groupReq.groupSpecs[].subZoneType", "groupReq.groupSpecs[].upperBound",
            "groupReq.groupSpecs[].zoneType", "groupReq.groupType", "groupReq.instanceIds[]",
            "groupReq.sourceId", "msgId", "prompt.parameters[].parameterName",
            "prompt.parameters[].type", "prompt.promptId", "systemSeatIds[]", "type",
        )

        val goldenSetSettingsResp = setOf(
            "settings.autoOptionalPaymentCancellationSetting", "settings.autoPassOption",
            "settings.autoSelectReplacementSetting", "settings.autoTapStopsSetting",
            "settings.defaultAutoPassOption", "settings.graveyardOrder", "settings.manaSelectionType",
            "settings.smartStopsSetting", "settings.stackAutoPassOption", "settings.stops[].appliesTo",
            "settings.stops[].status", "settings.stops[].stopType", "settings.transientStops[].appliesTo",
            "settings.transientStops[].status", "settings.transientStops[].stopType",
        )

        val goldenIntermissionReq = setOf(
            "intermissionPrompt.parameters[].numberValue", "intermissionPrompt.parameters[].parameterName",
            "intermissionPrompt.parameters[].type", "intermissionPrompt.promptId",
            "options[].optionPrompt.promptId", "options[].responseType", "result.reason",
            "result.result", "result.scope", "result.winningTeamId",
        )

        val goldenDieRollResultsResp = setOf(
            "playerDieRolls[].rollValue",
            "playerDieRolls[].systemSeatId",
        )

        val goldenInitialGSM = setOf(
            "diffDeletedInstanceIds[]", "gameInfo.deckConstraintInfo.maxDeckSize",
            "gameInfo.deckConstraintInfo.maxSideboardSize", "gameInfo.deckConstraintInfo.minDeckSize",
            "gameInfo.gameNumber", "gameInfo.matchID", "gameInfo.matchState", "gameInfo.matchWinCondition",
            "gameInfo.maxPipCount", "gameInfo.maxTimeoutCount", "gameInfo.mulliganType", "gameInfo.stage",
            "gameInfo.superFormat", "gameInfo.timeoutDurationSec", "gameInfo.type", "gameInfo.variant",
            "gameStateId", "pendingMessageCount", "players[].controllerSeatId", "players[].controllerType",
            "players[].lifeTotal", "players[].maxHandSize", "players[].pendingMessageType",
            "players[].startingLifeTotal", "players[].status", "players[].systemSeatNumber",
            "players[].teamId", "players[].timerIds[]", "teams[].id", "teams[].playerIds[]",
            "teams[].status", "timers[].behavior", "timers[].durationSec", "timers[].elapsedMs",
            "timers[].running", "timers[].timerId", "timers[].type", "timers[].warningThresholdSec",
            "turnInfo.decisionPlayer", "type", "update", "zones[].objectInstanceIds[]",
            "zones[].ownerSeatId", "zones[].type", "zones[].viewers[]", "zones[].visibility",
            "zones[].zoneId",
        )

        val goldenSelectTargetsReqWrapper = setOf(
            "allowCancel", "allowUndo", "gameStateId", "msgId", "prompt.promptId",
            "selectTargetsReq.abilityGrpId", "selectTargetsReq.sourceId",
            "selectTargetsReq.targets[].maxTargets", "selectTargetsReq.targets[].minTargets",
            "selectTargetsReq.targets[].prompt.parameters[].numberValue",
            "selectTargetsReq.targets[].prompt.parameters[].parameterName",
            "selectTargetsReq.targets[].prompt.parameters[].type",
            "selectTargetsReq.targets[].prompt.promptId", "selectTargetsReq.targets[].targetIdx",
            "selectTargetsReq.targets[].targetingAbilityGrpId", "selectTargetsReq.targets[].targetingPlayer",
            "selectTargetsReq.targets[].targets[].highlight", "selectTargetsReq.targets[].targets[].legalAction",
            "selectTargetsReq.targets[].targets[].targetInstanceId", "systemSeatIds[]", "type",
        )

        // --- Helpers ---

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

            @Suppress("UnusedPrivateProperty")
            val diff = FieldPathExtractor.formatDiff(golden, ours, expectedMissing)

            newGaps.shouldBeEmpty()
            fixedGaps.shouldBeEmpty()
            newExtras.shouldBeEmpty()
            removedExtras.shouldBeEmpty()
        }

        // --- SelectTargetsReq ---

        test("SelectTargetsReq field coverage vs real server") {
            val goldenFields = goldenSelectTargetsReqInner

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
            val goldenFields = goldenDeclareAttackersReq

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

        test("DeclareBlockersReq field coverage vs real server").config(tags = setOf(leyline.IntegrationTag)) {
            val goldenFields = goldenDeclareBlockersReq

            // Need a real Combat object — use MatchFlowHarness to reach declare blockers.
            // Human has a creature, AI attacks → human gets DeclareBlockersReq.
            val h = MatchFlowHarness(seed = 42L, deckList = COMBAT_DECK, validating = false)
            try {
                h.connectAndKeep()
                h.installScriptedAi(
                    listOf(
                        ScriptedAction.PlayLand("Mountain"),
                        ScriptedAction.CastSpell("Raging Goblin"),
                        ScriptedAction.Attack(listOf("Raging Goblin")),
                        ScriptedAction.PassPriority,
                    ),
                )

                // Human turn 1: play land, cast creature (potential blocker)
                h.playLand()
                h.castSpellByName("Raging Goblin")
                h.passPriority()

                // Skip human combat, let AI turn run (land + cast + attack)
                if (h.allMessages.any { it.hasDeclareAttackersReq() }) {
                    h.declareNoAttackers()
                }
                if (!h.allMessages.any { it.hasDeclareBlockersReq() }) {
                    leyline.game.advanceToPhase(h.bridge, "COMBAT_DECLARE_BLOCKERS")
                    h.triggerAutoPass()
                    h.drainSink()
                }

                val blockReq = h.allMessages.last { it.hasDeclareBlockersReq() }.declareBlockersReq
                val ourFields = FieldPathExtractor.extract(blockReq)

                val expectedMissing = emptySet<String>()
                assertFieldCoverage("DeclareBlockersReq", goldenFields, ourFields, expectedMissing)
            } finally {
                h.shutdown()
            }
        }

        // --- ActionsAvailableReq ---

        test("ActionsAvailableReq field coverage vs real server") {
            val goldenFields = goldenActionsAvailableReq

            val (b, game, _) = base.startWithBoard { _, human, _ ->
                base.addCard("Plains", human, ZoneType.Hand)
                base.addCard("Grizzly Bears", human, ZoneType.Hand)
                base.addCard("Forest", human, ZoneType.Battlefield)
            }
            val ours = base.bundleBuilder(b).buildActions(game)
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
            val goldenFields = goldenSelectTargetsReqWrapper

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
            val result = base.bundleBuilder(b).selectTargetsBundle(game, counter, prompt)
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
            val goldenFields = goldenConnectResp

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
            val goldenFields = goldenDieRollResultsResp

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
            val goldenFields = goldenInitialGSM

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
            val goldenFields = goldenMulliganReq

            val ours = GsmBuilder.buildMulliganReq(msgId = 9, gameStateId = 2, seatId = 1)
            val ourFields = FieldPathExtractor.extract(ours)

            assertFieldCoverage("MulliganReq", goldenFields, ourFields, emptySet(), emptySet())
        }

        // --- GroupReq ---

        test("GroupReq field coverage vs real server (London mulligan tuck)") {
            val goldenFields = goldenGroupReq

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
            val goldenFields = goldenSetSettingsResp

            // Build a settings message that covers all golden fields
            // (including autoSelectReplacementSetting which the real server sets)
            val clientSettings = SettingsMessage.newBuilder()
                .addStops(
                    Stop.newBuilder()
                        .setStopType(StopType.PrecombatMainPhase)
                        .setAppliesTo(SettingScope.Team_ac6e)
                        .setStatus(SettingStatus.Set),
                )
                .addTransientStops(
                    Stop.newBuilder()
                        .setStopType(StopType.PrecombatMainPhase)
                        .setAppliesTo(SettingScope.Team_ac6e)
                        .setStatus(SettingStatus.Clear_a3fe),
                )
                .setAutoPassOption(AutoPassOption.ResolveMyStackEffects)
                .setGraveyardOrder(OrderingType.OrderArbitraryAlways)
                .setManaSelectionType(ManaSelectionType.Auto_a88a)
                .setDefaultAutoPassOption(AutoPassOption.ResolveMyStackEffects)
                .setSmartStopsSetting(SmartStopsSetting.Enable_a188)
                .setAutoTapStopsSetting(AutoTapStopsSetting.Enable_ac12)
                .setAutoOptionalPaymentCancellationSetting(Setting.Enable_a20a)
                .setStackAutoPassOption(AutoPassOption.Clear_a465)
                .setAutoSelectReplacementSetting(Setting.Enable_a20a)
                .build()

            val (msg, _) = HandshakeMessages.settingsResp(1, 6, 1, clientSettings)
            val oursGRE = msg.greToClientEvent.greToClientMessagesList
                .first { it.type == GREMessageType.SetSettingsResp_695e }
            val ourFields = FieldPathExtractor.extract(oursGRE.setSettingsResp)

            assertFieldCoverage("SetSettingsResp", goldenFields, ourFields, emptySet(), emptySet())
        }

        // --- IntermissionReq ---

        test("IntermissionReq field coverage vs real server") {
            val goldenFields = goldenIntermissionReq

            val (b, _, counter) = base.startWithBoard { _, _, _ -> }
            val result = base.bundleBuilder(b).gameOverBundle(
                winningTeam = 1,
                counter = counter,
                losingPlayerSeatId = 2,
            )
            val oursGRE = result.messages.first { it.type == GREMessageType.IntermissionReq_695e }
            val ourFields = FieldPathExtractor.extract(oursGRE.intermissionReq)

            assertFieldCoverage("IntermissionReq", goldenFields, ourFields, emptySet(), emptySet())
        }

        // Annotation shape tests removed — fully covered by AnnotationShapeConformanceTest
    })
