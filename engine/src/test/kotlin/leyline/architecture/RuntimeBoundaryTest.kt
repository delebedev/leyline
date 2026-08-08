package leyline.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import io.kotest.core.spec.style.FunSpec
import leyline.UnitTag
import java.nio.file.Files
import java.nio.file.Path

/**
 * Enforces the runtime boundary direction defined by ADR 0014.
 *
 * Match orchestration may depend on immutable engine-facing values, but new
 * direct Forge dependencies must stay behind the engine boundary. Match and
 * game packages also remain independent of protocol-head transports.
 */
class RuntimeBoundaryTest :
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

        val sourceRoot =
            sequenceOf(
                cwd.resolve("src/main/kotlin"),
                cwd.resolve("engine/src/main/kotlin"),
            ).first { it.resolve("leyline").toFile().isDirectory }

        test("match Forge imports stay within the frozen migration allowlist") {
            // This is an upper bound, not an expected-equality snapshot: existing
            // imports may disappear without editing the test, while any new
            // importing file fails. Remove names only after their imports are gone.
            val allowedViolators =
                setOf(
                    "AutoPassEngine.kt",
                    "SessionContext.kt",
                    "MatchSession.kt",
                    "CombatHandler.kt",
                    "DeferredCastCostInteractionHandler.kt",
                    "OptionalActionHandler.kt",
                    "PayCostsInteractionHandler.kt",
                    "PuzzleHandler.kt",
                )
            val matchRoot = sourceRoot.resolve("leyline/match")
            val violators = mutableSetOf<String>()
            val stream = Files.walk(matchRoot)
            try {
                stream
                    .filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                    .forEach { file ->
                        if (Files.readAllLines(file).any { it.trim().startsWith("import forge.") }) {
                            violators += matchRoot.relativize(file).toString()
                        }
                    }
            } finally {
                stream.close()
            }

            check(violators.all { it in allowedViolators }) {
                "Unexpected Forge imports in leyline.match: " +
                    (violators - allowedViolators).sorted().joinToString()
            }
        }

        test("match and game do not depend on transport implementations") {
            noClasses()
                .that()
                .resideInAnyPackage(
                    "leyline.match..",
                    "leyline.game..",
                ).should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                    "io.netty..",
                    "io.ktor..",
                    "java.nio.channels..",
                    "kotlinx.coroutines.channels..",
                    "..transport..",
                    "leyline.native..",
                    "leyline.web..",
                ).because(
                    "ADR 0014 keeps transport channels in protocol heads outside the match runtime",
                ).check(classes)
        }
    })
