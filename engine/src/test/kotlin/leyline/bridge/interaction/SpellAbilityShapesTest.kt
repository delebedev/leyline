package leyline.bridge.interaction

import forge.game.ability.ApiType
import forge.game.card.Card
import forge.game.spellability.AbilitySub
import forge.game.spellability.AlternativeCost
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.bootstrap.GameBootstrap

class SpellAbilityShapesTest :
    FunSpec({
        tags(UnitTag)
        beforeSpec { GameBootstrap.initializeCardDatabase(quiet = true) }

        test("paid cost alternatives do not grant a casting permission") {
            for (cost in listOf(AlternativeCost.Evoke, AlternativeCost.Warp, AlternativeCost.Foretold)) {
                val ability = AbilitySub(ApiType.Draw, Card(7, null), null, emptyMap())
                ability.setAlternativeCost(cost)
                SpellAbilityShapes.usesCostOnlyCastingOption(ability) shouldBe true
            }
        }

        test("permission alternatives and ordinary casting keep the permission route") {
            SpellAbilityShapes.usesCostOnlyCastingOption(null) shouldBe false
            for (cost in listOf(null, AlternativeCost.Madness, AlternativeCost.Flashback)) {
                val ability = AbilitySub(ApiType.Draw, Card(7, null), null, emptyMap())
                ability.setAlternativeCost(cost)
                SpellAbilityShapes.usesCostOnlyCastingOption(ability) shouldBe false
            }
        }
    })
