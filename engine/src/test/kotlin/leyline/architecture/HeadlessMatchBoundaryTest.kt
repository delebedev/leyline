package leyline.architecture

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import java.nio.file.Files
import java.nio.file.Path

/**
 * Keeps the external headless seam independent from runtime infrastructure.
 * The concrete adapter is allowed to depend on the engine; the value seam is
 * not allowed to make those implementation types part of its interface.
 */
class HeadlessMatchBoundaryTest :
    FunSpec({
        tags(UnitTag)

        test("semantic headless values do not import runtime infrastructure") {
            val source =
                Path.of("engine/src/harness/kotlin/leyline/tooling/headless/HeadlessMatch.kt")
                    .takeIf { Files.exists(it) }
                    ?: Path.of("src/harness/kotlin/leyline/tooling/headless/HeadlessMatch.kt")
            val forbidden =
                listOf(
                    "GameBridge",
                    "MatchSession",
                    "MatchRegistry",
                    "ListMessageSink",
                    "ValidatingMessageSink",
                    "ClientAccumulator",
                    "forge.game",
                )
            val violations =
                Files.readAllLines(source).flatMapIndexed { index, line ->
                    forbidden.filter { token -> line.contains(token) }.map { token -> "${source}:${index + 1}: $token" }
                }
            violations.shouldBeEmpty()
        }

        test("implementation remains behind the internal adapter class") {
            val source =
                Path.of("engine/src/harness/kotlin/leyline/tooling/headless/MatchFlowHarness.kt")
                    .takeIf { Files.exists(it) }
                    ?: Path.of("src/harness/kotlin/leyline/tooling/headless/MatchFlowHarness.kt")
            Files.readAllLines(source)
                .firstOrNull { it.trim().contains("class MatchFlowHarness(") }
                ?.trim()
                ?.startsWith("internal class") shouldBe true
        }
    })
