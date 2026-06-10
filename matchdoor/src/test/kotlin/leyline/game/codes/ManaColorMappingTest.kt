package leyline.game.codes

import forge.card.mana.ManaCost
import forge.card.mana.ManaCostShard
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import wotc.mtgo.gre.external.messaging.Messages.ManaColor

class ManaColorMappingTest :
    FunSpec({
        tags(UnitTag)

        test("maps Forge snow mana shard") {
            assertSoftly {
                ManaColorMapping.fromShard(ManaCostShard.S) shouldBe ManaColor.Snow_afc9
                ManaColorMapping.deriveManaCost(ManaCost("S S")) shouldBe listOf(ManaColor.Snow_afc9 to 2)
            }
        }
    })
