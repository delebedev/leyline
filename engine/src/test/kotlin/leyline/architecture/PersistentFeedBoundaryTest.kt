package leyline.architecture

import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.game.mapping.StateFrameInput
import leyline.game.state.PersistentFeedFacts
import java.nio.file.Files
import java.nio.file.Path

class PersistentFeedBoundaryTest :
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
                    "leyline.game.mapping.PersistentFeedBuilder",
                    "leyline.game.mapping.PersistentAbilityWordFeedBuilder",
                    "leyline.game.mapping.PersistentTemporaryFeedBuilder",
                    "leyline.game.mapping.FrameIdResolver",
                    "leyline.game.state.PersistentFeedFacts",
                ).joinToString("|") { Regex.escape(it) } +
                ")(\\$.*)?"

        test("persistent-feed values and reduction do not depend on Forge or GameBridge") {
            noClasses()
                .that()
                .haveNameMatching(scopedClasses)
                .should()
                .dependOnClassesThat()
                .resideInAPackage("forge..")
                .check(classes)

            noClasses()
                .that()
                .haveNameMatching(scopedClasses)
                .should()
                .dependOnClassesThat()
                .haveNameMatching("leyline\\.game\\.state\\.GameBridge(\\$.*)?")
                .check(classes)

            noClasses()
                .that()
                .haveNameMatching("leyline\\.game\\.state\\.PersistentFeedFacts(\\$.*)?")
                .should()
                .dependOnClassesThat(
                    resideInAPackage("leyline.bridge..").and(
                        resideOutsideOfPackage("leyline.bridge.types.."),
                    ),
                ).check(classes)
        }

        test("state frame carries one persistent-feed fact value") {
            StateFrameInput::class.java.getDeclaredField("persistentFeedFacts").type shouldBe PersistentFeedFacts::class.java
        }

        test("shell materializers use the narrow stable-reference surface") {
            noClasses()
                .that()
                .haveNameMatching("leyline\\.game\\.bundle\\.(PersistentFeedFactsCapture|CombatQualificationFactsCapture)(\\$.*)?")
                .should()
                .dependOnClassesThat()
                .haveNameMatching("leyline\\.game\\.data\\.CardRepository(\\$.*)?")
                .check(classes)
        }

        test("only production and harness projection shells materialize persistent-feed facts") {
            val sourceRoot =
                sequenceOf(cwd.resolve("src/main/kotlin"), cwd.resolve("engine/src/main/kotlin"))
                    .first { it.resolve("leyline").toFile().isDirectory }
            val callers =
                Files
                    .walk(sourceRoot)
                    .use { paths ->
                        paths
                            .filter { it.toString().endsWith(".kt") }
                            .filter { Files.readString(it).contains("PersistentFeedFactsCapture.capture(") }
                            .map { sourceRoot.relativize(it).toString() }
                            .toList()
                    }.sorted()

            callers shouldBe
                listOf(
                    "leyline/game/bundle/BundleBuilder.kt",
                    "leyline/protocol/HandshakeMessages.kt",
                )

            val harnessRoot =
                sequenceOf(cwd.resolve("src/harness/kotlin"), cwd.resolve("engine/src/harness/kotlin"))
                    .first { it.resolve("leyline").toFile().isDirectory }
            val harnessCallers =
                Files
                    .walk(harnessRoot)
                    .use { paths ->
                        paths
                            .filter { it.toString().endsWith(".kt") }
                            .filter { Files.readString(it).contains("PersistentFeedFactsCapture.capture(") }
                            .map { harnessRoot.relativize(it).toString() }
                            .toList()
                    }.sorted()
            harnessCallers shouldBe
                listOf(
                    "leyline/game/TestHelpers.kt",
                    "leyline/tooling/headless/MatchFlowHarness.kt",
                )
        }
    })
