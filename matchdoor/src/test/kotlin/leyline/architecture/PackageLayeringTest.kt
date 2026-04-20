package leyline.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import io.kotest.core.spec.style.FunSpec
import leyline.UnitTag
import java.nio.file.Path

/**
 * Enforces internal package layering within the :matchdoor module.
 *
 * Cross-module boundaries (matchdoor vs frontdoor vs tooling) are enforced
 * by Gradle — build fails on illegal imports. These rules enforce the
 * internal tier structure that Gradle can't see.
 *
 * Rules match on prefix (`leyline.bridge..`) and apply to every sub-package
 * beneath. See `matchdoor/CLAUDE.md` for the full sub-package tree.
 *
 * ```
 * Tier 0 — foundation (leaves, import nothing from matchdoor):
 *   bridge         Forge adapter (forge/, handoff/, coord/, bootstrap/, types/)
 *   config         MatchConfig TOML data class
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
 * - bridge is a leaf: the Forge adapter layer has no upward deps
 * - game doesn't know about match sessions or wire protocol
 * - match is the top: nothing else imports it
 */
class PackageLayeringTest :
    FunSpec({

        tags(UnitTag)

        // Locate matchdoor build output — try submodule-relative first, then project-root-relative
        val cwd = Path.of("").toAbsolutePath()
        val buildDir =
            sequenceOf(
                cwd.resolve("build/classes"),
                cwd.resolve("matchdoor/build/classes"),
            ).first { it.resolve("kotlin/main/leyline").toFile().isDirectory }

        val classes =
            ClassFileImporter()
                .withImportOption(ImportOption.DoNotIncludeTests())
                .importPaths(
                    buildDir.resolve("kotlin/main"),
                    buildDir.resolve("java/main"),
                )

        // ── Tier 0: bridge is a pure leaf ───────────────────────────

        test("bridge does not depend on game, match, or protocol") {
            noClasses()
                .that()
                .resideInAPackage("leyline.bridge..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                    "leyline.game..",
                    "leyline.match..",
                    "leyline.protocol..",
                    "leyline.infra..",
                    "leyline.config..",
                ).check(classes)
        }

        // ── Tier 0: config is a pure leaf ───────────────────────────

        test("config does not depend on any matchdoor package") {
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
        // matchdoor reorg. Each rule holds today; a failure here means
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
    })
