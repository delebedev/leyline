package leyline.architecture

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.tooling.headless.ActionSelection
import leyline.tooling.headless.CombatAction
import leyline.tooling.headless.ControlAction
import leyline.tooling.headless.HeadlessMatch
import leyline.tooling.headless.MatchIntent
import leyline.tooling.headless.MatchQuery
import leyline.tooling.headless.PlayAction
import leyline.tooling.headless.PromptResponse
import java.lang.reflect.Modifier
import java.lang.reflect.Type
import java.nio.file.Files
import java.nio.file.Path
import kotlin.metadata.KmClassifier
import kotlin.metadata.Visibility
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.jvm.UnstableMetadataApi
import kotlin.metadata.visibility

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

        test("compiled headless extension metadata has no public declarations") {
            val extensions = compiledHeadlessExtensions()
            extensions.shouldNotBeEmpty()
            extensions.filter { it.visibility == Visibility.PUBLIC }.map { it.qualifiedName }.shouldBeEmpty()
        }

        test("compiled metadata detector rejects public extension fixture forms") {
            val fixture = Class.forName("leyline.architecture.fixtures.HeadlessMatchBoundaryFixturesKt")
            val extensions = metadataExtensions(fixture)
            extensions.filter { it.visibility == Visibility.PUBLIC }.map { it.name }.toSet() shouldBe
                setOf(
                    "publicGenericFixture",
                    "publicJvmSyntheticFixture",
                    "publicMultilineFixture",
                )
            extensions.first { it.name == "internalFixture" }.visibility shouldBe Visibility.INTERNAL
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

private data class MetadataExtension(
    val qualifiedName: String,
    val name: String,
    val visibility: Visibility,
)

private const val EXTENSION_FIXTURE_PACKAGE = "leyline/architecture/fixtures/"

private fun compiledClasses(): List<CompiledClass> =
    (testClassFiles() + harnessClassFiles()).map { path ->
        CompiledClass(binaryName(path), classBytes(path), sourceSet(path))
    }

@OptIn(UnstableMetadataApi::class)
private fun metadataExtensions(type: Class<*>): List<MetadataExtension> {
    val metadata = type.getAnnotation(Metadata::class.java) ?: return emptyList()
    val declarationPackage =
        when (val parsed = KotlinClassMetadata.readLenient(metadata)) {
            is KotlinClassMetadata.FileFacade -> parsed.kmPackage
            is KotlinClassMetadata.MultiFileClassPart -> parsed.kmPackage
            is KotlinClassMetadata.Class,
            is KotlinClassMetadata.MultiFileClassFacade,
            is KotlinClassMetadata.SyntheticClass,
            is KotlinClassMetadata.Unknown,
            -> return emptyList()
        }
    return declarationPackage.functions.mapNotNull { function ->
        val receiver = function.receiverParameterType?.classifier as? KmClassifier.Class ?: return@mapNotNull null
        if (receiver.name != "leyline/tooling/headless/HeadlessMatch") return@mapNotNull null
        MetadataExtension(
            qualifiedName = "${type.name}.${function.name}",
            name = function.name,
            visibility = function.visibility,
        )
    }
}

private fun compiledHeadlessExtensions(): List<MetadataExtension> =
    compiledClasses()
        .filter { it.sourceSet == "harness" && !it.binaryName.startsWith(EXTENSION_FIXTURE_PACKAGE) }
        .flatMap { compiled ->
            val type = compiled.loadClass()
            val metadata = type.getAnnotation(Metadata::class.java) ?: return@flatMap emptyList()
            val parsed = KotlinClassMetadata.readLenient(metadata)
            if (parsed !is KotlinClassMetadata.FileFacade && parsed !is KotlinClassMetadata.MultiFileClassPart) {
                return@flatMap emptyList()
            }
            val declarations = metadataExtensions(type)
            val methods =
                type.declaredMethods.filter { method ->
                    Modifier.isStatic(method.modifiers) &&
                        method.parameterTypes.firstOrNull() == HeadlessMatch::class.java
                }
            val unmatched = methods.filter { method -> declarations.none { it.name == method.name } }
            check(unmatched.all { it.isSynthetic }) {
                "Missing Kotlin metadata for HeadlessMatch extension ${type.name}.${unmatched.first { !it.isSynthetic }.name}"
            }
            methods.flatMap { method -> declarations.filter { declaration -> declaration.name == method.name } }
        }

private fun CompiledClass.loadClass(): Class<*> =
    Class.forName(
        binaryName.replace('/', '.'),
        false,
        HeadlessMatchBoundaryTest::class.java.classLoader,
    )

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
