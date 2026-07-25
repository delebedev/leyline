package leyline.game.mapping

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import leyline.bridge.types.InstanceId
import leyline.game.annotations.AnnotationBuilder
import leyline.testkit.BoardTest

class AbilityWordFeedMergerTest :
    BoardTest({
        test("Opus rows aggregate by player while preserving live ability order") {
            val rows =
                listOf(301, 302).map { abilityIid ->
                    AnnotationBuilder.abilityWordActive(
                        instanceId = InstanceId(abilityIid),
                        abilityWordName = "Opus",
                        affectorId = InstanceId(1),
                    )
                }

            val marker = AbilityWordFeedMerger.merge(rows).single()

            assertSoftly {
                marker.affectorId shouldBe 1
                marker.affectedIdsList shouldBe listOf(301, 302)
            }
        }

        test("Void source and trigger ability rows aggregate without crossing controllers") {
            val rows =
                listOf(
                    AnnotationBuilder.abilityWordActive(
                        instanceId = InstanceId(101),
                        abilityWordName = "Void",
                        affectorId = InstanceId(1),
                        affectedIds = listOf(InstanceId(101), InstanceId(102)),
                    ),
                    AnnotationBuilder.abilityWordActive(
                        instanceId = InstanceId(301),
                        abilityWordName = "Void",
                        affectorId = InstanceId(1),
                    ),
                    AnnotationBuilder.abilityWordActive(
                        instanceId = InstanceId(201),
                        abilityWordName = "Void",
                        affectorId = InstanceId(2),
                    ),
                )

            val markers = AbilityWordFeedMerger.merge(rows)

            markers shouldHaveSize 2
            assertSoftly {
                markers[0].affectorId shouldBe 1
                markers[0].affectedIdsList shouldBe listOf(101, 102, 301)
                markers[1].affectorId shouldBe 2
                markers[1].affectedIdsList shouldBe listOf(201)
            }
        }
    })
