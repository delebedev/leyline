package leyline.architecture

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaMethodCall
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import leyline.UnitTag
import leyline.architecture.EngineArchitecture.kotlinName
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
                    pendingAction shouldContain "windowRuntime.promptGameStateId("
                }
                withClue("window visibility must come from the window runtime") {
                    bridge shouldContain "p.windowRuntime.isVisible("
                }
            }
        }

        test("opponent priority suppression stays behind the coordinator") {
            val cutCoordinator = "leyline.bridge.coord.MatchCutCoordinator"

            classes()
                .that()
                .haveFullyQualifiedName(cutCoordinator)
                .should()
                .callMethodWhere(
                    methodCall(
                        "leyline.bridge.coord.MatchActionWindowRuntime",
                        "suppressPriorityPresentation",
                        "mutate priority visibility",
                    ),
                ).because("the coordinator owns the action-window visibility mutation")
                .check(classes)
        }

        test("session progression is owned by engine runtime continuation") {
            val session = Files.readString(EngineArchitecture.sourceRoot.resolve("leyline/match/MatchSession.kt"))
            val connection = Files.readString(EngineArchitecture.sourceRoot.resolve("leyline/match/MatchConnection.kt"))
            val bridge = Files.readString(EngineArchitecture.sourceRoot.resolve("leyline/game/state/GameBridge.kt"))
            val continuation = Files.readString(EngineArchitecture.sourceRoot.resolve("leyline/match/MatchRuntimeContinuation.kt"))
            val forbidden =
                listOf(
                    "Executor",
                    "requestAutoAdvance",
                    "autoAdvanceRequester",
                    "playbackDrainRequester",
                    "awaitQuiescence",
                    "awaitRuntimeHorizon",
                )

            assertSoftly {
                forbidden shouldHaveSize 6
                forbidden.forEach { name ->
                    withClue("session runtime must not retain $name") { session shouldNotContain name }
                    withClue("connection runtime must not retain $name") { connection shouldNotContain name }
                    withClue("bridge runtime must not retain $name") { bridge shouldNotContain name }
                }
                continuation shouldContain "drainCoordinatorBarrier"
                continuation shouldContain "awaitSeatHorizonWithTimeout"
                continuation shouldNotContain "ENGINE_PASS_TOKEN"
                continuation shouldNotContain "BundleBuilder.shouldAutoPass"
                continuation shouldNotContain "submitRuntimeToken"
                continuation shouldNotContain "continuePassOnly"
                continuation shouldNotContain "isPassOnlyPriority"
            }
        }

        test("post-handler horizons have one transport delivery observer") {
            val observer = Files.readString(EngineArchitecture.sourceRoot.resolve("leyline/match/MatchRuntimeDeliveryObserver.kt"))
            val connection = Files.readString(EngineArchitecture.sourceRoot.resolve("leyline/match/MatchConnection.kt"))
            val coordinator = Files.readString(EngineArchitecture.sourceRoot.resolve("leyline/bridge/coord/MatchCutCoordinator.kt"))

            assertSoftly {
                listOf(
                    observer.contains("deliverySignal"),
                    observer.contains("deliverRuntimeHorizon"),
                    coordinator.contains("internal val deliverySignal"),
                ).count { it } shouldBe 3
                observer shouldContain "deliverySignal"
                observer shouldContain "deliverRuntimeHorizon"
                observer shouldNotContain "prioritySignal"
                observer shouldNotContain "submitGREMessage"
                observer shouldNotContain "awaitPriority"
                connection shouldContain "armRuntimeDeliveryObserver()"
                connection shouldContain "stopRuntimeDeliveryObserver()"
                coordinator shouldContain "internal val deliverySignal"
            }
        }

        test("transport and session code cannot allocate logical sequence or output order") {
            val roots =
                listOf(
                    EngineArchitecture.sourceRoot.resolve("leyline/match"),
                    EngineArchitecture.sourceRoot.resolve("leyline/infra"),
                )
            val forbidden =
                listOf(
                    "MessageCounter",
                    "LogicalSequencePlanner",
                    "nextGsId(",
                    "nextMsgId(",
                    "setGsId(",
                    "setMsgId(",
                    "allocateOutputOrdinal(",
                )
            val violations = mutableListOf<String>()
            roots.filter(Files::exists).forEach { root ->
                Files.walk(root).use { stream ->
                    stream
                        .filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                        .forEach { file ->
                            val source = Files.readString(file)
                            forbidden.filter(source::contains).forEach { token ->
                                violations += "${EngineArchitecture.sourceRoot.relativize(file)}: $token"
                            }
                        }
                }
            }

            violations.shouldBeEmpty()
        }

        test("accumulated settings state has one runtime owner") {
            // Inspect declared fields rather than all dependencies: protocol
            // heads and builders may handle immutable SettingsMessage values,
            // but only the runtime may retain one across requests.
            fields()
                .that()
                .haveRawType("wotc.mtgo.gre.external.messaging.Messages\$SettingsMessage")
                .should()
                .beDeclaredInClassesThat()
                .haveFullyQualifiedName("leyline.bridge.coord.PriorityPolicyRuntime")
                .because("only the priority runtime may retain accumulated client settings")
                .check(classes)
        }

        test("response handlers do not reconstruct Forge identities") {
            val sessionRoot = EngineArchitecture.sourceRoot.resolve("leyline/match")
            val migratedHandlers =
                listOf(
                    "ActionPerformer.kt",
                    "CombatHandler.kt",
                    "DeferredCastCostInteractionHandler.kt",
                    "ManaSourcePaymentHandler.kt",
                    "TargetingHandler.kt",
                )
            val forbiddenReads = listOf("getForgeCardId(", "getInstanceIdMap(", "getPlayer(", ".players", "findCard(")
            val runtime = Files.readString(sessionRoot.resolve("../bridge/coord/RuntimeCombatWindow.kt"))

            assertSoftly {
                migratedHandlers shouldHaveSize 5
                migratedHandlers.forEach { name ->
                    val source = Files.readString(sessionRoot.resolve(name))
                    forbiddenReads.forEach { read ->
                        withClue("$name must submit client values; it must not perform $read") {
                            source shouldNotContain read
                        }
                    }
                }
                withClue("deferred admission must hide prompt catalogs and claim completion from the session") {
                    val deferred = Files.readString(sessionRoot.resolve("DeferredCastCostInteractionHandler.kt"))
                    listOf(
                        "DeferredCastPrompt.",
                        "currentDeferredCastPrompt",
                        "completeActionClaim(",
                        "failActionClaim(",
                        "BundleBuilder",
                        "sendBundledGRE(",
                        "commitProjection(",
                    ).forEach { deferred shouldNotContain it }
                }
                withClue("damage admission must preserve raw rows until the runtime resolves retained handles") {
                    val combat = Files.readString(sessionRoot.resolve("CombatHandler.kt"))
                    listOf("DamageAssignmentValue(", "opponent.value", "damageMap")
                        .forEach { combat shouldNotContain it }
                }
                withClue("the action runtime must retain the identities removed from session code") {
                    runtime shouldContain "getForgeCardId("
                    runtime shouldContain "getPlayer("
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
        "leyline.match.CombatHandler",
        "leyline.match.MatchSession",
        "leyline.match.MatchSessionKt",
        "leyline.match.MulliganHandler",
        "leyline.match.PuzzleHandler",
        "leyline.match.SessionContext",
        "leyline.match.SpectatorSession",
        "leyline.match.TargetingHandler",
    )

private fun methodCall(
    owner: String,
    name: String,
    description: String,
) = object : DescribedPredicate<JavaMethodCall>(description) {
    override fun test(call: JavaMethodCall): Boolean = call.targetOwner.name == owner && kotlinName(call.target.name) == name
}
