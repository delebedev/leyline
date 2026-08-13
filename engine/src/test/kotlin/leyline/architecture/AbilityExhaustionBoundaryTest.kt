package leyline.architecture

import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.game.mapping.StateFrameInput
import leyline.game.state.AbilityExhaustionFacts
import java.nio.file.Files
import java.nio.file.Path

class AbilityExhaustionBoundaryTest :
    FunSpec({
        tags(UnitTag)

        val cwd = Path.of("").toAbsolutePath()
        val buildDir =
            sequenceOf(cwd.resolve("build/classes"), cwd.resolve("engine/build/classes"))
                .first { it.resolve("kotlin/main/leyline").toFile().isDirectory }
        val classes =
            ClassFileImporter()
                .withImportOption(ImportOption.DoNotIncludeTests())
                .importPaths(buildDir.resolve("kotlin/main"), buildDir.resolve("java/main"))
        val scopedClasses =
            "(" +
                listOf(
                    "leyline.game.state.AbilityExhaustionFacts",
                    "leyline.game.annotations.AbilityExhaustionProjectionKt",
                ).joinToString("|") { Regex.escape(it) } +
                ")(\\$.*)?"
        val sourceRoot =
            sequenceOf(cwd.resolve("src/main/kotlin"), cwd.resolve("engine/src/main/kotlin"))
                .first { it.resolve("leyline").toFile().isDirectory }

        test("ability exhaustion values and projection depend only on value types") {
            noClasses()
                .that()
                .haveNameMatching(scopedClasses)
                .should()
                .dependOnClassesThat()
                .resideInAPackage("forge..")
                .because("ability exhaustion projection consumes immutable final rows")
                .check(classes)

            noClasses()
                .that()
                .haveNameMatching(scopedClasses)
                .should()
                .dependOnClassesThat()
                .haveNameMatching("leyline\\.game\\.state\\.GameBridge(\\$.*)?")
                .because("the live bridge remains in the shell capture adapter")
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

        test("state frame carries the ability exhaustion value") {
            StateFrameInput::class.java.getDeclaredField("abilityExhaustionFacts").type shouldBe
                AbilityExhaustionFacts::class.java
        }

        test("ability exhaustion reduction cannot reopen live state") {
            val scopedFiles =
                listOf(
                    "leyline/game/annotations/AbilityExhaustionProjection.kt",
                    "leyline/game/annotations/AnnotationPipeline.kt",
                    "leyline/game/mapping/StateMapper.kt",
                )
            val forbidden =
                listOf(
                    "StaticAbilityAdditionalActivations",
                    "getNonManaActivatedAbilities",
                    "abilityRegistryFor(",
                    "activationsThisTurn",
                    "activationsThisGame",
                    "isBoast",
                    "isExhaust",
                )

            scopedFiles
                .flatMap { relative ->
                    val source = Files.readString(sourceRoot.resolve(relative))
                    forbidden.filter(source::contains).map { "$relative: $it" }
                }.shouldBeEmpty()
        }

        test("only production projection shells materialize ability exhaustion facts") {
            val appSourceRoot =
                sequenceOf(cwd.resolve("app/main/kotlin"), cwd.resolve("../app/main/kotlin").normalize())
                    .first { it.resolve("leyline").toFile().isDirectory }
            val productionRoots =
                listOf(
                    "engine" to sourceRoot,
                    "app" to appSourceRoot,
                )
            val callers =
                productionRoots
                    .flatMap { (rootName, root) ->
                        Files.walk(root).use { paths ->
                            paths
                                .filter { it.toString().endsWith(".kt") }
                                .filter { Files.readString(it).contains("AbilityExhaustionFactsCapture.capture(") }
                                .map { "$rootName/${root.relativize(it)}" }
                                .toList()
                        }
                    }.sorted()
            val rawStateMapperCallers =
                productionRoots
                    .flatMap { (rootName, root) ->
                        Files.walk(root).use { paths ->
                            paths
                                .filter { it.toString().endsWith(".kt") }
                                .filter { file ->
                                    val source = Files.readString(file)
                                    source.contains("StateMapper.buildDiff(") ||
                                        Regex("StateMapper\\s*\\n\\s*\\.buildFromSnapshot\\(").containsMatchIn(source)
                                }.map { "$rootName/${root.relativize(it)}" }
                                .toList()
                        }
                    }.sorted()

            callers shouldBe
                listOf(
                    "app/leyline/debug/DebugServer.kt",
                    "engine/leyline/game/bundle/BundleBuilder.kt",
                    "engine/leyline/protocol/HandshakeMessages.kt",
                )
            rawStateMapperCallers shouldBe
                listOf(
                    "app/leyline/debug/DebugServer.kt",
                    "engine/leyline/game/bundle/BundleBuilder.kt",
                    "engine/leyline/protocol/HandshakeMessages.kt",
                )
        }
    })
