package leyline.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import io.kotest.core.spec.style.FunSpec
import leyline.UnitTag
import java.nio.file.Files
import java.nio.file.Path

/** Enforces value-only ownership for reveal-backed card choices. */
class RevealChoiceRuntimeBoundaryTest :
    FunSpec({
        tags(UnitTag)

        val cwd = Path.of("").toAbsolutePath()
        val buildDir =
            sequenceOf(cwd.resolve("build/classes"), cwd.resolve("engine/build/classes"))
                .first { it.resolve("kotlin/main/leyline").toFile().isDirectory }
        val classes =
            ClassFileImporter()
                .withImportOption(ImportOption.DoNotIncludeTests())
                .importPaths(buildDir.resolve("kotlin/main"), buildDir.resolve("java/main"))
        val sourceRoot =
            sequenceOf(cwd.resolve("src/main/kotlin"), cwd.resolve("engine/src/main/kotlin"))
                .first { it.resolve("leyline").toFile().isDirectory }

        test("reveal choice materialization and handoff values stay value-only") {
            noClasses()
                .that()
                .haveNameMatching("leyline\\.game\\.bundle\\.RevealChoiceWindowMaterializer(\\$.*)?")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("forge..")
                .check(classes)
            noClasses()
                .that()
                .haveNameMatching("leyline\\.game\\.bundle\\.RevealChoiceWindowMaterializer(\\$.*)?")
                .should()
                .dependOnClassesThat()
                .haveNameMatching("leyline\\.game\\.state\\.GameBridge(\\$.*)?")
                .check(classes)
            noClasses()
                .that()
                .haveNameMatching("leyline\\.bridge\\.handoff\\.(RevealChoice.*Value|PublishedRevealChoiceInteraction)(\\$.*)?")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("forge..")
                .check(classes)
        }

        test("reveal choice routes bypass residual SelectN session ownership") {
            val handler = Files.readString(sourceRoot.resolve("leyline/match/RevealChoiceInteractionHandler.kt"))
            listOf("import forge.", "findCard(", "getZone(", "getForgeCardId(", "activeReveal").forEach { forbidden ->
                check(forbidden !in handler)
            }
            val bridge = Files.readString(sourceRoot.resolve("leyline/bridge/handoff/InteractivePromptBridge.kt"))
            check("check(request.route is ResolvedPromptRoute.RevealChoice)" in bridge)
            val targeting = Files.readString(sourceRoot.resolve("leyline/match/TargetingHandler.kt"))
            check("coordinator.revealChoices.current() != null" in targeting)
            val route = Files.readString(sourceRoot.resolve("leyline/bridge/handoff/PromptRoute.kt"))
            check("PromptSemantic.RevealChoose -> ResolvedPromptRoute.RevealChoice" in route)

            val owners = mutableSetOf<String>()
            Files.walk(sourceRoot.resolve("leyline")).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                    .forEach { file ->
                        if ("prepareRevealChoiceWindow(" in Files.readString(file)) {
                            owners += sourceRoot.relativize(file).toString()
                        }
                    }
            }
            check(
                owners ==
                    setOf(
                        "leyline/bridge/coord/MatchRevealChoiceInteractionRuntime.kt",
                        "leyline/game/bundle/BundleBuilder.kt",
                    ),
            ) { "Unexpected RevealChoice-window preparers: ${owners.sorted()}" }
        }
    })
