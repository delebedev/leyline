package leyline.architecture

import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.architecture.EngineArchitecture.kotlinName
import leyline.architecture.EngineArchitecture.named
import leyline.game.mapping.StateFrameInput
import leyline.game.mapping.StateMapper
import leyline.game.mapping.StateProjectionCompiler
import leyline.game.mapping.StateProjectionEnvironment
import leyline.game.mapping.ViewerProjectionIntent
import leyline.game.state.ProjectionState
import leyline.game.state.ProjectionTransition

class ProjectionComputeBoundaryTest :
    FunSpec({
        tags(UnitTag)

        val classes = EngineArchitecture.mainClasses
        val computeTypes =
            listOf(
                "leyline.game.mapping.StateMapper",
                "leyline.game.mapping.StateProjectionCompiler",
                "leyline.game.annotations.AnnotationContext",
                "leyline.game.annotations.AnnotationPipeline",
                "leyline.game.annotations.ZoneTransferAdapter",
                "leyline.game.mapping.ZoneMapper",
                "leyline.game.mapping.LinkedFaceCompanionProjector",
                "leyline.game.annotations.RevealStateContributor",
                "leyline.game.annotations.ConvokeContributor",
                "leyline.game.annotations.TargetSpecContributor",
                "leyline.game.mapping.PersistentFeedBuilder",
            )
        val computePattern = named(computeTypes)

        test("state projection compute has no GameBridge dependency") {
            noClasses()
                .that()
                .haveNameMatching(computePattern)
                .should()
                .dependOnClassesThat()
                .haveNameMatching(named("leyline.game.state.GameBridge"))
                .because("the shell supplies immutable inputs and StateMapper owns its private editor")
                .check(classes)
        }

        test("projection helpers with value-only topology have no Forge dependency") {
            noClasses()
                .that()
                .haveNameMatching(named(computeTypes - "leyline.game.mapping.StateMapper"))
                .should()
                .dependOnClassesThat()
                .resideInAPackage("forge..")
                .because("only the snapshot phase value remains at the outer mapper boundary")
                .check(classes)
        }

        test("state projection compute reaches prompt facts but not the prompt journal") {
            noClasses()
                .that()
                .haveNameMatching(computePattern)
                .should()
                .dependOnClassesThat()
                .haveNameMatching(named("leyline.bridge.handoff.PromptJournal"))
                .because("compute reads the projected prompt facts, never the live journal")
                .check(classes)
        }

        test("single-view compiler exposes one complete explicit-value entrypoint") {
            val method =
                StateProjectionCompiler::class.java.declaredMethods.single {
                    it.name == "compileOneViewer" && it.parameterCount == 4
                }
            assertSoftly {
                method.parameterTypes.toList() shouldBe
                    listOf(
                        StateProjectionEnvironment::class.java,
                        StateFrameInput::class.java,
                        ProjectionState::class.java,
                        ViewerProjectionIntent::class.java,
                    )
                method.returnType shouldBe StateProjectionCompiler.Result::class.java
                StateMapper::class.java.declaredMethods
                    .filter { it.name == "buildFromSnapshot" || it.name == "buildDiff" }
                    .shouldBeEmpty()
                StateProjectionCompiler.Result::class.java.getMethod("getTransition").returnType shouldBe
                    ProjectionTransition::class.java
                StateFrameInput::class.java.declaredFields
                    .filter { it.type == ProjectionState::class.java }
                    .shouldBeEmpty()
            }
        }

        test("the compiler opens one editor and freezes it once") {
            // The editor is the compiler's only mutable step. A second open or a
            // second freeze means a caller can observe a half-built projection.
            val compilerName = "leyline.game.mapping.StateProjectionCompiler"
            val calls =
                classes
                    .filter { it.name == compilerName || it.name.startsWith("$compilerName$") }
                    .flatMap { it.methodCallsFromSelf }
                    .filter { kotlinName(it.target.name) in setOf("editor", "freeze") }

            assertSoftly {
                listOf("editor", "freeze").forEach { name ->
                    val sites = calls.filter { kotlinName(it.target.name) == name }
                    withClue("$name() call sites in the compiler: ${sites.map { "${it.origin.name}:${it.lineNumber}" }}") {
                        sites.size shouldBe 1
                    }
                }
            }
        }
    })
