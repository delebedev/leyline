package leyline.architecture

import io.kotest.core.spec.style.FunSpec
import leyline.UnitTag
import java.nio.file.Files
import java.nio.file.Path

class PromptProjectionBoundaryTest :
    FunSpec({
        tags(UnitTag)

        test("state projection compute reaches prompt facts but not prompt journals or writes") {
            val sourceRoot =
                sequenceOf(
                    Path.of("src/main/kotlin"),
                    Path.of("engine/src/main/kotlin"),
                ).first { it.resolve("leyline").toFile().isDirectory }
            val computeFiles =
                listOf(
                    "leyline/game/mapping/StateMapper.kt",
                    "leyline/game/annotations/AnnotationPipeline.kt",
                    "leyline/game/annotations/AnnotationContext.kt",
                    "leyline/game/annotations/ConvokeContributor.kt",
                    "leyline/game/annotations/RevealStateContributor.kt",
                    "leyline/game/annotations/TargetSpecContributor.kt",
                    "leyline/game/mapping/PersistentFeedBuilder.kt",
                )
            val forbidden =
                listOf(
                    ".promptBridge(",
                    "PromptJournal",
                    ".drainChoiceResults(",
                    ".clearConvokePayments(",
                    ".clearCollectEvidenceCost(",
                    ".evictAbilityRegistry(",
                    ".opponentKnowledge.update(",
                )

            computeFiles.forEach { relative ->
                val source = Files.readString(sourceRoot.resolve(relative))
                check(forbidden.none(source::contains)) { "$relative reaches a prompt journal or shared projection write" }
            }
        }
    })
