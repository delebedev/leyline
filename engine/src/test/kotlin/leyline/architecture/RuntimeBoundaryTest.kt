package leyline.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.handoff.DamageAssignmentPrompt
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.NumericInputPrompt
import leyline.bridge.handoff.OptionalActionPrompt
import leyline.bridge.types.PriorityActionFacts
import leyline.game.CombatDeclarationFacts
import leyline.game.EngineObservation
import leyline.game.GamePlayback
import leyline.game.InteractivePlaybackMaterializer
import leyline.game.PlaybackCutReason
import leyline.game.PlaybackYield
import leyline.game.SeatRuntimeFacts
import leyline.game.bundle.MessageCounter
import leyline.game.event.GameEventCollector
import leyline.game.mapping.NaiveGsmActionCapture
import leyline.game.state.GameBridge
import leyline.match.EngineCutAwaiter
import leyline.match.GameOps
import leyline.match.MatchOwner
import leyline.match.MatchSession
import leyline.match.SessionContext
import leyline.match.SpectatorSession
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path

/**
 * Enforces the runtime boundary direction defined by ADR 0014.
 *
 * Match orchestration may depend on immutable engine-facing values, but new
 * direct Forge dependencies must stay behind the engine boundary. Match and
 * game packages also remain independent of protocol-head transports.
 */
class RuntimeBoundaryTest :
    FunSpec({

        tags(UnitTag)

        val cwd = Path.of("").toAbsolutePath()
        val buildDir =
            sequenceOf(
                cwd.resolve("build/classes"),
                cwd.resolve("engine/build/classes"),
            ).first { it.resolve("kotlin/main/leyline").toFile().isDirectory }

        val classes =
            ClassFileImporter()
                .withImportOption(ImportOption.DoNotIncludeTests())
                .importPaths(
                    buildDir.resolve("kotlin/main"),
                    buildDir.resolve("java/main"),
                )

        test("match orchestration has zero semantic Forge dependencies") {
            noClasses()
                .that()
                .resideInAnyPackage("leyline.match..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("forge..")
                .because("live engine objects stay behind immutable value and command seams")
                .check(classes)
        }

        test("session-facing prompt and priority facts contain no Forge objects") {
            val valueTypes =
                listOf(
                    InteractivePromptBridge.PendingPrompt::class.java,
                    DamageAssignmentPrompt::class.java,
                    OptionalActionPrompt::class.java,
                    NumericInputPrompt::class.java,
                    PriorityActionFacts::class.java,
                )
            val liveFields =
                valueTypes.flatMap { type ->
                    type.declaredFields.mapNotNull { field ->
                        field.genericType.typeName
                            .takeIf { "forge." in it || "CompletableFuture" in it }
                            ?.let { "${type.simpleName}.${field.name}: $it" }
                    }
                }
            check(liveFields.isEmpty()) {
                "Session-facing boundary values retain live fields: ${liveFields.joinToString()}"
            }

            noClasses()
                .that()
                .resideInAnyPackage("leyline.match..")
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("java.util.concurrent.CompletableFuture")
                .because("session handlers submit values through bridge-owned completion gateways")
                .check(classes)
        }

        test("worker observations contain values only") {
            val valueTypes =
                listOf(
                    EngineObservation::class.java,
                    SeatRuntimeFacts::class.java,
                    CombatDeclarationFacts::class.java,
                    CombatDeclarationFacts.AttackerFact::class.java,
                    CombatDeclarationFacts.BlockerFact::class.java,
                    CombatDeclarationFacts.DamageRecipientFact.PlayerSeat::class.java,
                    CombatDeclarationFacts.DamageRecipientFact.Planeswalker::class.java,
                )
            val liveFields =
                valueTypes.flatMap { type ->
                    type.declaredFields.mapNotNull { field ->
                        field.genericType.typeName
                            .takeIf {
                                "forge." in it ||
                                    "wotc.mtgo.gre" in it ||
                                    "CompletableFuture" in it ||
                                    "Function" in it ||
                                    "GameBridge" in it
                            }?.let { "${type.simpleName}.${field.name}: $it" }
                    }
                }
            check(liveFields.isEmpty()) {
                "Worker observation values retain live fields: ${liveFields.joinToString()}"
            }
        }

        test("match orchestration cannot read the live engine graph") {
            val matchSource =
                sequenceOf(
                    cwd.resolve("src/main/kotlin/leyline/match"),
                    cwd.resolve("engine/src/main/kotlin/leyline/match"),
                ).first(Files::isDirectory)
            val forbidden =
                Regex(
                    """\.(?:getGame|requireGame|runtimeFacts)\(|""" +
                        """\b(?:GsmSnapshot|SnapshotCapture)\.capture\(|""" +
                        """\b(?:bridge|gameBridge|ctx\.bridge)\.snapshot\(""",
                )
            val violations = mutableListOf<String>()
            Files.walk(matchSource).use { files ->
                files
                    .filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                    .forEach { file ->
                        Files.readAllLines(file).forEachIndexed { index, line ->
                            if (forbidden.containsMatchIn(line)) {
                                violations += "${matchSource.relativize(file)}:${index + 1}"
                            }
                        }
                    }
            }
            check(violations.isEmpty()) {
                "Match orchestration reads the live engine graph: ${violations.joinToString()}"
            }
        }

        test("match and game do not depend on transport implementations") {
            noClasses()
                .that()
                .resideInAnyPackage(
                    "leyline.match..",
                    "leyline.game..",
                ).should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                    "io.netty..",
                    "io.ktor..",
                    "java.nio.channels..",
                    "kotlinx.coroutines.channels..",
                    "..transport..",
                    "leyline.native..",
                    "leyline.web..",
                ).because(
                    "ADR 0014 keeps transport channels in protocol heads outside the match runtime",
                ).check(classes)
        }

        test("priority action state crosses the worker boundary as values") {
            noClasses()
                .that()
                .haveNameMatching(
                    "leyline\\.bridge\\.handoff\\.GameActionBridge\\\$ActionOffer|" +
                        "leyline\\.bridge\\.handoff\\.GameActionBridge\\\$ActionSubmission|" +
                        "leyline\\.bridge\\.handoff\\.GameActionBridge\\\$PendingAction|" +
                        "leyline\\.game\\.mapping\\.ActionMapper\\\$ActionProjection|" +
                        "leyline\\.match\\.PendingClientInteraction.*",
                ).should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("leyline.bridge.handoff.PlayerAction")
                .because("live executable commands remain in the worker-owned token table")
                .check(classes)

            noClasses()
                .that()
                .haveFullyQualifiedName("leyline.bridge.handoff.GameActionBridge\$ActionOffer")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("forge..")
                .because("priority action offers contain tokens and immutable projection facts only")
                .check(classes)
        }

        test("prompt correlation is plain state held by the serial owner") {
            val counterFields =
                MessageCounter::class.java.declaredFields
                    .map { it.name }
                    .toSet()
            check("lastPromptGsId" !in counterFields && "lastPromptMsgId" !in counterFields) {
                "MessageCounter still owns prompt correlation: $counterFields"
            }

            val stateType =
                MatchOwner::class.java.declaredClasses.singleOrNull {
                    it.simpleName == "OwnerProtocolState"
                }
            checkNotNull(stateType) { "MatchOwner must contain OwnerProtocolState" }
            check(Modifier.isPrivate(stateType.modifiers)) {
                "OwnerProtocolState must be private to MatchOwner"
            }

            val sharedStateFields =
                stateType.declaredFields.filter {
                    Modifier.isVolatile(it.modifiers) ||
                        it.type.name.startsWith("java.util.concurrent") ||
                        it.type.name.startsWith("java.util.concurrent.atomic")
                }
            check(sharedStateFields.isEmpty()) {
                "OwnerProtocolState must remain plain owner-confined state: ${sharedStateFields.map { it.name }}"
            }

            val ownerState =
                MatchOwner::class.java.declaredFields.singleOrNull {
                    it.type == stateType
                }
            checkNotNull(ownerState) { "MatchOwner must hold OwnerProtocolState directly" }
            check(Modifier.isPrivate(ownerState.modifiers)) {
                "MatchOwner protocol state field must be private"
            }

            val rawAccessors =
                MatchOwner::class.java.declaredMethods.filter {
                    it.returnType == stateType || stateType in it.parameterTypes
                }
            check(rawAccessors.isEmpty()) {
                "MatchOwner must not expose raw protocol state: ${rawAccessors.map { it.name }}"
            }

            val stateMutators =
                stateType.declaredMethods.filter { it.name.startsWith("observeOutbound") }
            check(stateMutators.isNotEmpty() && Modifier.isPrivate(stateType.modifiers)) {
                "OwnerProtocolState mutators must remain inside the private owner state type"
            }

            val escapedState =
                classes
                    .filter { it.name != MatchOwner::class.java.name }
                    .flatMap { type ->
                        val reflected =
                            Class.forName(
                                type.name,
                                false,
                                MatchOwner::class.java.classLoader,
                            )
                        val fields =
                            reflected.declaredFields
                                .filter { it.type == stateType }
                                .map { "${type.name}.${it.name}" }
                        val methods =
                            reflected.declaredMethods
                                .filter {
                                    it.returnType == stateType ||
                                        stateType in it.parameterTypes
                                }.map { "${type.name}.${it.name}()" }
                        fields + methods
                    }
            check(escapedState.isEmpty()) {
                "OwnerProtocolState escaped MatchOwner: $escapedState"
            }
        }

        test("interactive playback crosses as a bounded value") {
            val forbidden =
                PlaybackYield::class.java.declaredFields.mapNotNull { field ->
                    field.genericType.typeName
                        .takeIf {
                            "forge." in it ||
                                "wotc.mtgo.gre" in it ||
                                "CompletableFuture" in it ||
                                "Function" in it
                        }?.let { "${field.name}: $it" }
                }
            check(forbidden.isEmpty()) {
                "PlaybackYield retains a live or protocol field: ${forbidden.joinToString()}"
            }

            val reservationFields = GameBridge.BundleFrameReservation::class.java.declaredFields
            check(
                reservationFields.none {
                    it.type == GameEventCollector::class.java ||
                        it.type == InteractivePromptBridge::class.java
                },
            ) {
                "Bundle reservation retains a mutable producer: ${reservationFields.map { it.genericType.typeName }}"
            }

            PlaybackCutReason.entries.map { it.name } shouldBe
                listOf(
                    "LAND_PLAYED",
                    "SPELL_ABILITY_CAST",
                    "SPELL_RESOLVED",
                    "STACK_EXIT_COMPLETION",
                    "CARD_COUNTERS",
                    "PLAYER_POISONED",
                    "TURN_BEGAN",
                    "TURN_PHASE",
                    "ATTACKERS_DECLARED",
                    "BLOCKERS_DECLARED",
                    "COMBAT_ENDED",
                )
        }

        test("playback callback adapter cannot compile protocol output") {
            val adapterField =
                GamePlayback::class.java.declaredFields.singleOrNull {
                    it.type == InteractivePlaybackMaterializer::class.java
                }
            checkNotNull(adapterField) {
                "GamePlayback must route every cut through InteractivePlaybackMaterializer"
            }

            noClasses()
                .that()
                .haveNameMatching(
                    "${GamePlayback::class.java.name}|" +
                        "${InteractivePlaybackMaterializer::class.java.name}|" +
                        NaiveGsmActionCapture::class.java.name,
                ).should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                    "leyline.game.bundle..",
                    "com.google.protobuf..",
                    "wotc.mtgo.gre.external.messaging..",
                ).because(
                    "engine callbacks materialize values; only the serial owner compiles protocol output",
                ).check(classes)

            val forbiddenPlaybackFields =
                GamePlayback::class.java.declaredFields.filter { field ->
                    field.name == "queueLock" ||
                        field.genericType.typeName.contains("MessageCounter") ||
                        field.genericType.typeName.contains("GREToClientMessage") ||
                        field.genericType.typeName.contains("ConcurrentLinkedQueue")
                }
            check(forbiddenPlaybackFields.isEmpty()) {
                "GamePlayback retains protocol or queue state: ${forbiddenPlaybackFields.map { it.name }}"
            }

            check(
                GameBridge::class.java.declaredClasses.none { it.simpleName == "PlaybackRegistry" } &&
                    GameBridge::class.java.declaredMethods.none {
                        it.name == "playbackFor" || it.name == "getPlayback"
                    },
            ) {
                "GameBridge retains the numbered-playback registry"
            }

            val spectatorPumpFields =
                SpectatorSession::class.java.declaredFields.filter {
                    it.type.name.contains("ScheduledExecutor") ||
                        it.type.name.contains("ScheduledFuture")
                }
            check(spectatorPumpFields.isEmpty()) {
                "SpectatorSession retains polling state: ${spectatorPumpFields.map { it.name }}"
            }

            noClasses()
                .that()
                .haveFullyQualifiedName(MatchSession::class.java.name)
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName(GamePlayback::class.java.name)
                .because("interactive ordering comes from the engine-cut FIFO, not numbered-queue repair")
                .check(classes)
        }

        test("production priority waits require the owner drain seam") {
            val contextConstructors = SessionContext::class.java.declaredConstructors
            check(
                contextConstructors.none { constructor ->
                    constructor.parameterTypes.contentEquals(arrayOf(GameBridge::class.java))
                },
            ) {
                "SessionContext must not provide a direct-bridge await fallback"
            }
            check(
                SessionContext::class.java.declaredFields.any {
                    it.type == EngineCutAwaiter::class.java
                },
            ) {
                "SessionContext must carry an EngineCutAwaiter"
            }

            val awaitMethods =
                GameOps::class.java.declaredMethods.filter {
                    it.name == "awaitEnginePriority" ||
                        it.name == "awaitEnginePriorityWithTimeout"
                }
            check(awaitMethods.isNotEmpty() && awaitMethods.all { Modifier.isAbstract(it.modifiers) }) {
                "GameOps priority waits must be supplied by the serial owner: ${awaitMethods.map { it.name to it.modifiers }}"
            }

            val productionAwaiter =
                MatchSession::class.java.declaredFields.singleOrNull {
                    EngineCutAwaiter::class.java.isAssignableFrom(it.type)
                }
            checkNotNull(productionAwaiter) {
                "MatchSession must bind EngineCutAwaiter to its owner drain"
            }
            check(Modifier.isPrivate(productionAwaiter.modifiers)) {
                "MatchSession EngineCutAwaiter must remain private"
            }

            check(
                EngineCutAwaiter::class.java.declaredMethods
                    .map { it.name }
                    .toSet() ==
                    setOf("awaitPriority", "awaitPriorityWithTimeout", "awaitActionPriority"),
            ) {
                "Every production priority wait must cross the owner drain seam"
            }
        }
    })
