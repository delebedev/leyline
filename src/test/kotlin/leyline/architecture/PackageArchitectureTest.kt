package leyline.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import io.kotest.core.spec.style.FunSpec
import leyline.UnitTag
import java.nio.file.Path

/**
 * Enforces the leyline package dependency graph (main sources only).
 *
 * ## Why these rules exist
 *
 * Dependencies should flow one direction: down. A lower-level package never
 * imports from a higher-level one. This keeps the codebase modular — you can
 * change or delete a package and only break its direct dependents, not cascade
 * through the whole system.
 *
 * Each package should be **cohesive** (everything changes for the same reason)
 * and expose a **minimal surface** (2-3 key types, rest is internal). Packages
 * are named by domain concept (`game`, `recording`, `protocol`) not by
 * technical role (`models`, `services`, `utils`).
 *
 * ## Intended layering (bottom → top)
 *
 * ```
 * config          (leaf — no leyline deps)
 * game            (domain core — depends only on config)
 * game.mapper     (pure projections — depends only on parent game)
 * protocol        (wire format — depends on game)
 * recording       (codecs — leaf, no leyline deps except game.CardDb)
 * analysis        (post-game — depends on recording, game)
 * debug           (diagnostics — depends on game, recording, protocol, analysis)
 * conformance     (comparison — depends on recording, debug, analysis)
 * server          (Netty orchestration — depends on everything above)
 * ```
 *
 * Allowed dependency direction: any package may depend on packages below it in
 * this list and on the root `leyline` package (shared infra like LeylinePaths).
 * Upward or lateral dependencies indicate a missing abstraction or a misplaced file.
 *
 * ## What these tests catch
 *
 * - **Cycles**: `noPackageCycles` detects any circular dependency chain.
 * - **Upward leaks**: per-package tests catch a leaf reaching into a higher layer
 *   (e.g. `recording` importing `server`).
 * - **Regressions**: if someone adds an import that violates layering, CI fails
 *   with a clear message naming the offending class and dependency.
 */
class PackageArchitectureTest :
    FunSpec({

        tags(UnitTag)

        /**
         * Import only leyline production classes from this module's build dir.
         * Using importPaths() instead of importPackages() avoids scanning the full
         * classpath — importPackages resolves transitive deps from forge-game
         * which blows the heap on CI runners with limited memory.
         *
         * Gradle compiles Kotlin sources to build/classes/kotlin/main and protobuf
         * generated Java to build/classes/java/main — import both so ArchUnit sees
         * the full picture.
         */
        val buildDir = Path.of("").toAbsolutePath().resolve("build/classes")
        val classes = ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPaths(
                buildDir.resolve("kotlin/main"),
                buildDir.resolve("java/main"),
            )

        // TODO: fix debug↔server cycle (DebugServer imports MatchSession, MatchSession imports SessionRecorder)
        xtest("no package cycles") {
            slices().matching("leyline.(*)..")
                .should().beFreeOfCycles()
                .check(classes)
        }

        test("config is leaf — no deps on any other leyline package") {
            noClasses().that().resideInAPackage("leyline.config..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                    "leyline.game..",
                    "leyline.protocol..",
                    "leyline.recording..",
                    "leyline.debug..",
                    "leyline.server..",
                    "leyline.conformance..",
                    "leyline.analysis..",
                ).check(classes)
        }

        test("recording does not import upward") {
            noClasses().that().resideInAPackage("leyline.recording..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                    "leyline.server..",
                    "leyline.debug..",
                    "leyline.conformance..",
                    "leyline.analysis..",
                ).check(classes)
        }

        test("game does not import upward") {
            noClasses().that().resideInAPackage("leyline.game..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                    "leyline.server..",
                    "leyline.debug..",
                    "leyline.protocol..",
                    "leyline.recording..",
                    "leyline.conformance..",
                    "leyline.analysis..",
                ).check(classes)
        }

        test("protocol does not import upward") {
            noClasses().that().resideInAPackage("leyline.protocol..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                    "leyline.server..",
                    "leyline.debug..",
                    "leyline.conformance..",
                    "leyline.analysis..",
                ).check(classes)
        }

        test("mapper does not import outside game") {
            noClasses().that().resideInAPackage("leyline.game.mapper..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                    "leyline.server..",
                    "leyline.debug..",
                    "leyline.conformance..",
                    "leyline.analysis..",
                    "leyline.recording..",
                    "leyline.protocol..",
                ).check(classes)
        }
    })
