package leyline.conformance

import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import leyline.BoardTag
import leyline.game.mapping.ActionMapper
import leyline.game.snapshot.GsmSnapshot
import leyline.testkit.BoardTestBase
import leyline.testkit.CardDataDeriver
import leyline.testkit.TestCardInjector
import leyline.testkit.TestCardRegistry
import wotc.mtgo.gre.external.messaging.Messages.ActionType

/**
 * Verifies the full pipeline: inject a multi-ability card, populate AbilityRegistry,
 * build actions via ActionMapper, and assert that abilityGrpIds are distinct and
 * match expected CardData slots.
 */
class AbilityGrpIdConformanceTest :
    FunSpec({
        val base = BoardTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        test("planeswalker Activate actions have distinct abilityGrpIds")
            .config(tags = setOf(BoardTag)) {
                val cardName = "Chandra, Torch of Defiance"
                val (b, game, _) = base.startWithBoard { _, _, _ -> }

                // Inject planeswalker onto battlefield
                val injected = TestCardInjector.inject(b, 1, cardName, ZoneType.Battlefield)
                val card = injected.card

                // Re-derive CardData from the live card (has player → full spellAbilities).
                // The initial registration from TestCardRegistry uses a temp card (null player)
                // which may lack activated abilities. Re-registering updates the repo with
                // the post-injection ability shape, stamped with the fixture's client identity.
                val cardData = CardDataDeriver.fromForgeCard(card, cardName)
                TestCardRegistry.repo.registerData(cardData, cardName)

                // AbilityRegistry is lazily built by GameBridge.abilityRegistryFor on first access

                // Build actions for seat 1
                val actions = ActionMapper.buildFromSnapshot(1, GsmSnapshot.capture(game, b, "test", 0), b)

                // Find all Activate_add3 actions for the planeswalker
                val activateActions =
                    actions.actionsList
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

                // Activate actions should reference ability ids that appear in
                // cardData.abilityIds. With the fixture path, slot ordering follows
                // the client's `Cards.AbilityIds` column directly (no synthetic
                // keyword/activated bucketing).
                val cardAbilityIds = cardData.abilityIds.map { it.first }.toSet()
                grpIds.forEach { cardAbilityIds shouldContain it }
            }
    })
