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
import leyline.game.state.MechanicSourceFacts
import java.nio.file.Files
import java.nio.file.Path

class MechanicSourceBoundaryTest :
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
                    "leyline.game.state.MechanicSourceFacts",
                    "leyline.game.annotations.MechanicSourceProjection",
                ).joinToString("|") { Regex.escape(it) } +
                ")(\\$.*)?"

        test("mechanic source values and reducer depend only on value types") {
            noClasses()
                .that()
                .haveNameMatching(scopedClasses)
                .should()
                .dependOnClassesThat()
                .resideInAPackage("forge..")
                .because("mechanic source reduction consumes immutable cut facts")
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

        test("state frame carries the mechanic source value") {
            StateFrameInput::class.java.getDeclaredField("mechanicSourceFacts").type shouldBe MechanicSourceFacts::class.java
        }

        test("mechanic annotation reduction cannot reopen live source state") {
            val sourceRoot =
                sequenceOf(cwd.resolve("src/main/kotlin"), cwd.resolve("engine/src/main/kotlin"))
                    .first { it.resolve("leyline").toFile().isDirectory }
            val scopedFiles =
                listOf(
                    "leyline/game/annotations/AnnotationContext.kt",
                    "leyline/game/annotations/AnnotationPipeline.kt",
                    "leyline/game/annotations/MechanicSourceProjection.kt",
                )
            val forbidden =
                listOf(
                    "bridge.getGame(",
                    "bridge.findCard(",
                    "bridge.seatOf(",
                    "BasicLandAbilities",
                    "tokenSpawningAbility",
                    "currentSourceZoneId",
                    "tokenAffectorFromTokenState",
                )

            scopedFiles
                .flatMap { relative ->
                    val source = Files.readString(sourceRoot.resolve(relative))
                    forbidden.filter(source::contains).map { "$relative: $it" }
                }.shouldBeEmpty()
        }

        test("only production projection shells materialize mechanic source facts") {
            val sourceRoot =
                sequenceOf(cwd.resolve("src/main/kotlin"), cwd.resolve("engine/src/main/kotlin"))
                    .first { it.resolve("leyline").toFile().isDirectory }
            val callers =
                Files.walk(sourceRoot).use { paths ->
                    paths
                        .filter { it.toString().endsWith(".kt") }
                        .filter { Files.readString(it).contains("MechanicSourceFactsCapture.capture(") }
                        .map { sourceRoot.relativize(it).toString() }
                        .toList()
                        .sorted()
                }

            callers shouldBe
                listOf(
                    "leyline/game/bundle/BundleBuilder.kt",
                    "leyline/protocol/HandshakeMessages.kt",
                )
        }
    })
