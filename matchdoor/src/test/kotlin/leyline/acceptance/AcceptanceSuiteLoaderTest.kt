package leyline.acceptance

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import leyline.UnitTag

class AcceptanceSuiteLoaderTest :
    FunSpec({
        tags(UnitTag)

        test("parses backend-neutral executable steps") {
            val suite =
                AcceptanceSuiteLoader.loadFromText(
                    """
                    |name: sample
                    |scenarios:
                    |  - id: cast-face
                    |    puzzle: sample-cast-face
                    |    steps:
                    |      - wait:
                    |          action: { type: activate, card: Miscalculation }
                    |      - activate: { card: Miscalculation, zone: hand }
                    |      - choose: { optional_cost: kicker }
                    |      - choose: { cto_id: 1 }
                    |      - modal_choice: { index: 0 }
                    |      - static_choice: { id: 34 }
                    |      - optional_action: { accept: true }
                    |      - target: { side: ours, zone: battlefield, card: Lunarch Veteran }
                    |      - target: { side: opponent, zone: stack, card: Counterspell }
                    |      - block: { blocker: Centaur Courser, attacker: Juggernaut }
                    |      - cast: { card: Think Twice, zone: graveyard, alt_cost: jump_start }
                    |      - select_cost: { zone: hand, cards: [Coral Merfolk] }
                    |      - select_card: { zone: sideboard, card: Environmental Sciences }
                    |      - select_cards: { zone: library, cards: [Lightning Bolt, Counterspell] }
                    |      - order_cards: [Counterspell, Lightning Bolt]
                    |      - expect:
                    |          all:
                    |            - prompt: { type: OrderReq, prompt_id: 42 }
                    |            - battlefield_stats_at_least: { side: ours, card: Monastery Swiftspear, power: 2, toughness: 3 }
                    |            - zone_not_contains: { side: ours, zone: hand, card: Miscalculation }
                    |            - zone_count_at_least: { side: ours, zone: hand, count: 2 }
                    """.trimMargin(),
                )

            assertSoftly {
                suite.name shouldBe "sample"
                suite.scenarios shouldHaveSize 1
                val scenario = suite.scenarios.single()
                scenario.id shouldBe "cast-face"
                scenario.steps shouldHaveSize 16
                scenario.steps[0] shouldBe WaitStep(listOf(ActionAvailableCondition(AcceptanceActionType.Activate, "Miscalculation")))
                scenario.steps[1] shouldBe ActivateStep("Miscalculation", AcceptanceZone.Hand, 0)
                scenario.steps[2] shouldBe ChooseStep(AcceptanceCastingTimeOption.Kicker, null)
                scenario.steps[3] shouldBe ChooseStep(null, 1)
                scenario.steps[4] shouldBe ModalChoiceStep(0)
                scenario.steps[5] shouldBe StaticChoiceStep(34)
                scenario.steps[6] shouldBe OptionalActionStep(accept = true)
                scenario.steps[7] shouldBe TargetStep(CardTargetSpec(AcceptanceSide.Ours, AcceptanceZone.Battlefield, "Lunarch Veteran"))
                scenario.steps[8] shouldBe TargetStep(CardTargetSpec(AcceptanceSide.Opponent, AcceptanceZone.Stack, "Counterspell"))
                scenario.steps[9] shouldBe BlockStep("Centaur Courser", "Juggernaut")
                scenario.steps[10] shouldBe CastStep("Think Twice", AcceptanceZone.Graveyard, AcceptanceAltCost.JumpStart)
                scenario.steps[11] shouldBe SelectCostStep(zone = AcceptanceZone.Hand, cards = listOf("Coral Merfolk"))
                scenario.steps[12] shouldBe SelectCardStep(zone = AcceptanceZone.Sideboard, card = "Environmental Sciences")
                scenario.steps[13] shouldBe SelectCardsStep(zone = AcceptanceZone.Library, cards = listOf("Lightning Bolt", "Counterspell"))
                scenario.steps[14] shouldBe OrderCardsStep(listOf("Counterspell", "Lightning Bolt"))
                scenario.steps[15] shouldBe
                    ExpectStep(
                        listOf(
                            PromptCondition("OrderReq", 42),
                            BattlefieldStatsAtLeastCondition(AcceptanceSide.Ours, "Monastery Swiftspear", 2, 3),
                            ZoneNotContainsCondition(AcceptanceSide.Ours, AcceptanceZone.Hand, "Miscalculation"),
                            ZoneCountAtLeastCondition(AcceptanceSide.Ours, AcceptanceZone.Hand, 2),
                        ),
                    )
            }
        }

        test("rejects unknown step keys") {
            shouldThrow<IllegalStateException> {
                AcceptanceSuiteLoader.loadFromText(
                    """
                    name: bad
                    scenarios:
                      - id: bad-step
                        puzzle: any
                        steps:
                          - click_face: {}
                    """.trimIndent(),
                )
            }
        }
    })
