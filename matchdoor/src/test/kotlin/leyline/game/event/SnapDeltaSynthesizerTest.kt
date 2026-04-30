package leyline.game.event

import forge.card.CardStateName
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.snapshot.CardSnapshot
import leyline.game.snapshot.GsmSnapshot

private fun creatureSnap(
    id: Int,
    power: Int?,
    toughness: Int?,
    backside: Boolean = false,
): CardSnapshot =
    CardSnapshot(
        forgeCardId = ForgeCardId(id),
        name = "C$id",
        grpId = 100 + id,
        owner = SeatId(1),
        controller = SeatId(1),
        netPower = power,
        netToughness = toughness,
        currentStateNameIsBackside = backside,
    )

private fun snapWith(vararg cards: CardSnapshot): GsmSnapshot = GsmSnapshot.forTest(objects = cards.associateBy { it.forgeCardId })

class SnapDeltaSynthesizerTest :
    FunSpec({

        tags(UnitTag)

        // -- PowerToughnessChanged --

        test("emits PowerToughnessChanged when P or T differs") {
            val prev = snapWith(creatureSnap(1, 2, 2))
            val cur = snapWith(creatureSnap(1, 4, 4))

            val events = SnapDeltaSynthesizer.synthesize(prev, cur)
            val pt = events.filterIsInstance<GameEvent.PowerToughnessChanged>()
            assertSoftly {
                pt shouldHaveSize 1
                pt[0].cardId shouldBe ForgeCardId(1)
                pt[0].oldPower shouldBe 2
                pt[0].newPower shouldBe 4
                pt[0].oldToughness shouldBe 2
                pt[0].newToughness shouldBe 4
            }
        }

        test("no PowerToughnessChanged when stats unchanged") {
            val prev = snapWith(creatureSnap(1, 2, 2))
            val cur = snapWith(creatureSnap(1, 2, 2))

            SnapDeltaSynthesizer
                .synthesize(prev, cur)
                .filterIsInstance<GameEvent.PowerToughnessChanged>()
                .shouldBeEmpty()
        }

        test("no PowerToughnessChanged when card was new in cur") {
            val prev = snapWith()
            val cur = snapWith(creatureSnap(1, 2, 2))

            SnapDeltaSynthesizer
                .synthesize(prev, cur)
                .filterIsInstance<GameEvent.PowerToughnessChanged>()
                .shouldBeEmpty()
        }

        test("no PowerToughnessChanged when one side has null P/T") {
            // Animate-creature transition: prev is non-creature (null P/T), cur is creature.
            val prev = snapWith(creatureSnap(1, null, null))
            val cur = snapWith(creatureSnap(1, 2, 2))

            SnapDeltaSynthesizer
                .synthesize(prev, cur)
                .filterIsInstance<GameEvent.PowerToughnessChanged>()
                .shouldBeEmpty()
        }

        // -- CardTransformed --

        test("emits CardTransformed when card flips to backside") {
            val prev = snapWith(creatureSnap(1, 2, 2, backside = false))
            val cur = snapWith(creatureSnap(1, 2, 2, backside = true))

            val events = SnapDeltaSynthesizer.synthesize(prev, cur)
            val transformed = events.filterIsInstance<GameEvent.CardTransformed>()
            assertSoftly {
                transformed shouldHaveSize 1
                transformed[0].cardId shouldBe ForgeCardId(1)
                transformed[0].newStateName shouldBe CardStateName.Backside
                transformed[0].isBackSide shouldBe true
            }
        }

        test("emits CardTransformed when card flips back to original") {
            val prev = snapWith(creatureSnap(1, 2, 2, backside = true))
            val cur = snapWith(creatureSnap(1, 2, 2, backside = false))

            val events = SnapDeltaSynthesizer.synthesize(prev, cur)
            val transformed = events.filterIsInstance<GameEvent.CardTransformed>()
            assertSoftly {
                transformed shouldHaveSize 1
                transformed[0].newStateName shouldBe CardStateName.Original
                transformed[0].isBackSide shouldBe false
            }
        }

        test("no CardTransformed when backside flag unchanged") {
            val prev = snapWith(creatureSnap(1, 2, 2, backside = false))
            val cur = snapWith(creatureSnap(1, 4, 4, backside = false))

            SnapDeltaSynthesizer
                .synthesize(prev, cur)
                .filterIsInstance<GameEvent.CardTransformed>()
                .shouldBeEmpty()
        }

        // -- Re-entry / lifetime boundaries --

        test("re-entered card with matching forgeCardId still diffs against prev") {
            // If forgeCardId stays the same across BF→GY→BF (rare; usually reallocates),
            // the synthesizer would diff. Forge generally reallocates ids on re-entry,
            // which means cur.objects has a fresh forgeCardId that prev does not — no
            // synthesis. This test pins the conservative behavior: same id, same P/T
            // both BF, no event.
            val prev = snapWith(creatureSnap(1, 2, 2))
            val cur = snapWith(creatureSnap(1, 2, 2))

            SnapDeltaSynthesizer.synthesize(prev, cur).shouldBeEmpty()
        }

        test("disjoint object sets produce no events") {
            // prev has card 1; cur has card 2 (id reallocation simulation). Neither
            // appears on both sides, so synthesis is empty.
            val prev = snapWith(creatureSnap(1, 2, 2))
            val cur = snapWith(creatureSnap(2, 5, 5))

            SnapDeltaSynthesizer.synthesize(prev, cur).shouldBeEmpty()
        }

        // -- Combined --

        test("emits both events when stats and backside both change") {
            val prev = snapWith(creatureSnap(1, 2, 2, backside = false))
            val cur = snapWith(creatureSnap(1, 5, 5, backside = true))

            val events = SnapDeltaSynthesizer.synthesize(prev, cur)
            assertSoftly {
                events.filterIsInstance<GameEvent.PowerToughnessChanged>() shouldHaveSize 1
                events.filterIsInstance<GameEvent.CardTransformed>() shouldHaveSize 1
            }
        }
    })
