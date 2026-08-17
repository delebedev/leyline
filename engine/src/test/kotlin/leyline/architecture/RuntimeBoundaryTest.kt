package leyline.architecture

import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import leyline.UnitTag
import leyline.architecture.EngineArchitecture.kotlinName
import leyline.architecture.EngineArchitecture.named

/**
 * Enforces the runtime boundary direction defined by ADR 0014.
 *
 * Match orchestration may depend on immutable engine-facing values, but new
 * direct Forge dependencies must stay behind the engine boundary, and the
 * request and response entry points that the coordinator migration retired
 * must not grow back.
 *
 * Per-prompt-family ownership lives in [PromptRouteBoundaryTest]; projection
 * value boundaries live in [ValueProjectionBoundaryTest].
 */
class RuntimeBoundaryTest :
    FunSpec({

        tags(UnitTag)

        val classes = EngineArchitecture.mainClasses

        test("match Forge dependencies stay within the frozen migration allowlist") {
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

        test("retired session-owned members stay retired") {
            val survivors =
                retiredMembers.flatMap { (owner, retired) ->
                    val declared = declaredMemberNames(classes.get(owner))
                    retired.filter(declared::contains).map { "$owner.$it" }
                }

            withClue("members retired by the coordinator migration were reintroduced") {
                survivors.sorted().shouldBeEmpty()
            }
        }

        test("retired session and live-projection types stay retired") {
            withClue("types replaced by coordinator runtimes or materialized facts must not reappear") {
                classes
                    .map { it.simpleName }
                    .filter(retiredTypes::contains)
                    .sorted()
                    .shouldBeEmpty()
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

/**
 * Request builders and session response entry points that the coordinator
 * migration removed. Each moved into a window materializer or a coordinator
 * runtime; a reappearance means a prompt family grew a second owner.
 */
private val retiredMembers =
    mapOf(
        "leyline.match.TargetingHandler" to
            setOf(
                "sendOrderReq",
                "onOrderResp",
                "orderBundle",
                "sendGroupReqForSurveilScry",
                "resolveSurveilScryBundle",
                "onGroupResp",
            ),
        "leyline.game.bundle.RequestBuilder" to
            setOf(
                "buildSelectTargets",
                "buildOrderReq",
                "buildConvokeCostPayCostsReq",
                "buildImproviseCostPayCostsReq",
                "buildWaterbendCostPayCostsReq",
                "buildSacrificePayCostsReq",
                "buildSelectCostPayCostsReq",
                "buildStationTapCostPayCostsReq",
                "buildEnlistCostPayCostsReq",
                "buildTeamworkCostPayCostsReq",
            ),
        "leyline.game.bundle.BundleBuilder" to
            setOf(
                "finalizeAnnotations",
                "finalizeStateFrame",
                "annotationRiders",
                "buildOrderFrameLocked",
                "stagePendingOrderZoneMove",
            ),
        "leyline.game.state.GameBridge" to
            setOf(
                "recordLibraryArrangement",
                "pollLibraryArrangement",
                "pendingLibraryArrangements",
                "captureLegacyCombatCheckpoint",
                "captureAndPause",
            ),
        "leyline.game.GamePlayback" to setOf("pendingResolutionFrame"),
        "leyline.game.GamePlaybackKt" to setOf("shouldAwaitResolutionBoundary"),
        "leyline.match.MatchSession" to setOf("sendLegacyPromptState"),
        "leyline.match.StaticChoiceInteractionHandler" to setOf("staticOptionIds"),
        "leyline.match.RevealChoiceInteractionHandler" to setOf("activeReveal"),
        "leyline.match.ManaSourcePaymentHandler" to setOf("waterbendManaCost"),
        "leyline.bridge.coord.CostPaymentCoordinator" to setOf("recordConvokePayments"),
        "leyline.bridge.forge.TapPaymentPolicy" to setOf("currentPayCostsPromptSource"),
    )

/**
 * Types the coordinator migration and the functional-core split deleted.
 * A reappearance means live state was reopened behind a familiar name.
 */
private val retiredTypes =
    setOf(
        "SearchPromptInteractionHandler",
        "SourceAbilityResolverFactory",
        "TeamworkCost",
    )

/**
 * Declared field and method names of [javaClass], normalized back to their
 * Kotlin spelling: the `$module` suffix on `internal` members is dropped and
 * property accessors are reported under the property name.
 */
private fun declaredMemberNames(javaClass: com.tngtech.archunit.core.domain.JavaClass): Set<String> =
    (javaClass.fields.map { it.name } + javaClass.methods.map { it.name })
        .map { kotlinName(it) }
        .flatMap { name ->
            val stripped = name.removePrefix("get").removePrefix("set")
            if (stripped != name && stripped.isNotEmpty()) {
                listOf(name, stripped.replaceFirstChar(Char::lowercaseChar))
            } else {
                listOf(name)
            }
        }.toSet()
