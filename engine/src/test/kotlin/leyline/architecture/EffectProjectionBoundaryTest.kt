package leyline.architecture

import io.kotest.core.spec.style.FunSpec
import leyline.UnitTag
import java.nio.file.Files
import java.nio.file.Path

class EffectProjectionBoundaryTest :
    FunSpec({
        tags(UnitTag)

        test("synthetic-effect projection reads facts instead of scoped Forge APIs") {
            val sourceRoot =
                sequenceOf(
                    Path.of("src/main/kotlin"),
                    Path.of("engine/src/main/kotlin"),
                ).first { it.resolve("leyline").toFile().isDirectory }
            val scopedProjectionFiles =
                listOf(
                    "leyline/game/mapping/StateMapper.kt",
                    "leyline/game/annotations/VehicleAttachContributor.kt",
                    "leyline/game/annotations/AnnotationEmitters.kt",
                )
            val forbidden =
                listOf(
                    "snapshotBoosts(",
                    "snapshotKeywords(",
                    "snapshotCrewState(",
                    "snapshotSaddleState(",
                    "snapshotReconfigureState(",
                    "pendingEarthbendResolutions(",
                    "drainEarthbendFrame(",
                    "materializeEffectProjectionFacts(",
                )

            scopedProjectionFiles.forEach { relative ->
                val source = Files.readString(sourceRoot.resolve(relative))
                check(forbidden.none(source::contains)) {
                    "$relative reaches a live synthetic-effect observation API"
                }
            }
        }

        test("effect projection facts contain no Forge model or bridge types") {
            val sourceRoot =
                sequenceOf(
                    Path.of("src/main/kotlin"),
                    Path.of("engine/src/main/kotlin"),
                ).first { it.resolve("leyline").toFile().isDirectory }
            val source = Files.readString(sourceRoot.resolve("leyline/game/state/EffectProjectionFacts.kt"))
            val forbidden =
                Regex(
                    "\\b(Card|Player|Game|SpellAbility|GameBridge|Callback|Allocator|Mutable(Collection|List|Map|Set))\\b|forge\\.game",
                )
            check(!forbidden.containsMatchIn(source)) {
                "EffectProjectionFacts must remain a value-only boundary"
            }
        }
    })
