package leyline.game.state

import forge.game.ability.AbilityUtils
import forge.game.ability.ApiType
import forge.game.keyword.Keyword
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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

        test("a delayed trigger's row from its spawning ability is counted at its own site") {
            val (bridge, game, _) =
                startWithBoard { _, human, _ ->
                    val source = addCard("Ba Sing Se", human, ZoneType.Battlefield)
                    val forest = addCard("Forest", human, ZoneType.Battlefield)
                    val earthbend = source.spellAbilities.single { it.api == ApiType.Earthbend }
                    earthbend.activatingPlayer = human
                    earthbend.targets.add(forest)
                    AbilityUtils.resolve(earthbend)
                }
            val trigger = game.triggerHandler.delayedTriggersSnapshot.first { it.spawningAbility?.api == ApiType.Earthbend }
            val identities = bridge.abilityIdentities

            fun counted() = identities.ledger.resolvedBySource()["spawning-trigger/spawning-ability"] ?: 0
            val before = counted()

            val row = identities.spawningTriggerAbilityGrpId(trigger)

            assertSoftly {
                row shouldNotBe null
                counted() - before shouldBe 1
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

        test("a prompt answers with the mode chosen for its own invocation only") {
            val (bridge, _, _) = startWithBoard { _, _, _ -> }
            val ninja = TestCardInjector.inject(bridge, 1, "Ninja of the Deep Hours", ZoneType.Hand).card
            val activated = ninja.spellAbilities.single { it.isActivatedAbility && !it.isManaAbility() }
            val identities = bridge.abilityIdentities
            identities.recordSelectedModalAbilityGrpId(activated.id, MODAL_ROW)

            val lookup = identities.lookupPrompt(ninja, activated)

            assertSoftly {
                (lookup as AbilityLookup.Resolved).via shouldBe AbilityIdentitySource.ModalChoice
                lookup.value.abilityGrpId shouldBe MODAL_ROW
                identities.ledger.resolvedBySource()["prompt/modal-choice"] shouldBe 1
                identities.lookupPrompt(ninja, ninja.firstSpellAbility).valueOrNull()?.abilityGrpId shouldNotBe MODAL_ROW
            }
        }

        test("a chosen mode stays until its root ability has been on the stack and left it") {
            val (bridge, _, _) = startWithBoard { _, _, _ -> }
            val ninja = TestCardInjector.inject(bridge, 1, "Ninja of the Deep Hours", ZoneType.Hand).card
            val activated = ninja.spellAbilities.single { it.isActivatedAbility && !it.isManaAbility() }
            val identities = bridge.abilityIdentities

            fun mode() = identities.lookupPrompt(ninja, activated).valueOrNull()?.abilityGrpId
            identities.recordSelectedModalAbilityGrpId(activated.id, MODAL_ROW)

            identities.retainPendingTriggerAbilityIdentities(emptySet(), emptySet())
            val beforeStack = mode()
            identities.retainPendingTriggerAbilityIdentities(emptySet(), setOf(activated.id))
            val onStack = mode()
            identities.retainPendingTriggerAbilityIdentities(emptySet(), emptySet())

            assertSoftly {
                beforeStack shouldBe MODAL_ROW
                onStack shouldBe MODAL_ROW
                mode() shouldNotBe MODAL_ROW
            }
        }
    }) {
    private companion object {
        const val MODAL_ROW = 424_242
    }
}
