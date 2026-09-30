package leyline.match

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag

class MatchReceiveDiagnosticTest :
    FunSpec({
        tags(UnitTag)

        test("nested waits restore the containing phase after failures") {
            val probe = MatchReceiveProbe()
            probe.observe {
                MatchReceiveProbe.inPhase(MatchReceivePhase.ActionProcessing) {
                    runCatching {
                        MatchReceiveProbe.inPhase(MatchReceivePhase.HorizonWait) {
                            probe.snapshot().phase shouldBe MatchReceivePhase.HorizonWait
                            error("failed")
                        }
                    }
                    probe.snapshot().phase shouldBe MatchReceivePhase.ActionProcessing
                }
            }
            probe.snapshot().phase shouldBe MatchReceivePhase.Idle
        }

        test("phase scope is removed after a receive so later unobserved work cannot alter its diagnostic") {
            val probe = MatchReceiveProbe()
            runCatching { probe.observe { error("failed") } }
            MatchReceiveProbe.inPhase(MatchReceivePhase.CoordinatorWait) {
                probe.snapshot().phase shouldBe MatchReceivePhase.Idle
            }
        }
    })
