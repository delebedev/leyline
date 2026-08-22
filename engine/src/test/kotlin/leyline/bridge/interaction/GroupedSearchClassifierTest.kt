package leyline.bridge.interaction

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import leyline.UnitTag

class GroupedSearchClassifierTest :
    FunSpec({
        tags(UnitTag)
        val shape =
            GroupedSearchClassifier.Shape("Library", "Hand", "1", "Instant,Card.hasKeywordFlash")

        test("partitions the grounded union in declared quality order") {
            GroupedSearchClassifier.classify(
                true,
                shape,
                listOf(
                    GroupedSearchClassifier.Candidate(isInstant = false, hasFlash = true),
                    GroupedSearchClassifier.Candidate(isInstant = true, hasFlash = false),
                ),
            ) shouldContainExactly listOf(listOf(1), listOf(0))
        }

        test("ordinary and unsupported searches remain flat") {
            GroupedSearchClassifier
                .classify(
                    true,
                    shape.copy(changeType = "Instant"),
                    listOf(GroupedSearchClassifier.Candidate(true, false)),
                ).shouldBeNull()
            GroupedSearchClassifier
                .classify(
                    false,
                    shape,
                    listOf(GroupedSearchClassifier.Candidate(true, false)),
                ).shouldBeNull()
        }

        test("refuses overlap and candidates outside the grounded union") {
            shouldThrow<IllegalStateException> {
                GroupedSearchClassifier.classify(
                    true,
                    shape,
                    listOf(
                        GroupedSearchClassifier.Candidate(true, true),
                        GroupedSearchClassifier.Candidate(false, true),
                    ),
                )
            }
            shouldThrow<IllegalStateException> {
                GroupedSearchClassifier.classify(
                    true,
                    shape,
                    listOf(
                        GroupedSearchClassifier.Candidate(true, false),
                        GroupedSearchClassifier.Candidate(false, false),
                    ),
                )
            }
        }
    })
