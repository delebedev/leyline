package leyline.game.bundle

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import wotc.mtgo.gre.external.messaging.Messages.AllowFailToFind

/**
 * Verify SearchReq message shape with populated inner fields.
 */
class SearchReqTest :
    FunSpec({

        tags(UnitTag)

        test("buildSearchRequest populates inner SearchReq fields") {
            val request =
                RequestBuilder.buildSearchRequest(
                    sourceInstanceId = 290,
                    libraryZoneId = 32,
                    allLibraryIds = listOf(100, 101, 102),
                    validTargetIds = listOf(100, 102),
                    maxFind = 1,
                    allowFailToFind = true,
                )

            assertSoftly {
                request.maxFind shouldBe 1
                request.zonesToSearchList shouldContainExactly listOf(32)
                request.itemsToSearchList shouldContainExactly listOf(100, 101, 102)
                request.itemsSoughtList shouldContainExactly listOf(100, 102)
                request.sourceId shouldBe 290
                request.allowFailToFind shouldBe AllowFailToFind.Any
            }
        }
    })
