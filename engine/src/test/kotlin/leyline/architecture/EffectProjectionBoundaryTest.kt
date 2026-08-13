package leyline.architecture

import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import io.kotest.core.spec.style.FunSpec
import leyline.UnitTag
import java.nio.file.Files
import java.nio.file.Path

class EffectProjectionBoundaryTest :
    FunSpec({
        tags(UnitTag)

        val cwd = Path.of("").toAbsolutePath()
        val buildDir =
            sequenceOf(
                cwd.resolve("build/classes"),
                cwd.resolve("engine/build/classes"),
            ).first { it.resolve("kotlin/main/leyline").toFile().isDirectory }
        val classes =
            ClassFileImporter()
                .withImportOption(ImportOption.DoNotIncludeTests())
                .importPaths(
                    buildDir.resolve("kotlin/main"),
                    buildDir.resolve("java/main"),
                )
        val scopedComputeClasses =
            "(" +
                listOf(
                    "leyline.game.state.EffectProjectionFacts",
                    "leyline.game.state.EffectTracker",
                    "leyline.game.annotations.MechanicAnnotations",
                    "leyline.game.state.EarthbendTracker",
                    "leyline.game.annotations.VehicleAttachContributor",
                    "leyline.game.annotations.EarthbendEmitter",
                ).joinToString("|") { Regex.escape(it) } +
                ")(\\$.*)?"

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

        test("scoped effect compute depends on values instead of Forge or GameBridge") {
            noClasses()
                .that()
                .haveNameMatching(scopedComputeClasses)
                .should()
                .dependOnClassesThat()
                .resideInAPackage("forge..")
                .because("scoped effect compute consumes materialized value facts")
                .check(classes)

            noClasses()
                .that()
                .haveNameMatching(scopedComputeClasses)
                .should()
                .dependOnClassesThat()
                .haveNameMatching("leyline\\.game\\.state\\.GameBridge(\\$.*)?")
                .because("GameBridge remains in the shell that materializes effect facts")
                .check(classes)

            noClasses()
                .that()
                .haveNameMatching(scopedComputeClasses)
                .should()
                .dependOnClassesThat(
                    resideInAPackage("leyline.bridge..").and(
                        resideOutsideOfPackage("leyline.bridge.types.."),
                    ),
                ).because("only value identifiers may cross from the bridge package")
                .check(classes)
        }

        test("effect attribution adapters do not reopen live state") {
            val sourceRoot =
                sequenceOf(
                    Path.of("src/main/kotlin"),
                    Path.of("engine/src/main/kotlin"),
                ).first { it.resolve("leyline").toFile().isDirectory }
            val stateMapper = Files.readString(sourceRoot.resolve("leyline/game/mapping/StateMapper.kt"))
            val effectAdapters =
                stateMapper.substring(
                    stateMapper.indexOf("    private fun boostEntries("),
                    stateMapper.indexOf("    private fun stackTransferDeletedIds("),
                )
            val annotationPipeline = Files.readString(sourceRoot.resolve("leyline/game/annotations/AnnotationPipeline.kt"))
            val effectReduction =
                annotationPipeline.substring(
                    annotationPipeline.indexOf("        val keywordAffectorFallbackForgeCardId ="),
                    annotationPipeline.indexOf("        annotations.addAll(effectTransient)") +
                        "        annotations.addAll(effectTransient)".length,
                )
            val forbiddenLiveApis =
                listOf(
                    "SourceAbilityResolverFactory",
                    "sourceAbilityResolver",
                    "keywordAffectorResolver",
                    "getGame(",
                    "findCard(",
                    "abilityRegistryFor(",
                    "staticAbilities",
                    "getZone(",
                )

            check(forbiddenLiveApis.none(effectAdapters::contains)) {
                "StateMapper effect adapters reopen live projection state"
            }
            check(forbiddenLiveApis.none(effectReduction::contains)) {
                "AnnotationPipeline effect reduction reopens live projection state"
            }
            check(!Files.exists(sourceRoot.resolve("leyline/game/mapping/SourceAbilityResolverFactory.kt"))) {
                "live source-ability resolver factory must stay deleted"
            }
        }
    })
