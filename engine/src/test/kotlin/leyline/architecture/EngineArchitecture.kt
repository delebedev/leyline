package leyline.architecture

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import java.nio.file.Path

/**
 * Shared class import for the specs in this package.
 *
 * Reading the engine's compiled output is the expensive part of an ArchUnit
 * rule, so every spec here shares one import instead of repeating the locate
 * and import dance. [mainClasses] holds production output only;
 * [productionAndHarnessClasses] adds the harness source set for rules that must
 * see harness call sites.
 */
internal object EngineArchitecture {
    private val cwd: Path = Path.of("").toAbsolutePath()

    /** Compiled output root, resolved from either the module or the repository directory. */
    private val buildDir: Path =
        sequenceOf(cwd.resolve("build/classes"), cwd.resolve("engine/build/classes"))
            .first { it.resolve("kotlin/main/leyline").toFile().isDirectory }

    /** Root of the engine's Kotlin production sources. */
    val sourceRoot: Path =
        sequenceOf(cwd.resolve("src/main/kotlin"), cwd.resolve("engine/src/main/kotlin"))
            .first { it.resolve("leyline").toFile().isDirectory }

    val mainClasses: JavaClasses by lazy {
        ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPaths(buildDir.resolve("kotlin/main"), buildDir.resolve("java/main"))
    }

    /**
     * Production output plus the harness source set. Call-ownership rules need
     * this so a harness call site counts as a violation rather than going unseen.
     */
    val productionAndHarnessClasses: JavaClasses by lazy {
        ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPaths(
                buildDir.resolve("kotlin/main"),
                buildDir.resolve("java/main"),
                buildDir.resolve("kotlin/harness"),
            )
    }

    /** Regex matching exactly [names] plus any nested or synthetic class beneath them. */
    fun named(vararg names: String): String = named(names.toList())

    fun named(names: List<String>): String = "(" + names.joinToString("|") { Regex.escape(it) } + ")(\\\$.*)?"

    /**
     * Regex matching a Kotlin member name as it appears in bytecode, including
     * the `$module` suffix the compiler appends to `internal` members and the
     * `$default` and `$lambda$n` companions it generates alongside them.
     */
    fun member(name: String): String = Regex.escape(name) + "(\\\$.*)?"
}
