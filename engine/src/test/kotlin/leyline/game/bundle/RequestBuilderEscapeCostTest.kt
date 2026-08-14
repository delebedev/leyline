package leyline.game.bundle

import forge.game.card.Card
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptRouteResolver
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.PromptCandidateKind
import leyline.bridge.types.PromptCandidateRefDto
import leyline.bridge.types.SeatId
import leyline.game.mapping.PromptIds
import leyline.testkit.BoardTest
import wotc.mtgo.gre.external.messaging.Messages.EffectCostType
import wotc.mtgo.gre.external.messaging.Messages.IdType
import wotc.mtgo.gre.external.messaging.Messages.OptionContext
import wotc.mtgo.gre.external.messaging.Messages.SelectionContext
import wotc.mtgo.gre.external.messaging.Messages.SelectionListType
import wotc.mtgo.gre.external.messaging.Messages.SelectionValidationType

/**
 * Pin every field of the canonical "select N as cost" PayCostsReq envelope.
 *
 * Sacrifice and Escape (exile-from-grave) share `buildSelectCostPayCostsReq`.
 * Each missing field manifests as the same client symptom (picker renders,
 * candidates are non-clickable / greyed out), so a binary "does it work" check
 * doesn't distinguish them — the field-by-field assertion below would have
 * caught Escape's missing `paymentActions` / `minSel` / `minWeight` /
 * `maxWeight` regressions in one run.
 *
 * Builder source: `RequestBuilder.buildSelectCostPayCostsReq`. Sacrifice
 * defaults via `buildSacrificePayCostsReq`.
 */
class RequestBuilderEscapeCostTest :
    BoardTest({

        test("buildSelectCostPayCostsReq emits the full non-mana cost envelope") {
            val (b, _, _) = startWithBoard { _, _, _ -> }
            val sourceForgeId = 100
            val candidateForgeIds = listOf(101, 102, 103)
            // Allocate iids so the builder's getOrAllocInstanceId calls return
            // stable values to assert against.
            val sourceIid = b.getOrAllocInstanceId(ForgeCardId(sourceForgeId)).value
            val candidateIids = candidateForgeIds.map { b.getOrAllocInstanceId(ForgeCardId(it)).value }

            val request =
                PromptRequest(
                    promptType = "choose_cards",
                    message = "Exile 3 other cards from your graveyard",
                    options = candidateForgeIds.map { "Card$it" },
                    min = 0, // upstream callers pass 0 for non-mandatory; builder must coerce
                    max = 3,
                    route = PromptRouteResolver.resolve(PromptSemantic.SelectNCostExileFromGrave),
                    candidateRefs =
                        candidateForgeIds.mapIndexed { idx, forgeId ->
                            PromptCandidateRefDto(idx, PromptCandidateKind.Card, forgeId)
                        },
                    sourceEntityId = sourceForgeId,
                )
            val pending =
                InteractivePromptBridge.PendingPrompt(
                    promptId = "test-cost-select",
                    request = request,
                    future = java.util.concurrent.CompletableFuture(),
                )

            val (req, prompt) =
                RequestBuilder.buildSelectCostPayCostsReq(
                    pending,
                    b,
                    PromptIds.CHOOSE_OR_COST_PAY_EXILE_FROM_GRAVE,
                )

            assertSoftly {
                // PayCostsReq envelope: paymentActions present (empty struct) and
                // effectCostReq populated with EffectCostType.Select.
                req.hasPaymentActions() shouldBe true
                req.hasEffectCostReq() shouldBe true
                req.effectCostReq.effectCostType shouldBe EffectCostType.Select_a59c

                // costSelection (a SelectNReq nested in EffectCostReq).
                val sel = req.effectCostReq.costSelection
                // min coerced to max (cost-payment is mandatory: pay exactly N).
                sel.minSel shouldBe 3
                sel.maxSel shouldBe 3
                sel.context shouldBe SelectionContext.NonManaPayment
                sel.optionContext shouldBe OptionContext.Payment
                sel.listType shouldBe SelectionListType.Dynamic
                sel.idType shouldBe IdType.InstanceId_ab2c
                sel.validationType shouldBe SelectionValidationType.NonRepeatable
                // Weight extremes set explicitly — proto3 defaults (0, 0) cause
                // the client to treat candidates as non-selectable.
                sel.minWeight shouldBe Int.MIN_VALUE
                sel.maxWeight shouldBe Int.MAX_VALUE
                // ids + weights match candidate count, weights are 1 each.
                sel.idsList shouldHaveSize 3
                sel.idsList.toList() shouldBe candidateIids
                sel.weightsList shouldHaveSize 3
                sel.weightsList.all { it == 1 } shouldBe true

                // Outer Prompt: promptId per cost flavor + CardId source param.
                prompt.promptId shouldBe PromptIds.CHOOSE_OR_COST_PAY_EXILE_FROM_GRAVE
                prompt.parametersCount shouldBeGreaterThan 0
                val cardIdParam = prompt.parametersList.first { it.parameterName == "CardId" }
                cardIdParam.numberValue shouldBe sourceIid

                // Defensive: ensure bridge / candidates didn't mis-allocate.
                sourceIid shouldNotBe 0
                candidateIids.all { it > 0 } shouldBe true
                SeatId(1).value shouldBe 1
            }
        }

        test("buildSacrificePayCostsReq is the same envelope with the sacrifice promptId") {
            val (b, _, _) = startWithBoard { _, _, _ -> }
            val sourceForgeId = 200
            val candidateForgeIds = listOf(201)
            b.getOrAllocInstanceId(ForgeCardId(sourceForgeId))
            candidateForgeIds.forEach { b.getOrAllocInstanceId(ForgeCardId(it)) }

            val request =
                PromptRequest(
                    promptType = "choose_cards",
                    message = "Sacrifice a creature",
                    options = listOf("Creature"),
                    min = 1,
                    max = 1,
                    candidateRefs =
                        candidateForgeIds.mapIndexed { idx, forgeId ->
                            PromptCandidateRefDto(idx, PromptCandidateKind.Card, forgeId)
                        },
                    sourceEntityId = sourceForgeId,
                )
            val pending =
                InteractivePromptBridge.PendingPrompt(
                    promptId = "test-sac",
                    request = request,
                    future = java.util.concurrent.CompletableFuture(),
                )

            val (req, prompt) = RequestBuilder.buildSacrificePayCostsReq(pending, b)

            // The shared envelope shape — sacrifice and exile-from-grave only
            // diverge on promptId.
            assertSoftly {
                req.hasPaymentActions() shouldBe true
                req.effectCostReq.effectCostType shouldBe EffectCostType.Select_a59c
                req.effectCostReq.costSelection.context shouldBe SelectionContext.NonManaPayment
                req.effectCostReq.costSelection.optionContext shouldBe OptionContext.Payment
                req.effectCostReq.costSelection.minWeight shouldBe Int.MIN_VALUE
                req.effectCostReq.costSelection.maxWeight shouldBe Int.MAX_VALUE
                prompt.promptId shouldBe PromptIds.CHOOSE_OR_COST_PAY_SACRIFICE
            }
        }

        test("buildStationTapCostPayCostsReq uses Station prompt id with cost envelope") {
            val (b, _, _) = startWithBoard { _, _, _ -> }
            val stationAbilityForgeId = 300
            val creatureForgeId = 301
            val stationAbilityIid = b.getOrAllocInstanceId(ForgeCardId(stationAbilityForgeId)).value
            val creatureIid = b.getOrAllocInstanceId(ForgeCardId(creatureForgeId)).value

            val request =
                PromptRequest(
                    promptType = "choose_cards",
                    message = "Tap a creature to add charge counters",
                    options = listOf("Creature"),
                    min = 1,
                    max = 1,
                    route = PromptRouteResolver.resolve(PromptSemantic.StationTapCost),
                    candidateRefs = listOf(PromptCandidateRefDto(0, PromptCandidateKind.Card, creatureForgeId)),
                    sourceEntityId = stationAbilityForgeId,
                )
            val pending =
                InteractivePromptBridge.PendingPrompt(
                    promptId = "test-station",
                    request = request,
                    future = java.util.concurrent.CompletableFuture(),
                )

            val (req, prompt) = RequestBuilder.buildStationTapCostPayCostsReq(pending, b)

            assertSoftly {
                req.hasPaymentActions() shouldBe true
                req.effectCostReq.effectCostType shouldBe EffectCostType.Select_a59c
                req.effectCostReq.costSelection.context shouldBe SelectionContext.NonManaPayment
                req.effectCostReq.costSelection.optionContext shouldBe OptionContext.Payment
                req.effectCostReq.costSelection.idsList
                    .toList() shouldBe listOf(creatureIid)
                prompt.promptId shouldBe PromptIds.STATION_TAP_COST
                prompt.parametersList.first { it.parameterName == "CardId" }.numberValue shouldBe stationAbilityIid
            }
        }

        test("buildEnlistCostPayCostsReq uses Enlist prompt id with cost envelope") {
            val (b, _, _) = startWithBoard { _, _, _ -> }
            val attackerForgeId = 400
            val enlistedForgeId = 401
            val attackerIid = b.getOrAllocInstanceId(ForgeCardId(attackerForgeId)).value
            val enlistedIid = b.getOrAllocInstanceId(ForgeCardId(enlistedForgeId)).value

            val request =
                PromptRequest(
                    promptType = "choose_cards",
                    message = "Tap a creature to enlist",
                    options = listOf("Creature"),
                    min = 1,
                    max = 1,
                    route = PromptRouteResolver.resolve(PromptSemantic.EnlistCost),
                    candidateRefs = listOf(PromptCandidateRefDto(0, PromptCandidateKind.Card, enlistedForgeId)),
                    sourceEntityId = attackerForgeId,
                )
            val pending =
                InteractivePromptBridge.PendingPrompt(
                    promptId = "test-enlist",
                    request = request,
                    future = java.util.concurrent.CompletableFuture(),
                )

            val (req, prompt) = RequestBuilder.buildEnlistCostPayCostsReq(pending, b)

            assertSoftly {
                req.hasPaymentActions() shouldBe true
                req.effectCostReq.effectCostType shouldBe EffectCostType.Select_a59c
                req.effectCostReq.costSelection.context shouldBe SelectionContext.NonManaPayment
                req.effectCostReq.costSelection.idsList
                    .toList() shouldBe listOf(enlistedIid)
                prompt.promptId shouldBe PromptIds.ENLIST_TAP_COST
                prompt.parametersList.first { it.parameterName == "CardId" }.numberValue shouldBe attackerIid
            }
        }

        test("buildTeamworkCostPayCostsReq emits weighted power cost envelope") {
            val (b, _, _) = startWithBoard { _, _, _ -> }
            val sourceForgeId = 450
            val candidateForgeIds = listOf(451, 452, 453)
            val sourceIid = b.getOrAllocInstanceId(ForgeCardId(sourceForgeId)).value
            val candidateIids = candidateForgeIds.map { b.getOrAllocInstanceId(ForgeCardId(it)).value }

            val request =
                PromptRequest(
                    promptType = "choose_cards",
                    message = "Tap creatures with total power 2 or greater",
                    options = candidateForgeIds.map { "Creature$it" },
                    min = 1,
                    max = candidateForgeIds.size,
                    route = PromptRouteResolver.resolve(PromptSemantic.TeamworkCost),
                    candidateRefs =
                        candidateForgeIds.mapIndexed { idx, forgeId ->
                            PromptCandidateRefDto(idx, PromptCandidateKind.Card, forgeId)
                        },
                    costSelectionWeights = listOf(3, 2, 1),
                    minSelectionWeight = 2,
                    sourceEntityId = sourceForgeId,
                )
            val pending =
                InteractivePromptBridge.PendingPrompt(
                    promptId = "test-teamwork",
                    request = request,
                    future = java.util.concurrent.CompletableFuture(),
                )

            val (req, prompt) = RequestBuilder.buildTeamworkCostPayCostsReq(pending, b)

            assertSoftly {
                req.hasPaymentActions() shouldBe true
                req.effectCostReq.effectCostType shouldBe EffectCostType.Select_a59c
                val sel = req.effectCostReq.costSelection
                sel.minSel shouldBe 2
                sel.maxSel shouldBe Int.MAX_VALUE
                sel.context shouldBe SelectionContext.NonManaPayment
                sel.optionContext shouldBe OptionContext.Payment
                sel.listType shouldBe SelectionListType.Dynamic
                sel.idType shouldBe IdType.InstanceId_ab2c
                sel.validationType shouldBe SelectionValidationType.NonRepeatable
                sel.minWeight shouldBe Int.MIN_VALUE
                sel.maxWeight shouldBe Int.MAX_VALUE
                sel.idsList.toList() shouldBe candidateIids
                sel.weightsList.toList() shouldBe listOf(3, 2, 1)
                prompt.promptId shouldBe PromptIds.TEAMWORK_TAP_COST
                prompt.parametersList.first { it.parameterName == "CardId" }.numberValue shouldBe sourceIid
            }
        }

        test("CollectEvidencePayCostsBuilder emits weighted cost envelope") {
            val (b, _, _) = startWithBoard { _, _, _ -> }
            val sourceForgeId = 500
            val candidateForgeIds = listOf(501, 502, 503)
            val sourceIid = b.getOrAllocInstanceId(ForgeCardId(sourceForgeId)).value
            val candidateIids = candidateForgeIds.map { b.getOrAllocInstanceId(ForgeCardId(it)).value }

            val request =
                PromptRequest(
                    promptType = "choose_cards",
                    message = "Exile cards with total mana value 6 or greater",
                    options = candidateForgeIds.map { "Card$it" },
                    min = 0,
                    max = candidateForgeIds.size,
                    route = PromptRouteResolver.resolve(PromptSemantic.SelectNCostCollectEvidence),
                    candidateRefs =
                        candidateForgeIds.mapIndexed { idx, forgeId ->
                            PromptCandidateRefDto(idx, PromptCandidateKind.Card, forgeId)
                        },
                    costSelectionWeights = listOf(2, 4, 7),
                    minSelectionWeight = 6,
                    sourceEntityId = sourceForgeId,
                )
            val pending =
                InteractivePromptBridge.PendingPrompt(
                    promptId = "test-collect-evidence",
                    request = request,
                    future = java.util.concurrent.CompletableFuture(),
                )

            val (req, prompt) = CollectEvidencePayCostsBuilder.build(pending, b)

            assertSoftly {
                req.hasPaymentActions() shouldBe true
                req.effectCostReq.effectCostType shouldBe EffectCostType.Select_a59c
                val sel = req.effectCostReq.costSelection
                sel.minSel shouldBe 0
                sel.maxSel shouldBe 3
                sel.context shouldBe SelectionContext.NonManaPayment
                sel.optionContext shouldBe OptionContext.Payment
                sel.listType shouldBe SelectionListType.Dynamic
                sel.idType shouldBe IdType.InstanceId_ab2c
                sel.validationType shouldBe SelectionValidationType.NonRepeatable
                sel.minWeight shouldBe 6
                sel.maxWeight shouldBe Int.MAX_VALUE
                sel.idsList.toList() shouldBe candidateIids
                sel.weightsList.toList() shouldBe listOf(2, 4, 7)
                prompt.promptId shouldBe PromptIds.COLLECT_EVIDENCE_COST
                prompt.parametersList.first { it.parameterName == "CardId" }.numberValue shouldBe sourceIid
            }
        }
    })
