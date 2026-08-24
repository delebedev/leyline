package leyline.architecture

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.config.LeylineConfigResolver
import java.nio.file.Files
import java.nio.file.Path

/**
 * Enforces the bootstrap boundary: production server code must not read
 * ambient process configuration (environment variables or system properties)
 * outside the approved bootstrap, CLI-tool, and platform-discovery files.
 *
 * The resolved configuration snapshot is the only server-configuration
 * channel; anything this test flags is a regression of the old scattered
 * env/sysprop surface.
 */
class AmbientConfigBoundaryTest :
    FunSpec({

        tags(UnitTag)

        val root = Path.of(System.getProperty("user.dir"))
        val modules =
            listOf(
                root.resolve("app/main/kotlin"),
                root.resolve("engine/src/main/kotlin"),
                root.resolve("native/src/main/kotlin"),
                root.resolve("web/src/main/kotlin"),
            )

        /** Files that own ambient input by design: entry-point bootstrap, separate CLI tools, platform discovery, build inputs. */
        val allowedFiles =
            setOf(
                // Entry-point bootstrap: capture process inputs, resolve, and fail on malformed state.
                "app/main/kotlin/leyline/LeylineMain.kt",
                "app/main/kotlin/leyline/WebMain.kt",
                "app/main/kotlin/leyline/config/LeylineConfigResolver.kt",
                // Separate CLI tools keep their own input handling.
                "app/main/kotlin/leyline/cli/SeedDb.kt",
                "app/main/kotlin/leyline/cli/CardDbPath.kt",
                // Platform discovery: journal and card database live at platform-standard locations.
                "app/main/kotlin/leyline/infra/ScrySessionJournal.kt",
                "engine/src/main/kotlin/leyline/game/data/ClientCardDatabase.kt",
                // Build/JVM input: Forge resource bundle mode.
                "engine/src/main/kotlin/leyline/bridge/bootstrap/ResourceResolver.kt",
            )

        fun mainKotlinFiles(): List<Path> =
            modules.flatMap { module ->
                Files.walk(module).use { stream ->
                    stream.filter { it.toString().endsWith(".kt") }.toList()
                }
            }

        fun relative(file: Path): String = root.relativize(file).toString().replace('\\', '/')

        fun hasAmbientRead(file: Path): Boolean {
            val text = Files.readString(file)
            return text.contains("System.getenv") || text.contains("System.getProperty")
        }

        test("no production server code reads ambient configuration outside the approved boundary") {
            val violations =
                mainKotlinFiles()
                    .filter { hasAmbientRead(it) }
                    .map(::relative)
                    .filterNot { it in allowedFiles }
            violations shouldBe emptyList()
        }

        test("no production server code references the deleted legacy configuration surface") {
            val violations =
                mainKotlinFiles()
                    .filterNot { relative(it) in allowedFiles }
                    .flatMap { file ->
                        val text = Files.readString(file)
                        val legacyKeys = LeylineConfigResolver.LEGACY_ENV_RENAMES.keys.filter { text.contains(it) }
                        val legacyType = if (Regex("""\bMatchConfig\b""").containsMatchIn(text)) listOf("MatchConfig") else emptyList()
                        (legacyKeys + legacyType).map { "${relative(file)}: $it" }
                    }
            violations shouldBe emptyList()
        }

        test("every ambient read that remains lives in an approved boundary file") {
            val filesWithReads = mainKotlinFiles().filter { hasAmbientRead(it) }.map(::relative).toSet()
            filesWithReads.shouldNotBeEmpty()
            allowedFiles.containsAll(filesWithReads) shouldBe true
        }
    })
