package leyline.architecture

import com.tngtech.archunit.core.domain.JavaFieldAccess
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

        test("the cut installer is the only production projection commit caller") {
            val callers =
                classes
                    .flatMap { it.methodCallsFromSelf }
                    .filter {
                        it.targetOwner.name == "leyline.game.state.GameBridge" &&
                            kotlinName(it.target.name) == "commitProjection" &&
                            it.originOwner.name.substringBefore('$') != "leyline.game.state.GameBridge"
                    }.map { it.originOwner.name.substringBefore('$') }
                    .toSet()

            callers shouldBe setOf("leyline.bridge.coord.CoordinatorCutInstaller")
        }

        test("the coordinator feed lock is the only cut publication monitor") {
            val bridge = classes.single { it.name == "leyline.game.state.GameBridge" }
            val obsoleteLock = "projection" + "BuildLock"

            (obsoleteLock in bridge.fields.map { it.name }) shouldBe false
        }

        test("projection state field writers match the publication and engine-shell inventory") {
            val bridge = classes.single { it.name == "leyline.game.state.GameBridge" }
            val writers =
                bridge.fieldAccessesFromSelf
                    .filter { it.target.name == "projectionState" && it.accessType == JavaFieldAccess.AccessType.SET }
                    .map { kotlinName(it.origin.name) }
                    .toSet()

            writers shouldBe
                setOf(
                    "<init>",
                    "getOrAllocInstanceId",
                    "installProjection",
                    "replaceProjectionStateForTest",
                    "resetForPuzzle",
                    "updateProjection",
                )
        }

        test("state-bearing single-feed cuts match the classified call-site inventory") {
            val preparedBundleCall =
                "PreparedCut.prepare(prior,planner,prepared.bundle.messages," +
                    "prepared.transition,prepared.closesPlaybackFrame)"
            val allowed =
                listOf(
                    "MatchActionWindowRuntime.kt:PreparedCut.prepare(prior,planner,messages," +
                        "prepared.transition,prepared.closesPlaybackFrame)",
                    "MatchBlockingInteractionRuntime.kt:${preparedBundleCall.dropLast(1)},)",
                    "MatchBlockingInteractionRuntime.kt:${preparedBundleCall.dropLast(1)},)",
                    "MatchLifecycleRuntime.kt:PreparedCut.prepare(prior,planner,messages," +
                        "full.transition,closesPlaybackFrame=false)",
                    "MatchLifecycleRuntime.kt:PreparedCut.prepare(prior,planner,prepared.messages," +
                        "prepared.transition,closesPlaybackFrame=false)",
                    "MatchTargetingInteractionRuntime.kt:$preparedBundleCall",
                )
            val coordRoot = EngineArchitecture.sourceRoot.resolve("leyline/bridge/coord")
            val calls = mutableListOf<String>()
            Files.walk(coordRoot).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                    .forEach { file ->
                        preparedCutCalls(Files.readString(file))
                            .filterNot { "projection = null" in it }
                            .forEach { call ->
                                calls += "${file.fileName}:" + call.filterNot(Char::isWhitespace)
                            }
                    }
            }

            withClue("state-bearing single-feed cuts must remain player-private or seat-scoped") {
                calls.sorted() shouldBe allowed.sorted()
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
                session shouldNotContain ".getOutcome()"
                continuation shouldNotContain "gameIsOver()"
                continuation shouldContain "committedGameOverOutcome()"
            }
        }

        test("mulligan redraw submits facts to the lifecycle cut") {
            val handler = Files.readString(EngineArchitecture.sourceRoot.resolve("leyline/match/MulliganHandler.kt"))

            listOf(
                handler.contains("publishMulliganRedraw(seatId, facts)"),
                handler.contains("bridge.resetInstanceIds()"),
            ) shouldBe listOf(true, false)
        }

        test("phase action replacement is declared to the cut installer") {
            val runtime = Files.readString(EngineArchitecture.sourceRoot.resolve("leyline/bridge/coord/MatchActionWindowRuntime.kt"))
            val publication = runtime.substringAfter("private fun publishPresentation").substringBefore("internal fun resolve")

            listOf(
                publication.contains("replaces = replaces"),
                publication.contains("removeOwnedBatch"),
                runtime.contains("removePrevious"),
            ) shouldBe listOf(true, false, false)
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

private fun preparedCutCalls(source: String): List<String> {
    val marker = "PreparedCut.prepare("
    val calls = mutableListOf<String>()
    var searchFrom = 0
    while (true) {
        val start = source.indexOf(marker, searchFrom)
        if (start < 0) return calls
        var depth = 1
        var cursor = start + marker.length
        while (cursor < source.length && depth > 0) {
            when (source[cursor]) {
                '(' -> depth++
                ')' -> depth--
            }
            cursor++
        }
        check(depth == 0) { "Unclosed PreparedCut.prepare call" }
        calls += source.substring(start, cursor)
        searchFrom = cursor
    }
}
