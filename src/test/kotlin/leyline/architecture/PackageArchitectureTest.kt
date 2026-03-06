package leyline.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import io.kotest.core.spec.style.FunSpec
import leyline.UnitTag
import java.nio.file.Path

/**
 * Enforces the leyline root module package dependency graph (main sources only).
 *
 * The `:frontdoor`, `:account`, and `:matchdoor` modules are separate Gradle
 * modules — their boundaries are enforced by the build system (stronger than
 * ArchUnit). This test covers packages that remain in the root `:app` module.
 *
 * ## Root module packages
 *
 * ```
 * Tier 1 — framing:
 *   protocol            wire framing (FrameCodec)        → no leyline deps
 *
 * Tier 2 — recording:
 *   recording           session codecs                   → protocol
 *
 * Tier 3 — analysis:
 *   analysis            post-game reports                → recording
 *
 * Tier 4 — diagnostics:
 *   debug               live diagnostics                 → analysis, recording, protocol
 *
 * Tier 5 — top:
 *   infra               Netty server, bootstrap          → everything
 *   cli                 CLI tools
 *
 * ─── Separate Gradle modules ───
 *   :matchdoor   bridge, game, match, config, HandshakeMessages, ProtoDump
 *   :frontdoor   FD protocol, lobby, decks, events, matchmaking
 *   :account     Ktor HTTPS, WAS compat, JWT, registration
 * ```
 */
class PackageArchitectureTest :
    FunSpec({

        tags(UnitTag)

        val buildDir = Path.of("").toAbsolutePath().resolve("build/classes")
        val classes = ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPaths(
                buildDir.resolve("kotlin/main"),
                buildDir.resolve("java/main"),
            )

        // ── Tier 1: framing ─────────────────────────────────────────

        test("protocol (root) is leaf") {
            noClasses().that().resideInAPackage("leyline.protocol..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                    "leyline.infra..",
                    "leyline.debug..",
                    "leyline.conformance..",
                    "leyline.analysis..",
                    "leyline.recording..",
                    "leyline.cli..",
                ).check(classes)
        }

        // ── Tier 2: recording ───────────────────────────────────────

        test("recording does not import upward") {
            noClasses().that().resideInAPackage("leyline.recording..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                    "leyline.infra..",
                    "leyline.debug..",
                    "leyline.conformance..",
                    "leyline.analysis..",
                    "leyline.cli..",
                ).check(classes)
        }

        // ── Tier 3: analysis ────────────────────────────────────────

        test("analysis does not import upward") {
            noClasses().that().resideInAPackage("leyline.analysis..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                    "leyline.infra..",
                    "leyline.debug..",
                    "leyline.conformance..",
                    "leyline.cli..",
                ).check(classes)
        }

        // ── Tier 4: debug ───────────────────────────────────────────

        test("debug does not import cli or conformance") {
            noClasses().that().resideInAPackage("leyline.debug..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                    "leyline.cli..",
                    "leyline.conformance..",
                ).check(classes)
        }

        test("conformance does not import upward") {
            noClasses().that().resideInAPackage("leyline.conformance..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                    "leyline.infra..",
                    "leyline.cli..",
                ).check(classes)
        }
    })
