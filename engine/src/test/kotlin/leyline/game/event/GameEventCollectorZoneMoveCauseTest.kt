package leyline.game.event

import forge.game.ability.ApiType
import forge.game.event.GameEventZoneChangeCause
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag

class GameEventCollectorZoneMoveCauseTest :
    FunSpec({
        tags(UnitTag)
        val owner = GameEventZoneChangeCause(100, 501, 501, ApiType.Draw, false, 501)
        test("only matching resolving effects inherit stack ownership") {
            val child = GameEventZoneChangeCause(100, 502, 500, ApiType.DigUntil, false, 502)
            val other = GameEventZoneChangeCause(101, 503, 503, ApiType.Draw, false, 503)
            val cost = GameEventZoneChangeCause(100, 504, 504, ApiType.Draw, true, 504)
            assertSoftly {
                zoneMoveCause(null, owner, true, false)?.stackAbilityForgeId shouldBe 501
                zoneMoveCause(null, owner, false, false) shouldBe null
                zoneMoveCause(child, owner, true, false) shouldBe
                    ZoneMoveCause(
                        leyline.bridge.types.ForgeCardId(100),
                        502,
                        500,
                        "DigUntil",
                        false,
                        501,
                    )
                zoneMoveCause(child, owner, false, false)?.stackAbilityForgeId shouldBe 502
                zoneMoveCause(other, owner, true, false)?.stackAbilityForgeId shouldBe 503
                zoneMoveCause(cost, owner, true, false)?.stackAbilityForgeId shouldBe 504
                zoneMoveCause(child, owner, true, true)?.stackAbilityForgeId shouldBe 502
            }
        }
    })
