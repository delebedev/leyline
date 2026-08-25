package leyline.game.generator

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import java.nio.file.Files
import java.nio.file.Path

/** Keeps shared and test-private puzzle namespaces owned and disjoint. */
class PuzzleFixtureTopologyTest :
    FunSpec({
        tags(UnitTag)

        val root = Path.of(System.getProperty("leyline.content.root", ".")).toAbsolutePath().normalize()
        val sharedDir = root.resolve("data/puzzles")
        val testDir = root.resolve("engine/src/test/resources/test-puzzles")

        test("shared fixtures have an acceptance, test, or guide owner") {
            val sources =
                listOf("engine/src", "app", "native", "web", "docs", "just", "data/puzzles")
                    .map(root::resolve)
                    .filter(Files::exists)
                    .flatMap(::sourceFiles)
                    .joinToString("\n") { Files.readString(it) }
            val orphaned =
                pzlNames(sharedDir).filter { name ->
                    !Regex(
                        "(?<![A-Za-z0-9_-])(?:data/puzzles/|test-puzzles/|puzzles/)?${Regex.escape(name)}(?:\\.pzl)?(?![A-Za-z0-9_-])",
                    ).containsMatchIn(sources)
                }
            orphaned.shouldBeEmpty()
        }

        test("shared and test-private namespaces have no basename collision") {
            pzlNames(sharedDir).intersect(pzlNames(testDir)).shouldBeEmpty()
        }

        test("legacy root puzzle directory is gone") {
            Files.exists(root.resolve("puzzles")) shouldBe false
        }
    })

private fun pzlNames(dir: Path): Set<String> =
    if (!Files.isDirectory(dir)) {
        emptySet()
    } else {
        Files.list(dir).use { stream ->
            stream
                .filter { it.fileName.toString().endsWith(".pzl") }
                .map { it.fileName.toString().removeSuffix(".pzl") }
                .toList()
                .toSet()
        }
    }

private fun sourceFiles(root: Path): List<Path> =
    Files.walk(root).use { stream ->
        stream
            .filter(Files::isRegularFile)
            .filter {
                it.fileName.toString().let { name ->
                    name.endsWith(".kt") ||
                        name.endsWith(".md") ||
                        name.endsWith(".yaml") ||
                        name.endsWith(".just") ||
                        name == "justfile"
                }
            }.toList()
    }
