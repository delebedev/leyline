package leyline.architecture

import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import leyline.UnitTag
import leyline.game.data.CardProtoBuilder
import leyline.game.event.GameEvent
import leyline.game.mapping.MatchProjectionConfig
import leyline.game.mapping.StateProjectionEnvironment
import java.nio.file.Path

class StateZoneProjectionBoundaryTest :
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
                .importPaths(buildDir.resolve("kotlin/main"), buildDir.resolve("java/main"))
        val scopedClasses =
            "(" +
                listOf(
                    "leyline.game.mapping.StateZoneProjection",
                    "leyline.game.mapping.StateProjectionEnvironment",
                    "leyline.game.mapping.MatchProjectionConfig",
                    "leyline.game.snapshot.CardSnapshot",
                    "leyline.game.snapshot.StackEntry",
                    "leyline.game.annotations.ZoneTransferDetector",
                    "leyline.game.annotations.ZoneTransferContext",
                    "leyline.game.annotations.CombatAnnotations",
                ).joinToString("|") { Regex.escape(it) } +
                ")(\\$.*)?"

        test("state-zone semantic projection depends on values instead of Forge or GameBridge") {
            noClasses()
                .that()
                .haveNameMatching(scopedClasses)
                .should()
                .dependOnClassesThat()
                .resideInAPackage("forge..")
                .because("state-zone semantics consume immutable snapshot facts")
                .check(classes)

            noClasses()
                .that()
                .haveNameMatching(scopedClasses)
                .should()
                .dependOnClassesThat()
                .haveNameMatching("leyline\\.game\\.state\\.GameBridge(\\$.*)?")
                .because("GameBridge remains in the shell adapter")
                .check(classes)

            noClasses()
                .that()
                .haveNameMatching(scopedClasses)
                .should()
                .dependOnClassesThat(
                    resideInAPackage("leyline.bridge..").and(
                        resideOutsideOfPackage("leyline.bridge.types.."),
                    ),
                ).because("only value identifiers may cross from the bridge package")
                .check(classes)
        }

        test("state projection environment contains only stable reference data and match config") {
            StateProjectionEnvironment::class.java.declaredFields
                .filterNot { it.isSynthetic }
                .associate { it.name to it.type } shouldBe
                mapOf(
                    "cardProto" to CardProtoBuilder::class.java,
                    "matchConfig" to MatchProjectionConfig::class.java,
                )
        }

        test("Paradigm event facts carry value source identity") {
            GameEvent.SpellCast::class.java.getDeclaredField("paradigmSourceCardId").type shouldBe
                leyline.bridge.types.ForgeCardId::class.java
            GameEvent.SpellResolved::class.java.getDeclaredField("paradigmSourceCardId").type shouldBe
                leyline.bridge.types.ForgeCardId::class.java
        }

        test("production state-zone adapters do not query live engine semantics") {
            val sourceRoot =
                sequenceOf(cwd.resolve("src/main/kotlin"), cwd.resolve("engine/src/main/kotlin"))
                    .first { it.toFile().isDirectory }
            val sourceFiles =
                listOf(
                    "leyline/game/mapping/StateMapper.kt",
                    "leyline/game/mapping/ZoneMapper.kt",
                    "leyline/game/annotations/ZoneTransferAdapter.kt",
                )
            val prohibitedCalls =
                listOf(
                    "bridge.getGame(",
                    "bridge.getPlayer(",
                    "bridge.findCard(",
                    "bridge.seatOf(",
                    "bridge.isBrawlOrCommander",
                    "bridge.abilityRegistryFor(",
                    "bridge.paradigmSourceStackIidFor(",
                )

            sourceFiles
                .flatMap { relative ->
                    val source = sourceRoot.resolve(relative).toFile().readText()
                    prohibitedCalls.filter(source::contains).map { "$relative: $it" }
                }.shouldBeEmpty()

            val triggerLifecycleSource =
                sourceRoot.resolve("leyline/game/annotations/AnnotationPipeline.kt").toFile().readText()
            triggerLifecycleSource shouldNotContain "bridge.paradigmSourceStackIidFor("
        }
    })
