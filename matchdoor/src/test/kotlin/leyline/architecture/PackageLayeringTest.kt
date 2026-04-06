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
 * ```
 * Tier 0 — foundation (leaves, import nothing from matchdoor):
 *   bridge         Forge adapter (WebPlayerController, cost decisions, bootstrap)
 *   config         MatchConfig TOML data class
 *   conformance    StructuralFingerprint, StructuralDiff (proto shape analysis)
 *
 * Tier 1 — game engine (imports Tier 0):
 *   game           StateMapper, BundleBuilder, annotations, events, card data
 *   game.mapper    ActionMapper, ZoneMapper, ObjectMapper (bidirectional with game)
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
        val buildDir = sequenceOf(
            cwd.resolve("build/classes"),
            cwd.resolve("matchdoor/build/classes"),
        ).first { it.resolve("kotlin/main/leyline").toFile().isDirectory }

        val classes = ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPaths(
                buildDir.resolve("kotlin/main"),
                buildDir.resolve("java/main"),
            )

        // ── Tier 0: bridge is a pure leaf ───────────────────────────

        test("bridge does not depend on game, match, or protocol") {
            noClasses().that().resideInAPackage("leyline.bridge..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                    "leyline.game..",
                    "leyline.match..",
                    "leyline.protocol..",
                    "leyline.infra..",
                    "leyline.config..",
                    "leyline.conformance..",
                ).check(classes)
        }

        // ── Tier 0: config is a pure leaf ───────────────────────────

        test("config does not depend on any matchdoor package") {
            noClasses().that().resideInAPackage("leyline.config..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                    "leyline.bridge..",
                    "leyline.game..",
                    "leyline.match..",
                    "leyline.protocol..",
                    "leyline.infra..",
                    "leyline.conformance..",
                ).check(classes)
        }

        // ── Tier 0: conformance is a pure leaf ─────────────────────

        test("conformance does not depend on any matchdoor package") {
            noClasses().that().resideInAPackage("leyline.conformance..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                    "leyline.bridge..",
                    "leyline.game..",
                    "leyline.match..",
                    "leyline.protocol..",
                    "leyline.infra..",
                    "leyline.config..",
                ).check(classes)
        }

        // ── Tier 1: game layer doesn't reach up ────────────────────

        test("game does not depend on match, protocol, or infra") {
            noClasses().that().resideInAPackage("leyline.game..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                    "leyline.match..",
                    "leyline.protocol..",
                    "leyline.infra..",
                    "leyline.conformance..",
                ).check(classes)
        }

        // ── Tier 2: protocol doesn't reach up to match ─────────────

        test("protocol does not depend on match") {
            noClasses().that().resideInAPackage("leyline.protocol..")
                .should().dependOnClassesThat()
                .resideInAPackage("leyline.match..")
                .check(classes)
        }

        // ── Tier 2: infra doesn't reach up to match ────────────────

        test("infra does not depend on match or game") {
            noClasses().that().resideInAPackage("leyline.infra..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                    "leyline.match..",
                    "leyline.game..",
                    "leyline.bridge..",
                    "leyline.config..",
                    "leyline.conformance..",
                ).check(classes)
        }
    })
