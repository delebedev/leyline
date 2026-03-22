package leyline.conformance

import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import leyline.ConformanceTag
import leyline.game.AbilityRegistry
import leyline.game.mapper.ActionMapper
import wotc.mtgo.gre.external.messaging.Messages.ActionType

/**
 * Verifies the full pipeline: inject a multi-ability card, populate AbilityRegistry,
 * build actions via ActionMapper, and assert that abilityGrpIds are distinct and
 * match expected CardData slots.
 */
class AbilityGrpIdConformanceTest :
    FunSpec({
        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        test("planeswalker Activate actions have distinct abilityGrpIds")
            .config(tags = setOf(ConformanceTag)) {
                val cardName = "Chandra, Torch of Defiance"
                val (b, game, _) = base.startWithBoard { _, _, _ -> }

                // Inject planeswalker onto battlefield
                val injected = TestCardInjector.inject(b, 1, cardName, ZoneType.Battlefield)
                val card = injected.card

                // Re-derive CardData from the live card (has player → full spellAbilities).
                // The initial registration from TestCardRegistry uses a temp card (null player)
                // which may lack activated abilities. Re-registering updates the repo.
                val cardData = CardDataDeriver.fromForgeCard(card)
                TestCardRegistry.repo.registerData(cardData, cardName)

                // Build AbilityRegistry — maps Forge SA ids to abilityGrpId slots
                b.abilityRegistries[card.id] = AbilityRegistry.build(card, cardData)

                // Build actions for seat 1
                val actions = ActionMapper.buildActions(game, 1, b)

                // Find all Activate_add3 actions for the planeswalker
                val activateActions = actions.actionsList
                    .filter { it.actionType == ActionType.Activate_add3 && it.instanceId == injected.instanceId }

                // Chandra has 4 loyalty abilities; at least some should be playable
                activateActions.size shouldBeGreaterThan 0

                // Each should have a non-zero abilityGrpId
                for (action in activateActions) {
                    action.abilityGrpId shouldBeGreaterThan 0
                }

                // All abilityGrpIds should be distinct
                val grpIds = activateActions.map { it.abilityGrpId }
                grpIds.distinct() shouldHaveSize grpIds.size

                // The abilityGrpIds should match CardData.abilityIds slots in order
                val keywordCount = cardData.keywordAbilityGrpIds.size
                val expectedSlots = cardData.abilityIds
                    .drop(keywordCount) // skip keyword ability slots
                    .take(activateActions.size) // match the playable count
                    .map { it.first }
                grpIds shouldBe expectedSlots
            }
    })
