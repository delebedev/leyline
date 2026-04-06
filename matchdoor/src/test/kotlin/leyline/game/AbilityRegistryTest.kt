package leyline.game

import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.ConformanceTag
import leyline.conformance.CardDataDeriver
import leyline.conformance.ConformanceTestBase
import leyline.conformance.TestCardInjector

class AbilityRegistryTest :
    FunSpec({
        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        test("planeswalker loyalty abilities map to distinct abilityGrpId slots")
            .config(tags = setOf(ConformanceTag)) {
                val cardName = "Chandra, Torch of Defiance"
                val (b, game, _) = base.startWithBoard { _, _, _ -> }

                // Inject planeswalker onto battlefield
                val injected = TestCardInjector.inject(
                    b,
                    1,
                    cardName,
                    ZoneType.Battlefield,
                )
                val card = injected.card

                // Derive CardData from the live card (has game context → full abilities)
                val cardData = CardDataDeriver.fromForgeCard(card)

                // Chandra has 4 loyalty abilities (all activated, non-mana)
                val loyaltyAbilities = card.spellAbilities
                    .filter { it.isActivatedAbility && !it.isManaAbility() }
                loyaltyAbilities.shouldHaveSize(4)

                // CardData should have ability slots for all 4
                cardData.abilityIds.shouldHaveSize(4)

                // Build registry
                val registry = AbilityRegistry.build(card, cardData)

                // Each loyalty ability should map to a distinct abilityGrpId
                val mappedGrpIds = loyaltyAbilities.map { sa ->
                    val mapped = registry.forSpellAbility(sa.id)
                    mapped.shouldNotBeNull()
                    mapped
                }
                mappedGrpIds.distinct().shouldHaveSize(4)

                // The mapped grpIds should match the slots from cardData.abilityIds
                val expectedSlots = cardData.abilityIds.map { it.first }
                mappedGrpIds shouldBe expectedSlots
            }
    })
