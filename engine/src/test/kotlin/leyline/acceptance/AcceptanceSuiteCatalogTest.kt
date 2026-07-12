package leyline.acceptance

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension

/**
 * Statically validates the whole acceptance suite catalog: every suite parses, every scenario's
 * puzzle reference resolves to a fixture, every scenario has steps, and scenario ids are unique
 * across suites (they double as a cross-suite filter namespace). Runs at unit-test speed instead
 * of paying the puzzle-execution cost of [AcceptanceSuitesTest] to catch catalog-shape mistakes.
 */
class AcceptanceSuiteCatalogTest :
    FunSpec({
        tags(UnitTag)

        val suiteFiles = AcceptanceSuiteLoader.suiteFiles()

        test("discovers the acceptance suite catalog") {
            // Bump alongside puzzles/sets/*.yaml additions or removals.
            suiteFiles shouldHaveSize 10
        }

        suiteFiles.forEach { path ->
            test("validates ${path.name}") {
                val suite = AcceptanceSuiteLoader.loadFromFile(path)
                suite.name shouldBe path.nameWithoutExtension
                suite.scenarios.forEach { scenario ->
                    withClue("${path.name} scenario '${scenario.id}' references puzzle '${scenario.puzzle}'") {
                        AcceptanceSuiteLoader.puzzleExists(scenario.puzzle) shouldBe true
                    }
                    withClue("${path.name} scenario '${scenario.id}' has no steps") {
                        scenario.steps.shouldNotBeEmpty()
                    }
                }
            }
        }

        test("scenario ids are unique across the catalog") {
            val ids = suiteFiles.flatMap { path -> AcceptanceSuiteLoader.loadFromFile(path).scenarios.map { it.id } }
            val duplicates = duplicateIds(ids)
            withClue("duplicate scenario ids: $duplicates") {
                duplicates.shouldBeEmpty()
            }
        }

        test("negative: flags duplicate scenario ids across suites") {
            val suiteA =
                AcceptanceSuiteLoader.loadFromText(
                    """
                    |name: dup-a
                    |scenarios:
                    |  - id: shared-id
                    |    puzzle: warmup-land-permanent
                    |    steps:
                    |      - resolve_stack: {}
                    """.trimMargin(),
                )
            val suiteB =
                AcceptanceSuiteLoader.loadFromText(
                    """
                    |name: dup-b
                    |scenarios:
                    |  - id: shared-id
                    |    puzzle: warmup-land-permanent
                    |    steps:
                    |      - resolve_stack: {}
                    """.trimMargin(),
                )
            val ids = (suiteA.scenarios + suiteB.scenarios).map { it.id }
            duplicateIds(ids) shouldBe setOf("shared-id")
        }

        test("negative: flags a scenario puzzle reference that does not exist") {
            val suite =
                AcceptanceSuiteLoader.loadFromText(
                    """
                    |name: bogus-puzzle
                    |scenarios:
                    |  - id: bogus-puzzle-ref
                    |    puzzle: this-puzzle-does-not-exist-anywhere
                    |    steps:
                    |      - resolve_stack: {}
                    """.trimMargin(),
                )
            AcceptanceSuiteLoader.puzzleExists(suite.scenarios.single().puzzle) shouldBe false
        }
    })

private fun duplicateIds(ids: List<String>): Set<String> {
    val counts = ids.groupingBy { it }.eachCount()
    return counts.filterValues { it > 1 }.keys
}
