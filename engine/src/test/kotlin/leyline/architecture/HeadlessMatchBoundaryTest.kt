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
                Path
                    .of("engine/src/harness/kotlin/leyline/tooling/headless/HeadlessMatch.kt")
                    .takeIf { Files.exists(it) }
                    ?: Path.of("src/harness/kotlin/leyline/tooling/headless/HeadlessMatch.kt")
            val forbidden =
                listOf(
                    "GameBridge",
                    "MatchFlowHarness",
                    "MatchRegistry",
                    "ListMessageSink",
                    "ValidatingMessageSink",
                    "HeadlessSession",
                    "ClientAccumulator",
                    "forge.game",
                )
            val violations =
                Files.readAllLines(source).flatMapIndexed { index, line ->
                    forbidden.filter { token -> line.contains(token) }.map { token -> "$source:${index + 1}: $token" }
                }
            violations.shouldBeEmpty()
        }

        test("implementation remains behind the internal adapter class") {
            val source =
                Path
                    .of("engine/src/harness/kotlin/leyline/tooling/headless/MatchFlowHarness.kt")
                    .takeIf { Files.exists(it) }
                    ?: Path.of("src/harness/kotlin/leyline/tooling/headless/MatchFlowHarness.kt")
            Files
                .readAllLines(source)
                .firstOrNull { it.trim().contains("class MatchFlowHarness(") }
                ?.trim()
                ?.startsWith("internal class") shouldBe true
        }

        test("consumers do not reach through the headless seam") {
            val testRoot =
                Path.of("engine/src/test/kotlin/leyline").takeIf { Files.exists(it) }
                    ?: Path.of("src/test/kotlin/leyline")
            val sessionConsumers =
                Files.walk(testRoot).use { stream ->
                    stream
                        .filter { it.toString().endsWith(".kt") }
                        .filter { !it.fileName.toString().equals("HeadlessMatchBoundaryTest.kt") }
                        .filter { file -> Files.readAllLines(file).any { line -> "SessionTest(" in line } }
                        .toList()
                }
            val roots =
                listOf(
                    Path.of("engine/src/harness/kotlin/leyline/tooling/simclient"),
                    Path.of("engine/src/test/kotlin/leyline/acceptance"),
                    Path.of("engine/src/test/kotlin/leyline/testkit/SessionTest.kt"),
                ).map { path ->
                    path.takeIf { Files.exists(it) } ?: Path.of(path.toString().removePrefix("engine/"))
                } + sessionConsumers
            val forbidden =
                listOf(
                    "MatchFlowHarness",
                    "GameBridge",
                    "ClientAccumulator",
                    "ListMessageSink",
                    "ValidatingMessageSink",
                    "harness.bridge",
                    "harness.session",
                    "harness.accumulator",
                )
            val violations =
                roots.flatMap { root ->
                    val files =
                        if (Files.isDirectory(root)) {
                            Files.walk(root).use { stream -> stream.filter { it.toString().endsWith(".kt") }.toList() }
                        } else {
                            listOf(root)
                        }
                    files.flatMap { file ->
                        Files.readAllLines(file).flatMapIndexed { index, line ->
                            val sourceLine = line.trimStart()
                            forbidden
                                .filter { token -> token in line }
                                .filterNot { token ->
                                    sourceLine.startsWith("//") || sourceLine.startsWith("*")
                                }.map { token -> "$file:${index + 1}: $token" }
                        }
                    }
                }
            violations.shouldBeEmpty()
        }
    })
