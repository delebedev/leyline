package leyline.game.mapping

import forge.game.ability.AbilityFactory
import forge.game.ability.AbilityKey
import forge.game.ability.AbilityUtils
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import leyline.bridge.coord.hasAmbiguousActionCatalog
import leyline.bridge.handoff.PlayerAction
import leyline.bridge.types.ForgeCardId
import leyline.game.snapshot.SnapshotCapture
import leyline.testkit.BoardTest
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.ManaColor

class ActivatedActionEmitterTest :
    BoardTest({
        val colors = setOf(ManaColor.White_afc9, ManaColor.Blue_afc9, ManaColor.Black_afc9, ManaColor.Red_afc9, ManaColor.Green_afc9)
        for ((name, expected) in listOf(
            "Utopia Mycon" to colors,
            "Birds of Paradise" to colors,
            "Lotus Petal" to colors,
            "Elves of Deep Shadow" to setOf(ManaColor.Black_afc9),
            "Wastes" to setOf(ManaColor.Colorless_afc9),
            "Noble Hierarch" to setOf(ManaColor.White_afc9, ManaColor.Blue_afc9, ManaColor.Green_afc9),
        )) {
            test("$name publishes its produced colors without generic or extra choices") {
                val board = startWithBoard { _, player, _ -> addCard(name, player, ZoneType.Battlefield) }
                val source = board.human.battlefield.card(name)
                source.manaAbilities.flatMap { ActivatedActionEmitter.producedManaColors(it) }.toSet() shouldBe expected
            }
        }

        test("resolution-granted mana ability keeps an identity distinct from the printed basic ability") {
            var landForgeId = 0
            val (b, game, _) =
                startWithBoard { game, human, _ ->
                    val donor = addCard("Topsoil Turner", human, ZoneType.Battlefield)
                    val hand = addCard("Snow-Covered Forest", human, ZoneType.Hand)
                    val effect = AbilityFactory.getAbility(donor.getSVar("TrigAnimateAll"), donor)
                    effect.activatingPlayer = human
                    AbilityUtils.resolve(effect)
                    landForgeId = game.action.moveToPlay(hand, effect, AbilityKey.newMap()).id
                }

            val projection = ActionMapper.buildProjectionFromSnapshot(1, SnapshotCapture.run(game, b, "test", 0), b)
            val landInstanceId = b.getOrAllocInstanceId(ForgeCardId(landForgeId)).value
            val offers =
                projection.offers.filter {
                    it.action.actionType == ActionType.ActivateMana && it.action.instanceId == landInstanceId
                }
            val byAmount =
                offers.associateBy {
                    it.command
                        .shouldBeInstanceOf<PlayerAction.ActivateMana>()
                        .ability
                        ?.getParamOrDefault("Amount", "1")
                }
            val printed = checkNotNull(byAmount["1"]).action
            val granted = checkNotNull(byAmount["2"]).action

            assertSoftly {
                offers.size shouldBe 2
                hasAmbiguousActionCatalog(projection.offers) shouldBe false
                printed.abilityGrpId shouldBe 1005
                granted.abilityGrpId shouldNotBe 0
                granted.abilityGrpId shouldNotBe printed.abilityGrpId
                granted.uniqueAbilityId shouldNotBe printed.uniqueAbilityId
            }
        }
    })
