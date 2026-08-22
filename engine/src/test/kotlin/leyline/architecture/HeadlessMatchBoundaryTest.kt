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
            val violations =
                fullLoopConsumerClasses(compiledClasses())
                    .flatMap { compiled -> forbiddenRuntimeTypes(compiled.bytes).map { "${compiled.binaryName}: $it" } }
            violations.shouldBeEmpty()
        }

        test("compiled boundary detector catches a forbidden alias reference") {
            val sample = "Lleyline/game/state/GameBridge;".toByteArray()
            forbiddenRuntimeTypes(sample) shouldBe listOf("leyline/game/state/GameBridge")
        }

        test("compiled boundary detector includes generated SessionTest nested classes") {
            val sample =
                listOf(
                    CompiledClass("leyline/FakeSessionSpec", "Lleyline/testkit/SessionTest;".toByteArray()),
                    CompiledClass("leyline/FakeSessionSpec\$nested", "Lleyline/game/state/GameBridge;".toByteArray()),
                )
            fullLoopConsumerClasses(sample)
                .flatMap { compiled -> forbiddenRuntimeTypes(compiled.bytes) }
                .shouldBe(listOf("leyline/game/state/GameBridge"))
        }

        test("headless convenience extensions are module-internal") {
            val source =
                listOf(
                    Path.of("engine/src/harness/kotlin/leyline/tooling/headless/HeadlessMatch.kt"),
                    Path.of("src/harness/kotlin/leyline/tooling/headless/HeadlessMatch.kt"),
                ).first(Files::exists)
            val publicExtensions =
                Files.readAllLines(source).filter { line ->
                    line.matches(Regex("\\s*(?:public\\s+)?fun HeadlessMatch\\..*"))
                }
            publicExtensions.shouldBeEmpty()
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

private data class CompiledClass(
    val binaryName: String,
    val bytes: ByteArray,
    val sourceSet: String = "test",
)

private fun compiledClasses(): List<CompiledClass> =
    (testClassFiles() + harnessClassFiles()).map { path ->
        CompiledClass(binaryName(path), classBytes(path), sourceSet(path))
    }

private fun fullLoopConsumerClasses(classes: List<CompiledClass>): List<CompiledClass> {
    val sessionRoots =
        classes
            .filter { !it.binaryName.startsWith("leyline/architecture/") }
            .filter { it.bytes.containsAscii("leyline/testkit/SessionTest") }
            .map { it.binaryName }
    return classes.filter { compiled ->
        isExplicitFullLoopConsumer(compiled) ||
            sessionRoots.any { root -> compiled.binaryName == root || compiled.binaryName.startsWith("$root$") }
    }
}

private fun isExplicitFullLoopConsumer(compiled: CompiledClass): Boolean =
    compiled.binaryName.contains("leyline/acceptance/") ||
        compiled.binaryName == "leyline/session/targeting/KeywordTriggerTargetPromptTest" ||
        compiled.binaryName.contains("leyline/mechanics/endure/") ||
        compiled.sourceSet == "test" &&
        compiled.binaryName == "leyline/tooling/simclient/SimClientE2ETest" ||
        compiled.sourceSet == "harness" &&
        compiled.binaryName.contains("leyline/tooling/simclient/")

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

private fun binaryName(path: Path): String {
    val normalized = path.toString().replace('\\', '/')
    val marker = listOf("/kotlin/test/", "/kotlin/harness/").first { normalized.contains(it) }
    return normalized.substringAfter(marker).removeSuffix(".class")
}

private fun sourceSet(path: Path): String = if (path.toString().replace('\\', '/').contains("/kotlin/harness/")) "harness" else "test"

private fun classBytes(path: Path): ByteArray = Files.readAllBytes(path)

private fun ByteArray.containsAscii(value: String): Boolean = toString(Charsets.ISO_8859_1).contains(value)

private fun forbiddenRuntimeTypes(bytes: ByteArray): List<String> = forbiddenRuntimeNames.filter(bytes::containsAscii)
