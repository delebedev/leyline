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
 * ## Intended layering (bottom → top)
 *
 * ```
 * Tier 0 — leaves (no leyline deps):
 *   config              match configuration (TOML data classes)
 *   frontdoor.domain    FD value types (Deck, Player, Format)
 *   arena               CLI automation tools
 *
 * Tier 0.5 — shared converters:
 *   bridge              Forge adapter primitives + DeckConverter → frontdoor.domain
 *
 * Tier 1 — domain core:
 *   game                engine wrappers, state mapping  → bridge, config
 *   game.mapper         pure projections                → game
 *
 * Tier 2 — format / persistence:
 *   protocol            wire framing, protobuf codecs   → game
 *   recording           session codecs                  → game
 *   frontdoor.repo      FD persistence (Exposed)        → frontdoor.domain
 *   frontdoor.wire      FD serialization                → frontdoor.domain, protocol
 *   frontdoor.service   FD use cases                    → frontdoor.domain, frontdoor.repo
 *
 * Tier 3 — analysis:
 *   analysis            post-game reports               → recording, game
 *
 * Tier 4 — diagnostics:
 *   debug               live diagnostics                → game, protocol, recording, analysis
 *
 * Tier 5 — orchestration:
 *   frontdoor           FD handler                      → frontdoor.*, protocol, debug
 *   conformance         comparison                      → recording
 *   match               match orchestration             → bridge, game, protocol, debug, infra
 *   cli                 CLI tools                       → frontdoor.domain, frontdoor.repo, game
 *
 * Tier 6 — top:
 *   infra               Netty server, bootstrap         → everything
 * ```
 *
 * Rules marked `xtest` represent intended design with known violations (flagged
 * as TODOs). Enable them as the violations are fixed.
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

        // ── Tier 0: leaves ──────────────────────────────────────────

        test("config is leaf") {
            noClasses().that().resideInAPackage("leyline.config..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                    "leyline.game..",
                    "leyline.protocol..",
                    "leyline.recording..",
                    "leyline.debug..",
                    "leyline.conformance..",
                    "leyline.analysis..",
                    "leyline.frontdoor..",
                    "leyline.match..",
                    "leyline.infra..",
                    "leyline.cli..",
                    "leyline.bridge..",
                    "leyline.arena..",
                ).check(classes)
        }

        test("bridge depends only on frontdoor.domain") {
            noClasses().that().resideInAPackage("leyline.bridge..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                    "leyline.config..",
                    "leyline.game..",
                    "leyline.protocol..",
                    "leyline.recording..",
                    "leyline.debug..",
                    "leyline.conformance..",
                    "leyline.analysis..",
                    "leyline.frontdoor.repo..",
                    "leyline.frontdoor.wire..",
                    "leyline.frontdoor.service..",
                    "leyline.match..",
                    "leyline.infra..",
                    "leyline.cli..",
                    "leyline.arena..",
                ).check(classes)
        }

        test("frontdoor.domain is leaf") {
            noClasses().that().resideInAPackage("leyline.frontdoor.domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                    "leyline.config..",
                    "leyline.game..",
                    "leyline.protocol..",
                    "leyline.recording..",
                    "leyline.debug..",
                    "leyline.conformance..",
                    "leyline.analysis..",
                    "leyline.frontdoor.repo..",
                    "leyline.frontdoor.wire..",
                    "leyline.frontdoor.service..",
                    "leyline.match..",
                    "leyline.infra..",
                    "leyline.cli..",
                    "leyline.bridge..",
                    "leyline.arena..",
                ).check(classes)
        }

        // ── Tier 1: domain core ─────────────────────────────────────

        test("game does not import upward (infra, match, debug, frontdoor, cli)") {
            noClasses().that().resideInAPackage("leyline.game..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                    "leyline.infra..",
                    "leyline.match..",
                    "leyline.debug..",
                    "leyline.frontdoor..",
                    "leyline.cli..",
                    "leyline.conformance..",
                    "leyline.analysis..",
                    "leyline.recording..",
                    "leyline.protocol..",
                ).check(classes)
        }

        test("mapper does not import outside game+bridge") {
            noClasses().that().resideInAPackage("leyline.game.mapper..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                    "leyline.config..",
                    "leyline.infra..",
                    "leyline.match..",
                    "leyline.debug..",
                    "leyline.conformance..",
                    "leyline.analysis..",
                    "leyline.recording..",
                    "leyline.protocol..",
                    "leyline.frontdoor..",
                    "leyline.cli..",
                ).check(classes)
        }

        // ── Tier 2: format / persistence ────────────────────────────

        test("protocol does not import upward") {
            noClasses().that().resideInAPackage("leyline.protocol..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                    "leyline.infra..",
                    "leyline.match..",
                    "leyline.debug..",
                    "leyline.conformance..",
                    "leyline.analysis..",
                    "leyline.frontdoor..",
                    "leyline.cli..",
                ).check(classes)
        }

        test("recording does not import upward") {
            noClasses().that().resideInAPackage("leyline.recording..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                    "leyline.infra..",
                    "leyline.match..",
                    "leyline.debug..",
                    "leyline.conformance..",
                    "leyline.analysis..",
                    "leyline.frontdoor..",
                    "leyline.cli..",
                ).check(classes)
        }

        test("frontdoor.repo depends only on frontdoor.domain") {
            noClasses().that().resideInAPackage("leyline.frontdoor.repo..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                    "leyline.config..",
                    "leyline.game..",
                    "leyline.protocol..",
                    "leyline.recording..",
                    "leyline.debug..",
                    "leyline.conformance..",
                    "leyline.analysis..",
                    "leyline.frontdoor.wire..",
                    "leyline.frontdoor.service..",
                    "leyline.match..",
                    "leyline.infra..",
                    "leyline.cli..",
                    "leyline.bridge..",
                ).check(classes)
        }

        test("frontdoor.service depends only on frontdoor.domain, repo, and bridge") {
            noClasses().that().resideInAPackage("leyline.frontdoor.service..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                    "leyline.config..",
                    "leyline.game..",
                    "leyline.protocol..",
                    "leyline.recording..",
                    "leyline.debug..",
                    "leyline.conformance..",
                    "leyline.analysis..",
                    "leyline.frontdoor.wire..",
                    "leyline.match..",
                    "leyline.infra..",
                    "leyline.cli..",
                ).check(classes)
        }

        // TODO: frontdoor.wire → debug.FdDebugCollector — wire layer shouldn't know about debug
        xtest("frontdoor.wire depends only on frontdoor.domain and protocol") {
            noClasses().that().resideInAPackage("leyline.frontdoor.wire..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                    "leyline.game..",
                    "leyline.recording..",
                    "leyline.conformance..",
                    "leyline.analysis..",
                    "leyline.debug..",
                    "leyline.frontdoor.service..",
                    "leyline.match..",
                    "leyline.infra..",
                    "leyline.cli..",
                    "leyline.bridge..",
                ).check(classes)
        }

        // ── Tier 3–4: analysis, debug ───────────────────────────────

        test("analysis does not import upward") {
            noClasses().that().resideInAPackage("leyline.analysis..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                    "leyline.infra..",
                    "leyline.match..",
                    "leyline.debug..",
                    "leyline.conformance..",
                    "leyline.frontdoor..",
                    "leyline.cli..",
                ).check(classes)
        }

        // TODO: debug → match.MatchSession — upward dep, debug should not know about match
        xtest("debug does not import upward (match, frontdoor, infra, cli)") {
            noClasses().that().resideInAPackage("leyline.debug..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                    "leyline.infra..",
                    "leyline.match..",
                    "leyline.frontdoor..",
                    "leyline.cli..",
                    "leyline.conformance..",
                ).check(classes)
        }

        // ── Tier 5: bounded-context boundaries ──────────────────────

        test("match does not import frontdoor services — BC boundary") {
            noClasses().that().resideInAPackage("leyline.match..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                    "leyline.frontdoor.repo..",
                    "leyline.frontdoor.wire..",
                    "leyline.frontdoor.service..",
                    "leyline.cli..",
                    "leyline.conformance..",
                    "leyline.analysis..",
                ).check(classes)
        }

        test("conformance does not import upward") {
            noClasses().that().resideInAPackage("leyline.conformance..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                    "leyline.infra..",
                    "leyline.match..",
                    "leyline.frontdoor..",
                    "leyline.cli..",
                ).check(classes)
        }

        // ── Cycles ──────────────────────────────────────────────────

        // TODO: debug↔match cycle (DebugServer imports MatchSession, MatchSession imports SessionRecorder)
        xtest("no package cycles") {
            slices().matching("leyline.(*)..")
                .should().beFreeOfCycles()
                .check(classes)
        }
    })
