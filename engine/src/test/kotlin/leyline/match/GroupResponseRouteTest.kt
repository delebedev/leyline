package leyline.match

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.MulliganPhase

class GroupResponseRouteTest :
    FunSpec({
        tags(UnitTag)

        test("published in-game Grouping takes precedence over London state") {
            groupResponseRoute(true, MulliganPhase.WaitingTuck) shouldBe GroupResponseRoute.Grouping
        }

        test("only an exact London tuck window routes to mulligan") {
            assertSoftly {
                groupResponseRoute(false, MulliganPhase.WaitingTuck) shouldBe GroupResponseRoute.LondonTuck
                groupResponseRoute(false, MulliganPhase.WaitingKeep) shouldBe GroupResponseRoute.Stale
                groupResponseRoute(false, null) shouldBe GroupResponseRoute.Stale
            }
        }
    })
