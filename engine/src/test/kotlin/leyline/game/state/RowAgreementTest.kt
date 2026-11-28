package leyline.game.state

import forge.game.ability.AbilityUtils
import forge.game.ability.ApiType
import forge.game.card.Card
import forge.game.spellability.SpellAbility
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import leyline.bridge.types.AbilityIdentitySource
import leyline.bridge.types.AbilityLookup
import leyline.testkit.BoardTest

class RowAgreementTest :
    BoardTest({

        /** Resolves Ba Sing Se's Earthbend on a Forest and returns Ba Sing Se with the delayed return trigger it registered. */
        fun earthbendReturnTrigger(): Triple<leyline.game.state.GameBridge, Card, SpellAbility> {
            lateinit var source: Card
            val (bridge, game, _) =
                startWithBoard { _, human, _ ->
                    source = addCard("Ba Sing Se", human, ZoneType.Battlefield)
                    val forest = addCard("Forest", human, ZoneType.Battlefield)
                    val earthbend = source.spellAbilities.single { it.api == ApiType.Earthbend }
                    earthbend.activatingPlayer = human
                    earthbend.targets.add(forest)
                    AbilityUtils.resolve(earthbend)
                }
            val trigger = game.triggerHandler.delayedTriggersSnapshot.first { it.spawningAbility?.api == ApiType.Earthbend }
            val ability = trigger.ensureAbility().also { it.trigger = trigger }
            return Triple(bridge, source, ability)
        }

        test("a delayed trigger answered from its spawning ability takes that ability's row without a mismatch") {
            val (bridge, source, ability) = earthbendReturnTrigger()
            val identities = bridge.abilityIdentities

            val lookup = identities.lookup(source, ability)

            assertSoftly {
                (lookup as AbilityLookup.Resolved).via shouldBe AbilityIdentitySource.PendingTrigger
                identities.ledger.rowMismatches() shouldBe emptyMap()
            }
        }

        test("the same trigger on the activated row fails the check when another route answered it") {
            val (bridge, source, ability) = earthbendReturnTrigger()
            val activatedRow =
                checkNotNull(bridge.abilityIdentities.lookup(source, ability).valueOrNull()).abilityGrpId
            val agreement = RowAgreement(bridge.cardRepository)

            assertSoftly {
                agreement.disagreement(ability, activatedRow, AbilityIdentitySource.SpawningAbility) shouldBe null
                agreement.disagreement(ability, activatedRow, AbilityIdentitySource.PendingTrigger) shouldBe null
                agreement.disagreement(ability, activatedRow, AbilityIdentitySource.Registry) shouldNotBe null
                agreement.disagreement(ability, activatedRow)!! shouldStartWith "trigger on activated ability row"
            }
        }
    })
