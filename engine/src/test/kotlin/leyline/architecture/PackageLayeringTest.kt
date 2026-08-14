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

/**
 * Enforces internal package layering within the :engine module.
 *
 * Cross-module boundaries (engine vs native vs tooling) are enforced
 * by Gradle — build fails on illegal imports. These rules enforce the
 * internal tier structure that Gradle can't see.
 *
 * Rules match on prefix (`leyline.bridge..`) and apply to every sub-package
 * beneath. See `engine/AGENTS.md` for the full sub-package tree.
 *
 * ```
 * Tier 0 — foundation (leaves, import nothing from engine):
 *   bridge         Forge adapter (forge/, handoff/, bootstrap/, types/)
 *   config         MatchConfig TOML data class
 *
 * Imperative shell:
 *   bridge/coord   match runtime orchestration; may depend on game + config
 *
 * Tier 1 — game engine (imports Tier 0):
 *   game           Snapshot → proto (snapshot/, state/, event/, mapper/,
 *                  annotations/, bundle/, data/, codes/, generator/)
 *
 * Tier 2 — wire protocol (imports Tier 0 + 1):
 *   protocol       HandshakeMessages, FrameCodec, ProtoDump
 *   infra          MessageSink (wire output)
 *
 * Tier 3 — session orchestration (imports everything):
 *   match          MatchSession, MatchHandler, combat/targeting/mulligan handlers
 * ```
 *
 * Key invariants:
 * - bridge adapters are leaves; bridge/coord is the explicit imperative shell
 * - game doesn't know about match sessions or wire protocol
 * - match is the top: nothing else imports it
 */
class PackageLayeringTest :
    FunSpec({

        tags(UnitTag)

        // Locate engine build output — try submodule-relative first, then project-root-relative
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

        // ── Tier 0: bridge is a pure leaf ───────────────────────────

        test("bridge adapters do not depend on game, match, or protocol") {
            noClasses()
                .that(
                    resideInAPackage("leyline.bridge..").and(
                        resideOutsideOfPackage("leyline.bridge.coord.."),
                    ),
                ).should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                    "leyline.game..",
                    "leyline.match..",
                    "leyline.protocol..",
                    "leyline.infra..",
                    "leyline.config..",
                ).check(classes)
        }

        test("bridge coordinator shell does not depend on sessions or wire delivery") {
            noClasses()
                .that()
                .resideInAPackage("leyline.bridge.coord..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                    "leyline.match..",
                    "leyline.protocol..",
                    "leyline.infra..",
                ).check(classes)
        }

        // ── Tier 0: config is a pure leaf ───────────────────────────

        test("config does not depend on any engine package") {
            noClasses()
                .that()
                .resideInAPackage("leyline.config..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                    "leyline.bridge..",
                    "leyline.game..",
                    "leyline.match..",
                    "leyline.protocol..",
                    "leyline.infra..",
                ).check(classes)
        }

        // ── Tier 1: game layer doesn't reach up ────────────────────

        test("game does not depend on match, protocol, or infra") {
            noClasses()
                .that()
                .resideInAPackage("leyline.game..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                    "leyline.match..",
                    "leyline.protocol..",
                    "leyline.infra..",
                ).check(classes)
        }

        // ── Tier 2: protocol doesn't reach up to match ─────────────

        test("protocol does not depend on match") {
            noClasses()
                .that()
                .resideInAPackage("leyline.protocol..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("leyline.match..")
                .check(classes)
        }

        // ── Tier 2: infra doesn't reach up to match ────────────────

        test("infra does not depend on match or game") {
            noClasses()
                .that()
                .resideInAPackage("leyline.infra..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                    "leyline.match..",
                    "leyline.game..",
                    "leyline.bridge..",
                    "leyline.config..",
                ).check(classes)
        }

        // ── Sub-package invariants ─────────────────────────────────
        //
        // Rules below lock in the sub-package boundaries agreed in the
        // engine reorg. Each rule holds today; a failure here means
        // a new import crossed a boundary that was meant to be one-way.

        test("bridge/types is a pure-data leaf within bridge") {
            noClasses()
                .that()
                .resideInAPackage("leyline.bridge.types..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                    "leyline.bridge.bootstrap..",
                    "leyline.bridge.coord..",
                    "leyline.bridge.forge..",
                    "leyline.bridge.handoff..",
                ).check(classes)
        }

        test("game/codes is a pure-data leaf within game") {
            noClasses()
                .that()
                .resideInAPackage("leyline.game.codes..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                    "leyline.game.annotations..",
                    "leyline.game.bundle..",
                    "leyline.game.data..",
                    "leyline.game.event..",
                    "leyline.game.generator..",
                    "leyline.game.mapping..",
                    "leyline.game.snapshot..",
                    "leyline.game.state..",
                ).check(classes)
        }

        test("game/data depends on nothing else in game except codes") {
            noClasses()
                .that()
                .resideInAPackage("leyline.game.data..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                    "leyline.game.annotations..",
                    "leyline.game.bundle..",
                    "leyline.game.event..",
                    "leyline.game.generator..",
                    "leyline.game.mapping..",
                    "leyline.game.snapshot..",
                    "leyline.game.state..",
                ).check(classes)
        }

        test("bridge adapter imports from game stay explicitly allowlisted") {
            val allowed =
                setOf(
                    "import leyline.game.data.KeywordAbilityIds",
                    "import leyline.game.mapping.PromptIds",
                )
            val bridgeRoot = sourceRoot.resolve("leyline/bridge")
            val coordinatorShellRoot = bridgeRoot.resolve("coord")
            val violations = mutableListOf<String>()
            val stream = Files.walk(bridgeRoot)
            try {
                stream
                    .filter {
                        Files.isRegularFile(it) &&
                            it.toString().endsWith(".kt") &&
                            !it.startsWith(coordinatorShellRoot)
                    }.forEach { file ->
                        Files.readAllLines(file).forEachIndexed { index, line ->
                            val trimmed = line.trim()
                            if (trimmed.startsWith("import leyline.game.") && trimmed !in allowed) {
                                violations += "${sourceRoot.relativize(file)}:${index + 1}: $trimmed"
                            }
                        }
                    }
            } finally {
                stream.close()
            }

            // If this fails, don't reflexively add an exception. First decide
            // whether the source file is genuinely a bridge boundary exception
            // or whether the package structure should change.
            check(violations.isEmpty()) {
                "Unexpected bridge -> game imports:\n" + violations.joinToString("\n")
            }
        }
    })
