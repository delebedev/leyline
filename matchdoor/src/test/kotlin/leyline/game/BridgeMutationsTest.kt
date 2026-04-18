package leyline.game

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag

class BridgeMutationsTest :
    FunSpec({

        tags(UnitTag)

        test("EMPTY smoke — zero-element collections + counter defaults") {
            val m = BridgeMutations.EMPTY
            assertSoftly {
                m.idReallocations.size shouldBe 0
                m.retiredIds.size shouldBe 0
                m.zoneRecordings.size shouldBe 0
                m.persistentBatch.allAnnotations.size shouldBe 0
                m.nextAnnotationId shouldBe 50
            }
        }
    })
