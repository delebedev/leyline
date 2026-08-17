package leyline.architecture

import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import leyline.UnitTag
import leyline.architecture.EngineArchitecture.named
import java.nio.file.Files

/**
 * Enforces the runtime boundary direction defined by ADR 0014: match
 * orchestration may depend on immutable engine-facing values, but direct Forge
 * dependencies stay behind the engine boundary. Alongside it, the single-owner
 * rules for cut installation, command arbitration, and the action window
 * lifecycle.
 *
 * Per-prompt-family ownership lives in [PromptRouteBoundaryTest]; projection
 * value boundaries live in [ValueProjectionBoundaryTest].
 */
class RuntimeBoundaryTest :
    FunSpec({

        tags(UnitTag)

        val classes = EngineArchitecture.mainClasses

        test("match Forge dependencies stay within the allowlist") {
            // An upper bound, not an expected-equality snapshot: a listed class may
            // shed its Forge dependency without editing this test, while any new
            // Forge-touching class in leyline.match fails. Remove names only after
            // their dependencies are gone.
            noClasses()
                .that()
                .resideInAPackage("leyline.match..")
                .and()
                .haveNameNotMatching(named(forgeCoupledMatchClasses))
                .should()
                .dependOnClassesThat()
                .resideInAPackage("forge..")
                .because("ADR 0014 keeps direct Forge coupling behind the engine boundary")
                .check(classes)
        }

        test("one exchange owns iterative command arbitration") {
            // Targeting and mana-source payment have different domains but one
            // cross-thread handshake. A second command queue in this package means
            // the deadline/delivery race has to be fixed twice again.
            noClasses()
                .that()
                .resideInAPackage("leyline.bridge.coord..")
                .and()
                .haveNameNotMatching(named("leyline.bridge.coord.InteractiveCommandExchange"))
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("java.util.concurrent.LinkedBlockingQueue")
                .because("iterative command arbitration stays centralized in one exchange")
                .check(classes)
        }

        test("one installer owns the coordinator cut transaction") {
            // Source-level, deliberately: the property is the co-occurrence of a
            // queue append and a projection commit in one class, which reads as two
            // unrelated call edges in the class model. MatchSearchInteractionRuntime
            // commits a projection without appending a batch and is not an owner.
            val owners = setOf("CoordinatorCutInstaller.kt", "MatchCutCoordinator.kt")
            val coordRoot = EngineArchitecture.sourceRoot.resolve("leyline/bridge/coord")
            val installers = mutableSetOf<String>()
            Files.walk(coordRoot).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                    .forEach { file ->
                        val source = Files.readString(file)
                        if ("commitProjection(" in source && "queue.add(" in source) {
                            installers += coordRoot.relativize(file).toString()
                        }
                    }
            }

            withClue("cut installation must stay centralized; owners are $owners") {
                installers shouldBe owners
            }
        }

        test("the action window record is the only lifecycle authority") {
            // Source-level, deliberately: this asserts the absence of mutable mirror
            // state on a data class and the presence of delegation to the runtime
            // record. Absent members leave nothing in the class model to match.
            val bridge =
                EngineArchitecture.sourceRoot
                    .resolve("leyline/bridge/handoff/GameActionBridge.kt")
                    .let(Files::readString)
            val pendingAction =
                bridge
                    .substringAfter("data class PendingAction(")
                    .substringBefore("sealed interface ActionSubmission")

            assertSoftly {
                withClue("mirror flags on PendingAction restore a second lifecycle authority") {
                    listOf("var published", "var claimed", "var promptGameStateId")
                        .filter(pendingAction::contains)
                        .shouldBeEmpty()
                }
                withClue("PendingAction must read its prompt correlation from the window runtime") {
                    pendingAction shouldContain "windowRuntime?.promptGameStateId("
                }
                withClue("window visibility must come from the window runtime") {
                    bridge shouldContain "windowRuntime?.isVisible("
                }
            }
        }
    })

/**
 * Match-layer classes that still hold a direct Forge dependency. Every other
 * class under `leyline.match` reaches Forge only through the engine boundary.
 */
private val forgeCoupledMatchClasses =
    listOf(
        "leyline.match.ActionPerformer",
        "leyline.match.AutoPassEngine",
        "leyline.match.CombatHandler",
        "leyline.match.MatchSession",
        "leyline.match.MatchSessionKt",
        "leyline.match.MulliganHandler",
        "leyline.match.PuzzleHandler",
        "leyline.match.SessionContext",
        "leyline.match.SpectatorSession",
        "leyline.match.TargetingHandler",
    )
