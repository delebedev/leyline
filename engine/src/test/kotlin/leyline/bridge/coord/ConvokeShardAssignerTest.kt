package leyline.bridge.coord

import forge.card.ColorSet
import forge.card.MagicColor
import forge.card.mana.ManaCostShard
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag

class ConvokeShardAssignerTest :
    FunSpec({
        tags(UnitTag)

        test("assigns multicolor source to keep constrained source payable") {
            val azorius = "azorius" to ColorSet.fromMask(MagicColor.WHITE.toInt() or MagicColor.BLUE.toInt())
            val white = "white" to ColorSet.fromMask(MagicColor.WHITE.toInt())

            val assignments =
                ConvokeShardAssigner.assign(
                    sources = listOf(azorius, white),
                    costCounts = mapOf(ManaCostShard.WHITE to 1, ManaCostShard.BLUE to 1),
                ) { it.second }

            assignments shouldBe listOf(azorius to ManaCostShard.BLUE, white to ManaCostShard.WHITE)
        }

        test("prefers colored pips before generic when either assignment pays one") {
            val white = "white" to ColorSet.fromMask(MagicColor.WHITE.toInt())

            val assignments =
                ConvokeShardAssigner.assign(
                    sources = listOf(white),
                    costCounts = mapOf(ManaCostShard.WHITE to 1, ManaCostShard.GENERIC to 1),
                ) { it.second }

            assignments shouldBe listOf(white to ManaCostShard.WHITE)
        }
    })
