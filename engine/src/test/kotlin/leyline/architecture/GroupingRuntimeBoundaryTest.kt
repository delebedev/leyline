package leyline.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import io.kotest.core.spec.style.FunSpec
import leyline.UnitTag
import java.nio.file.Files
import java.nio.file.Path

/** Enforces value-only publication and session ownership for Scry and Surveil grouping. */
class GroupingRuntimeBoundaryTest :
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

        test("Grouping materialization and handoff values stay Forge-free") {
            noClasses()
                .that()
                .haveNameMatching("leyline\\.game\\.bundle\\.GroupingWindowMaterializer(\\$.*)?")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("forge..")
                .check(classes)
            noClasses()
                .that()
                .haveNameMatching("leyline\\.bridge\\.handoff\\.(Grouping.*Value|PublishedGroupingInteraction)(\\$.*)?")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("forge..")
                .check(classes)
        }

        test("Grouping routes bypass legacy session and builder ownership") {
            val handler = Files.readString(sourceRoot.resolve("leyline/match/GroupingInteractionHandler.kt"))
            listOf("import forge.", "findById(", "getZone(", "getForgeCardId(").forEach { forbidden ->
                check(forbidden !in handler)
            }
            val bridge = Files.readString(sourceRoot.resolve("leyline/bridge/handoff/InteractivePromptBridge.kt"))
            check("check(request.route is ResolvedPromptRoute.Grouping)" in bridge)
            val targeting = Files.readString(sourceRoot.resolve("leyline/match/TargetingHandler.kt"))
            listOf("sendGroupReqForSurveilScry", "resolveSurveilScryBundle", "fun onGroupResp").forEach { removed ->
                check(removed !in targeting)
            }
            val gameBridge = Files.readString(sourceRoot.resolve("leyline/game/state/GameBridge.kt"))
            listOf("recordLibraryArrangement", "pollLibraryArrangement", "pendingLibraryArrangements").forEach { removed ->
                check(removed !in gameBridge)
            }

            val owners = mutableSetOf<String>()
            Files.walk(sourceRoot.resolve("leyline")).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                    .forEach { file ->
                        if ("prepareGroupingWindow(" in Files.readString(file)) {
                            owners += sourceRoot.relativize(file).toString()
                        }
                    }
            }
            check(
                owners ==
                    setOf(
                        "leyline/bridge/coord/MatchGroupingInteractionRuntime.kt",
                        "leyline/game/bundle/BundleBuilder.kt",
                    ),
            ) { "Unexpected Grouping-window preparers: ${owners.sorted()}" }
        }
    })
