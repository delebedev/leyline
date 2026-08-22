package leyline.architecture

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.tooling.headless.ActionSelection
import leyline.tooling.headless.CombatAction
import leyline.tooling.headless.ControlAction
import leyline.tooling.headless.MatchIntent
import leyline.tooling.headless.MatchQuery
import leyline.tooling.headless.PlayAction
import leyline.tooling.headless.PromptResponse
import java.lang.reflect.Type
import java.nio.file.Files
import java.nio.file.Path

/**
 * Keeps the semantic seam independent from runtime handles.
 *
 * The check reads compiled class references for every full-loop consumer. This
 * catches typealiases and qualified references after Kotlin has erased source
 * spelling, while allowing deliberately low-level Forge tests to import the
 * implementation adapter explicitly.
 */
class HeadlessMatchBoundaryTest :
    FunSpec({
        tags(UnitTag)

        test("headless intent values expose only semantic types") {
            io.kotest.assertions.assertSoftly {
                forbiddenTypesIn(MatchIntent::class.java).shouldBeEmpty()
                forbiddenTypesIn(MatchQuery::class.java).shouldBeEmpty()

                MatchIntent::class.java.declaredClasses
                    .map { it.simpleName }
                    .toSet() shouldBe
                    setOf("Play", "Combat", "Prompt", "Control")
                ActionSelection::class.java.declaredFields
                    .map { it.name }
                    .toSet() shouldBe
                    setOf("kind", "instanceId", "abilityGrpId", "alternativeGrpId")
                listOf(PlayAction::class.java, CombatAction::class.java, PromptResponse::class.java, ControlAction::class.java)
                    .flatMap(::forbiddenTypesIn)
                    .shouldBeEmpty()
            }
        }

        test("full-loop consumers have no compiled runtime reach-through") {
            val files =
                (testClassFiles() + harnessClassFiles()).filter { path ->
                    val relative = path.toString().replace('\\', '/')
                    relative.contains("/leyline/acceptance/") ||
                        relative.endsWith("/leyline/session/targeting/KeywordTriggerTargetPromptTest.class") ||
                        relative.contains("/leyline/mechanics/endure/") ||
                        relative.endsWith("/leyline/tooling/simclient/SimClientE2ETest.class") ||
                        relative.contains("/leyline/tooling/simclient/") &&
                        relative.contains("build/classes/kotlin/harness") ||
                        classBytes(path).containsAscii("leyline/testkit/SessionTest")
                }
            val violations = files.flatMap { path -> forbiddenRuntimeTypes(classBytes(path)).map { "$path: $it" } }
            violations.shouldBeEmpty()
        }

        test("compiled boundary detector catches a forbidden alias reference") {
            val sample = "Lleyline/game/state/GameBridge;".toByteArray()
            forbiddenRuntimeTypes(sample) shouldBe listOf("leyline/game/state/GameBridge")
        }
    })

private val forbiddenRuntimeNames =
    listOf(
        "leyline/tooling/headless/MatchFlowHarness",
        "leyline/game/state/GameBridge",
        "leyline/match/MatchSession",
        "leyline/infra/ListMessageSink",
        "leyline/tooling/headless/ClientAccumulator",
        "forge/game/Game",
        "forge/game/player/Player",
        "forge/game/card/Card",
    )

private fun forbiddenTypesIn(type: Class<*>): List<String> =
    (listOf(type) + type.declaredClasses.flatMap(::nestedTypes)).flatMap { nested ->
        nested.declaredMethods
            .flatMap { method ->
                listOf(method.genericReturnType) + method.genericParameterTypes.toList()
            }.flatMap(::forbiddenTypeNames) +
            nested.declaredFields.flatMap { forbiddenTypeNames(it.genericType) }
    }

private fun nestedTypes(type: Class<*>): List<Class<*>> = listOf(type) + type.declaredClasses.flatMap(::nestedTypes)

private fun forbiddenTypeNames(type: Type): List<String> = forbiddenRuntimeNames.filter { it in type.typeName.replace('.', '/') }

private fun testClassFiles(): List<Path> =
    listOf(
        Path.of("engine/build/classes/kotlin/test"),
        Path.of("build/classes/kotlin/test"),
    ).filter(Files::isDirectory).flatMap { root ->
        Files.walk(root).use { stream -> stream.filter { it.toString().endsWith(".class") }.toList() }
    }

private fun harnessClassFiles(): List<Path> =
    listOf(
        Path.of("engine/build/classes/kotlin/harness"),
        Path.of("build/classes/kotlin/harness"),
    ).filter(Files::isDirectory).flatMap { root ->
        Files.walk(root).use { stream -> stream.filter { it.toString().endsWith(".class") }.toList() }
    }

private fun classBytes(path: Path): ByteArray = Files.readAllBytes(path)

private fun ByteArray.containsAscii(value: String): Boolean = toString(Charsets.ISO_8859_1).contains(value)

private fun forbiddenRuntimeTypes(bytes: ByteArray): List<String> = forbiddenRuntimeNames.filter(bytes::containsAscii)
