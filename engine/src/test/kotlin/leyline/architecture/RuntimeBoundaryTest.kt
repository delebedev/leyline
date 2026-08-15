package leyline.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import io.kotest.core.spec.style.FunSpec
import leyline.UnitTag
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

        val sourceRoot =
            sequenceOf(
                cwd.resolve("src/main/kotlin"),
                cwd.resolve("engine/src/main/kotlin"),
            ).first { it.resolve("leyline").toFile().isDirectory }

        test("match Forge imports stay within the frozen migration allowlist") {
            // This is an upper bound, not an expected-equality snapshot: existing
            // imports may disappear without editing the test, while any new
            // importing file fails. Remove names only after their imports are gone.
            val allowedViolators =
                setOf(
                    "AutoPassEngine.kt",
                    "SessionContext.kt",
                    "MatchSession.kt",
                    "CombatHandler.kt",
                    "DeferredCastCostInteractionHandler.kt",
                    "OptionalActionHandler.kt",
                    "PuzzleHandler.kt",
                )
            val matchRoot = sourceRoot.resolve("leyline/match")
            val violators = mutableSetOf<String>()
            val stream = Files.walk(matchRoot)
            try {
                stream
                    .filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                    .forEach { file ->
                        if (Files.readAllLines(file).any { it.trim().startsWith("import forge.") }) {
                            violators += matchRoot.relativize(file).toString()
                        }
                    }
            } finally {
                stream.close()
            }

            check(violators.all { it in allowedViolators }) {
                "Unexpected Forge imports in leyline.match: " +
                    (violators - allowedViolators).sorted().joinToString()
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

        test("blocking interaction materialization is value-only") {
            noClasses()
                .that()
                .haveNameMatching("leyline\\.game\\.bundle\\.BlockingInteractionMaterializer(\\$.*)?")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("forge..")
                .check(classes)

            noClasses()
                .that()
                .haveNameMatching("leyline\\.game\\.bundle\\.BlockingInteractionMaterializer(\\$.*)?")
                .should()
                .dependOnClassesThat()
                .haveNameMatching("leyline\\.game\\.state\\.GameBridge(\\$.*)?")
                .check(classes)
        }

        test("targeting interaction materialization and handoff values stay value-only") {
            noClasses()
                .that()
                .haveNameMatching("leyline\\.game\\.bundle\\.TargetingWindowMaterializer(\\$.*)?")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("forge..")
                .check(classes)

            noClasses()
                .that()
                .haveNameMatching("leyline\\.game\\.bundle\\.TargetingWindowMaterializer(\\$.*)?")
                .should()
                .dependOnClassesThat()
                .haveNameMatching("leyline\\.game\\.state\\.GameBridge(\\$.*)?")
                .check(classes)

            noClasses()
                .that()
                .haveNameMatching("leyline\\.bridge\\.handoff\\.Targeting(WindowValue|CandidateValue|CommandReceipt)(\\$.*)?")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("forge..")
                .check(classes)
        }

        test("targeting projection is owned by the match runtime") {
            val handler = Files.readString(sourceRoot.resolve("leyline/match/TargetingHandler.kt"))
            listOf(
                "PendingClientInteraction.TargetSelection",
                "SpellAbility",
                ".canTarget(",
                "prepareTargetingWindow",
                "buildSelectTargets",
                "queuePendingSubmittedTargets",
            ).forEach { forbidden -> check(forbidden !in handler) }

            val requestBuilder = Files.readString(sourceRoot.resolve("leyline/game/bundle/RequestBuilder.kt"))
            check("buildSelectTargets" !in requestBuilder)

            val bridge = Files.readString(sourceRoot.resolve("leyline/bridge/handoff/InteractivePromptBridge.kt"))
            check("request.route is ResolvedPromptRoute.Targeting" in bridge)
            check("targetingSa != null" !in bridge)

            val producer = Files.readString(sourceRoot.resolve("leyline/bridge/coord/TargetingCoordinator.kt"))
            check("PromptRouteResolver.resolve(PromptSemantic.TargetSelection)" in producer)

            val owners = mutableSetOf<String>()
            val stream = Files.walk(sourceRoot.resolve("leyline"))
            try {
                stream
                    .filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                    .forEach { file ->
                        if ("prepareTargetingWindow(" in Files.readString(file)) {
                            owners += sourceRoot.relativize(file).toString()
                        }
                    }
            } finally {
                stream.close()
            }
            check(
                owners ==
                    setOf(
                        "leyline/bridge/coord/MatchTargetingInteractionRuntime.kt",
                        "leyline/game/bundle/BundleBuilder.kt",
                    ),
            ) { "Unexpected targeting-window preparers: ${owners.sorted()}" }
        }

        test("ordered-card windows are coordinator-owned and session responses stay value-only") {
            noClasses()
                .that()
                .haveNameMatching("leyline\\.game\\.bundle\\.OrderWindowMaterializer(\\$.*)?")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("forge..")
                .check(classes)

            noClasses()
                .that()
                .haveNameMatching("leyline\\.bridge\\.handoff\\.Order(Window|Candidate|Move)Value(\\$.*)?")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("forge..")
                .check(classes)

            val sessionHandler = Files.readString(sourceRoot.resolve("leyline/match/OrderInteractionHandler.kt"))
            listOf("import forge.", "findCard(", "getZone(", "getForgeCardId(").forEach { forbidden ->
                check(forbidden !in sessionHandler)
            }
            val bridge = Files.readString(sourceRoot.resolve("leyline/bridge/handoff/InteractivePromptBridge.kt"))
            check("route !is ResolvedPromptRoute.Order" in bridge)
            val targeting = Files.readString(sourceRoot.resolve("leyline/match/TargetingHandler.kt"))
            listOf("sendOrderReq", "orderBundle", "fun onOrderResp").forEach { removed -> check(removed !in targeting) }
            val requestBuilder = Files.readString(sourceRoot.resolve("leyline/game/bundle/RequestBuilder.kt"))
            check("buildOrderReq" !in requestBuilder)

            val owners = mutableSetOf<String>()
            val stream = Files.walk(sourceRoot.resolve("leyline"))
            try {
                stream
                    .filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                    .forEach { file ->
                        if ("prepareOrderWindow(" in Files.readString(file)) {
                            owners += sourceRoot.relativize(file).toString()
                        }
                    }
            } finally {
                stream.close()
            }
            check(
                owners ==
                    setOf(
                        "leyline/bridge/coord/MatchOrderInteractionRuntime.kt",
                        "leyline/game/bundle/BundleBuilder.kt",
                    ),
            ) { "Unexpected Order-window preparers: ${owners.sorted()}" }
        }

        test("card-backed SelectN windows are coordinator-owned and session responses stay value-only") {
            noClasses()
                .that()
                .haveNameMatching("leyline\\.game\\.bundle\\.CardSelectWindowMaterializer(\\$.*)?")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("forge..")
                .check(classes)

            noClasses()
                .that()
                .haveNameMatching("leyline\\.bridge\\.handoff\\.CardSelect.*Value(\\$.*)?")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("forge..")
                .check(classes)

            val sessionHandler = Files.readString(sourceRoot.resolve("leyline/match/CardSelectInteractionHandler.kt"))
            listOf("import forge.", "findCard(", "getZone(", "getForgeCardId(").forEach { forbidden ->
                check(forbidden !in sessionHandler)
            }
            val bridge = Files.readString(sourceRoot.resolve("leyline/bridge/handoff/InteractivePromptBridge.kt"))
            check("route !is ResolvedPromptRoute.CardSelect" in bridge)
            val targeting = Files.readString(sourceRoot.resolve("leyline/match/TargetingHandler.kt"))
            check("CardSelect prompts must be published by MatchCardSelectInteractionRuntime" in targeting)
            val route = Files.readString(sourceRoot.resolve("leyline/bridge/handoff/PromptRoute.kt"))
            listOf(
                "SelectNLegendRule",
                "SelectNDiscard",
                "SelectNSacrificeEffect",
                "SuspectChoice",
                "MutateTopBottom",
                "SelectNLibraryPutback",
                "ManifestDread",
            ).forEach { semantic ->
                check(
                    "PromptSemantic.$semantic -> cardSelect" in route ||
                        "PromptSemantic.$semantic ->\n                cardSelect" in route,
                )
            }

            val owners = mutableSetOf<String>()
            val stream = Files.walk(sourceRoot.resolve("leyline"))
            try {
                stream
                    .filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                    .forEach { file ->
                        if ("prepareCardSelectWindow(" in Files.readString(file)) {
                            owners += sourceRoot.relativize(file).toString()
                        }
                    }
            } finally {
                stream.close()
            }
            check(
                owners ==
                    setOf(
                        "leyline/bridge/coord/MatchCardSelectInteractionRuntime.kt",
                        "leyline/game/bundle/BundleBuilder.kt",
                    ),
            ) { "Unexpected CardSelect-window preparers: ${owners.sorted()}" }
        }

        test("iterative mana-source payments are coordinator-owned and session values stay frozen") {
            noClasses()
                .that()
                .haveNameMatching("leyline\\.game\\.bundle\\.ManaSourcePaymentMaterializer(\\$.*)?")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("forge..")
                .check(classes)

            noClasses()
                .that()
                .haveNameMatching("leyline\\.bridge\\.handoff\\.ManaSourcePayment.*Value(\\$.*)?")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("forge..")
                .check(classes)

            val handler = Files.readString(sourceRoot.resolve("leyline/match/ManaSourcePaymentHandler.kt"))
            listOf(
                "import forge.",
                "findCard(",
                "getZone(",
                "waterbendManaCost",
                "ConvokePayments",
            ).forEach { forbidden -> check(forbidden !in handler) }

            val oneShot = Files.readString(sourceRoot.resolve("leyline/bridge/coord/MatchOneShotPayCostsRuntime.kt"))
            listOf("getZone(", "findCard(", "getForgeCardId(").forEach { forbidden -> check(forbidden !in oneShot) }

            val producer = Files.readString(sourceRoot.resolve("leyline/bridge/coord/CostPaymentCoordinator.kt"))
            check("recordConvokePayments" !in producer)

            val requestBuilder = Files.readString(sourceRoot.resolve("leyline/game/bundle/RequestBuilder.kt"))
            listOf(
                "buildConvokeCostPayCostsReq",
                "buildImproviseCostPayCostsReq",
                "buildWaterbendCostPayCostsReq",
            ).forEach { removed -> check(removed !in requestBuilder) }

            val owners = mutableSetOf<String>()
            val stream = Files.walk(sourceRoot.resolve("leyline"))
            try {
                stream
                    .filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                    .forEach { file ->
                        if ("prepareManaSourcePayment(" in Files.readString(file)) {
                            owners += sourceRoot.relativize(file).toString()
                        }
                    }
            } finally {
                stream.close()
            }
            check(
                owners ==
                    setOf(
                        "leyline/bridge/coord/MatchManaSourcePaymentRuntime.kt",
                        "leyline/game/bundle/BundleBuilder.kt",
                    ),
            ) { "Unexpected mana-source payment preparers: ${owners.sorted()}" }
        }

        test("one-shot PayCosts windows are coordinator-owned and session responses stay value-only") {
            noClasses()
                .that()
                .haveNameMatching("leyline\\.game\\.bundle\\.OneShotPayCostsMaterializer(\\$.*)?")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("forge..")
                .check(classes)

            noClasses()
                .that()
                .haveNameMatching("leyline\\.bridge\\.handoff\\.OneShotPayCosts.*Value(\\$.*)?")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("forge..")
                .check(classes)

            val handler = Files.readString(sourceRoot.resolve("leyline/match/ManaSourcePaymentHandler.kt"))
            listOf("import forge.", "findCard(", "getZone(", "getForgeCardId(").forEach { forbidden ->
                check(forbidden !in handler)
            }
            val bridge = Files.readString(sourceRoot.resolve("leyline/bridge/handoff/InteractivePromptBridge.kt"))
            check("route !is ResolvedPromptRoute.PayCosts" in bridge)
            val targeting = Files.readString(sourceRoot.resolve("leyline/match/TargetingHandler.kt"))
            check("PayCostsInteractionHandler" !in targeting)
            val tapPolicy = Files.readString(sourceRoot.resolve("leyline/bridge/forge/TapPaymentPolicy.kt"))
            check("game.stack" !in tapPolicy)
            check("currentPayCostsPromptSource" !in tapPolicy)
            val requestBuilder = Files.readString(sourceRoot.resolve("leyline/game/bundle/RequestBuilder.kt"))
            listOf(
                "buildSacrificePayCostsReq",
                "buildSelectCostPayCostsReq",
                "buildStationTapCostPayCostsReq",
                "buildEnlistCostPayCostsReq",
                "buildTeamworkCostPayCostsReq",
            ).forEach { removed -> check(removed !in requestBuilder) }

            val obsoleteTapSymbols = setOf("TeamworkCost", "TEAMWORK_TAP_COST", "buildTeamworkCostPayCostsReq")
            val residuals = mutableMapOf<String, MutableSet<String>>()
            val symbolStream = Files.walk(sourceRoot.resolve("leyline"))
            try {
                symbolStream
                    .filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                    .forEach { file ->
                        val contents = Files.readString(file)
                        val found = obsoleteTapSymbols.filterTo(mutableSetOf()) { it in contents }
                        if (found.isNotEmpty()) {
                            residuals[sourceRoot.relativize(file).toString()] = found
                        }
                    }
            } finally {
                symbolStream.close()
            }
            check(residuals.isEmpty()) { "Obsolete tap-payment symbols remain: $residuals" }

            val owners = mutableSetOf<String>()
            val stream = Files.walk(sourceRoot.resolve("leyline"))
            try {
                stream
                    .filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                    .forEach { file ->
                        if ("prepareOneShotPayCosts(" in Files.readString(file)) {
                            owners += sourceRoot.relativize(file).toString()
                        }
                    }
            } finally {
                stream.close()
            }
            check(
                owners ==
                    setOf(
                        "leyline/bridge/coord/MatchOneShotPayCostsRuntime.kt",
                        "leyline/game/bundle/BundleBuilder.kt",
                    ),
            ) { "Unexpected one-shot PayCosts preparers: ${owners.sorted()}" }
        }

        test("deferred cast cost handoff is value-only and session code does not rediscover abilities") {
            noClasses()
                .that()
                .haveNameMatching("leyline\\.bridge\\.handoff\\.DeferredCastCostPlan(\\$.*)?")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("forge..")
                .check(classes)

            noClasses()
                .that()
                .haveNameMatching("leyline\\.bridge\\.handoff\\.DeferredCastCostPlan(\\$.*)?")
                .should()
                .dependOnClassesThat()
                .haveNameMatching("leyline\\.game\\.state\\.GameBridge(\\$.*)?")
                .check(classes)

            val handler = Files.readString(sourceRoot.resolve("leyline/match/DeferredCastCostInteractionHandler.kt"))
            listOf(
                "import forge.",
                "getAllCastableAbilities",
                "findById",
                "setActivatingPlayer",
                "PlayerAction.CastSpell",
            ).forEach { forbidden -> check(forbidden !in handler) }
            check("deferredCostPlan" in handler)
            check("completeActionClaim" in handler)
        }

        test("puzzle setup stays inert until match publication ownership is registered") {
            val source = Files.readString(sourceRoot.resolve("leyline/game/state/GameBridge.kt"))
            val puzzleStart = source.substringAfter("fun startPuzzle(").substringBefore("fun resetForPuzzle(")

            check(puzzleStart.indexOf("applyPuzzleSafely(puzzle, g)") < puzzleStart.indexOf("registerPlaybackPipeline(g"))
            check(
                source
                    .substringAfter("private fun runWithTempControllers(")
                    .substringBefore("private fun registerPuzzleCards(")
                    .contains("interactionRuntime = setupBlockingInteractionRuntime"),
            )
            check(
                source
                    .substringAfter("private val setupBlockingInteractionRuntime")
                    .substringBefore("/** Rebind an unpublished puzzle")
                    .let { setup ->
                        "cutCoordinator" !in setup && "publish" !in setup && "commitProjection" !in setup
                    },
            )
        }

        test("coordinator refresh uses frozen action values and combat echo has no live action query") {
            val bundle = Files.readString(sourceRoot.resolve("leyline/game/bundle/BundleBuilder.kt"))
            val phase = bundle.substringAfter("private fun buildPhaseTransitionDiff(").substringBefore("/** Embed stripped-down actions")
            val echo = bundle.substringAfter("private fun prepareCombatEcho(").substringBefore("/**\n     * Declare-blockers bundle")

            check("if (priorityActions == null)" in phase)
            check("priorityActions ?:" in phase)
            check("ActionMapper.buildNaiveActions" !in echo)
            check("ActionMapper.buildProjectionFromSnapshot" !in echo)
            check("presentationActions" in echo)

            val targeting = Files.readString(sourceRoot.resolve("leyline/match/TargetingHandler.kt"))
            check("buildActions(" !in targeting)
            check("hasMeaningfulPriorityAction(actionWindow.actionId)" in targeting)
        }
    })
