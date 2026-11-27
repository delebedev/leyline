package leyline.game.state

import forge.game.keyword.Keyword
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import leyline.bridge.types.AbilityDefinitionRef
import leyline.bridge.types.AbilityIdentitySource
import leyline.bridge.types.AbilityLookup
import leyline.bridge.types.AbilityLookupMiss
import leyline.testkit.BoardTest
import leyline.testkit.TestCardInjector

class AbilityIdentitiesTest :
    BoardTest({

        test("each lookup names the source that answered and the ledger counts it by site") {
            val (bridge, _, _) = startWithBoard { _, _, _ -> }
            val ninja = TestCardInjector.inject(bridge, 1, "Ninja of the Deep Hours", ZoneType.Hand).card
            val bondwarden = TestCardInjector.inject(bridge, 1, "Enduring Bondwarden", ZoneType.Battlefield).card
            val activated = ninja.spellAbilities.single { it.isActivatedAbility && !it.isManaAbility() }
            val backup =
                bondwarden
                    .getKeywords()
                    .first { it.keyword == Keyword.BACKUP }
                    .triggers
                    .single()
            val identities = bridge.abilityIdentities

            val activatedLookup = identities.lookup(ninja, activated)
            val definitionLookup = identities.lookup(bondwarden, AbilityDefinitionRef.Trigger(backup.definitionId))

            assertSoftly {
                (activatedLookup as AbilityLookup.Resolved).via shouldBe AbilityIdentitySource.Registry
                (definitionLookup as AbilityLookup.Resolved).via shouldBe AbilityIdentitySource.Registry
                identities.ledger.resolvedBySource() shouldContainKey "spell-ability/registry"
                identities.ledger.resolvedBySource() shouldContainKey "definition/registry"
            }
        }

        test("a definition the source card does not carry is a counted miss") {
            val (bridge, _, _) = startWithBoard { _, _, _ -> }
            val pacifism = TestCardInjector.inject(bridge, 1, "Pacifism", ZoneType.Battlefield).card
            val identities = bridge.abilityIdentities

            val lookup = identities.lookup(pacifism, AbilityDefinitionRef.Trigger(Int.MAX_VALUE))

            assertSoftly {
                lookup shouldBe AbilityLookup.Missing(AbilityLookupMiss.NoRow)
                identities.ledger.missedByReason()["definition/no-row"] shouldBe 1
            }
        }

        test("a prompt answers with the modal choice recorded for its source") {
            val (bridge, _, _) = startWithBoard { _, _, _ -> }
            val ninja = TestCardInjector.inject(bridge, 1, "Ninja of the Deep Hours", ZoneType.Hand).card
            val activated = ninja.spellAbilities.single { it.isActivatedAbility && !it.isManaAbility() }
            val identities = bridge.abilityIdentities
            identities.recordSelectedModalAbilityGrpId(leyline.bridge.types.ForgeCardId(ninja.id), MODAL_ROW)

            val lookup = identities.lookupPrompt(ninja, activated)

            assertSoftly {
                (lookup as AbilityLookup.Resolved).via shouldBe AbilityIdentitySource.ModalChoice
                lookup.value.abilityGrpId shouldBe MODAL_ROW
                identities.ledger.resolvedBySource()["prompt/modal-choice"] shouldBe 1
            }
        }
    }) {
    private companion object {
        const val MODAL_ROW = 424_242
    }
}
