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
                    name: sample
                    scenarios:
                      - id: cast-face
                        puzzle: sample-cast-face
                        steps:
                          - wait:
                              action: { type: activate, card: Miscalculation }
                          - activate: { card: Miscalculation, zone: hand }
                          - choose: { optional_cost: kicker }
                          - choose: { cto_id: 1 }
                          - modal_choice: { index: 0 }
                          - optional_action: { accept: true }
                          - target: { side: ours, zone: battlefield, card: Lunarch Veteran }
                          - block: { blocker: Centaur Courser, attacker: Juggernaut }
                          - cast: { card: Think Twice, zone: graveyard, alt_cost: jump_start }
                          - select_cost: { zone: hand, cards: [Coral Merfolk] }
                          - expect:
                              all:
                                - battlefield_stats_at_least: { side: ours, card: Monastery Swiftspear, power: 2, toughness: 3 }
                                - zone_not_contains: { side: ours, zone: hand, card: Miscalculation }
                                - zone_count_at_least: { side: ours, zone: hand, count: 2 }
                    """.trimIndent(),
                )

            assertSoftly {
                suite.name shouldBe "sample"
                suite.scenarios shouldHaveSize 1
                val scenario = suite.scenarios.single()
                scenario.id shouldBe "cast-face"
                scenario.steps shouldHaveSize 11
                scenario.steps[0] shouldBe WaitStep(listOf(ActionAvailableCondition(AcceptanceActionType.Activate, "Miscalculation")))
                scenario.steps[1] shouldBe ActivateStep("Miscalculation", AcceptanceZone.Hand, 0)
                scenario.steps[2] shouldBe ChooseStep(AcceptanceCastingTimeOption.Kicker, null)
                scenario.steps[3] shouldBe ChooseStep(null, 1)
                scenario.steps[4] shouldBe ModalChoiceStep(0)
                scenario.steps[5] shouldBe OptionalActionStep(accept = true)
                scenario.steps[6] shouldBe TargetStep(CardTargetSpec(AcceptanceSide.Ours, AcceptanceZone.Battlefield, "Lunarch Veteran"))
                scenario.steps[7] shouldBe BlockStep("Centaur Courser", "Juggernaut")
                scenario.steps[8] shouldBe CastStep("Think Twice", AcceptanceZone.Graveyard, AcceptanceAltCost.JumpStart)
                scenario.steps[9] shouldBe SelectCostStep(zone = AcceptanceZone.Hand, cards = listOf("Coral Merfolk"))
                scenario.steps[10] shouldBe
                    ExpectStep(
                        listOf(
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
