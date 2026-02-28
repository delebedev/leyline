package forge.nexus.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import org.testng.annotations.Test

/**
 * Enforces the forge-nexus package dependency graph (main sources only).
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
 * config          (leaf — no forge.nexus deps)
 * game            (domain core — depends only on config)
 * game.mapper     (pure projections — depends only on parent game)
 * protocol        (wire format — depends on game)
 * recording       (codecs — leaf, no forge.nexus deps except game.CardDb)
 * analysis        (post-game — depends on recording, game)
 * debug           (diagnostics — depends on game, recording, protocol, analysis)
 * conformance     (comparison — depends on recording, debug, analysis)
 * server          (Netty orchestration — depends on everything above)
 * ```
 *
 * Allowed dependency direction: any package may depend on packages below it in
 * this list and on the root `forge.nexus` package (shared infra like NexusPaths).
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
@Test(groups = ["unit"])
class PackageArchitectureTest {

    /**
     * Import only production classes — test classes legitimately cross package
     * boundaries (e.g. game tests using conformance.TestCardRegistry).
     */
    private val classes = ClassFileImporter()
        .withImportOption(ImportOption.DoNotIncludeTests())
        .importPackages("forge.nexus")

    /** No circular dependencies between top-level packages. */
    @Test
    fun noPackageCycles() {
        slices().matching("forge.nexus.(*)..")
            .should().beFreeOfCycles()
            .check(classes)
    }

    /** config is a leaf — no deps on any other forge.nexus package. */
    @Test
    fun configIsLeaf() {
        noClasses().that().resideInAPackage("forge.nexus.config..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                "forge.nexus.game..",
                "forge.nexus.protocol..",
                "forge.nexus.recording..",
                "forge.nexus.debug..",
                "forge.nexus.server..",
                "forge.nexus.conformance..",
                "forge.nexus.analysis..",
            ).check(classes)
    }

    /** recording never imports from server, debug, conformance, or analysis. */
    @Test
    fun recordingDoesNotImportUpward() {
        noClasses().that().resideInAPackage("forge.nexus.recording..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                "forge.nexus.server..",
                "forge.nexus.debug..",
                "forge.nexus.conformance..",
                "forge.nexus.analysis..",
            ).check(classes)
    }

    /** game (domain core) never imports from server, debug, protocol, recording, etc. */
    @Test
    fun gameDoesNotImportUpward() {
        noClasses().that().resideInAPackage("forge.nexus.game..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                "forge.nexus.server..",
                "forge.nexus.debug..",
                "forge.nexus.protocol..",
                "forge.nexus.recording..",
                "forge.nexus.conformance..",
                "forge.nexus.analysis..",
            ).check(classes)
    }

    /** protocol never imports from server, debug, conformance, or analysis. */
    @Test
    fun protocolDoesNotImportUpward() {
        noClasses().that().resideInAPackage("forge.nexus.protocol..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                "forge.nexus.server..",
                "forge.nexus.debug..",
                "forge.nexus.conformance..",
                "forge.nexus.analysis..",
            ).check(classes)
    }

    /** game.mapper subpackage is pure — no deps on server, debug, protocol, etc. */
    @Test
    fun mapperDoesNotImportOutsideGame() {
        noClasses().that().resideInAPackage("forge.nexus.game.mapper..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                "forge.nexus.server..",
                "forge.nexus.debug..",
                "forge.nexus.conformance..",
                "forge.nexus.analysis..",
                "forge.nexus.recording..",
                "forge.nexus.protocol..",
            ).check(classes)
    }
}
