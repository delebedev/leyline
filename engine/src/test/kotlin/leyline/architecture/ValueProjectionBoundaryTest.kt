package leyline.architecture

import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.architecture.EngineArchitecture.named
import leyline.bridge.types.ForgeCardId
import leyline.game.data.CardProtoBuilder
import leyline.game.event.GameEvent
import leyline.game.mapping.MatchProjectionConfig
import leyline.game.mapping.ProjectionCardReferences
import leyline.game.mapping.StateFrameInput
import leyline.game.mapping.StateProjectionEnvironment
import leyline.game.state.AbilityExhaustionFacts
import leyline.game.state.MechanicSourceFacts
import leyline.game.state.PersistentFeedFacts

/**
 * Enforces the functional-core boundary of ADR 0015 for each family of
 * projection facts, one row per family.
 *
 * A family's value types and reducers read materialized facts: no Forge, no
 * [leyline.game.state.GameBridge], and nothing from `leyline.bridge` beyond the
 * identifier value types. The shell that materializes those facts is a named,
 * closed set of call sites — the rule fails when a fourth caller appears, not
 * when a file is renamed.
 *
 * Adding a family is adding a row. Each row emits its own test so a failure
 * names the family that broke.
 */
class ValueProjectionBoundaryTest :
    FunSpec({

        tags(UnitTag)

        val classes = EngineArchitecture.mainClasses
        val gameBridge = named("leyline.game.state.GameBridge")

        projectionFamilies.forEach { family ->
            test("${family.name} projection depends only on value types") {
                val scope = named(family.valueTypes)

                noClasses()
                    .that()
                    .haveNameMatching(scope)
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("forge..")
                    .because("${family.name} reduction consumes immutable facts")
                    .check(classes)

                noClasses()
                    .that()
                    .haveNameMatching(scope)
                    .should()
                    .dependOnClassesThat()
                    .haveNameMatching(gameBridge)
                    .because("the live bridge stays in the shell that materializes ${family.name} facts")
                    .check(classes)

                noClasses()
                    .that()
                    .haveNameMatching(named(family.identifierOnlyTypes ?: family.valueTypes))
                    .should()
                    .dependOnClassesThat(
                        resideInAPackage("leyline.bridge..").and(resideOutsideOfPackage("leyline.bridge.types..")),
                    ).because("only value identifiers may cross from the bridge package")
                    .check(classes)
            }

            family.stateFrameField?.let { (fieldName, fieldType) ->
                test("state frame carries the ${family.name} value") {
                    StateFrameInput::class.java.getDeclaredField(fieldName).type shouldBe fieldType
                }
            }

            family.captureObject?.let { captureObject ->
                test("only the named projection shells materialize ${family.name} facts") {
                    methods()
                        .that()
                        .areDeclaredInClassesThat()
                        .haveNameMatching(named(captureObject))
                        .and()
                        .haveName("capture")
                        .should()
                        .onlyBeCalled()
                        .byClassesThat()
                        .haveNameMatching(named(family.captureCallers))
                        .because("${family.name} facts are materialized once per frame, by the shell")
                        .check(EngineArchitecture.productionAndHarnessClasses)
                }
            }
        }

        test("state projection environment contains only stable reference data and match config") {
            StateProjectionEnvironment::class.java.declaredFields
                .filterNot { it.isSynthetic }
                .associate { it.name to it.type } shouldBe
                mapOf(
                    "cardProto" to CardProtoBuilder::class.java,
                    "matchConfig" to MatchProjectionConfig::class.java,
                    "cardReferences" to ProjectionCardReferences::class.java,
                )
        }

        test("Paradigm event facts carry value source identity") {
            GameEvent.SpellCast::class.java.getDeclaredField("paradigmSourceCardId").type shouldBe
                ForgeCardId::class.java
            GameEvent.SpellResolved::class.java.getDeclaredField("paradigmSourceCardId").type shouldBe
                ForgeCardId::class.java
        }

        test("shell materializers use the narrow stable-reference surface") {
            noClasses()
                .that()
                .haveNameMatching(
                    named(
                        "leyline.game.bundle.PersistentFeedFactsCapture",
                        "leyline.game.bundle.CombatQualificationFactsCapture",
                    ),
                ).should()
                .dependOnClassesThat()
                .haveNameMatching(named("leyline.game.data.CardRepository"))
                .because("fact materializers read projected card references, not the repository")
                .check(classes)
        }

        test("retired live-projection resolvers stay retired") {
            val retired = setOf("SourceAbilityResolverFactory", "TeamworkCost")
            withClue("live-projection types that were replaced by materialized facts must not reappear") {
                classes.map { it.simpleName }.filter(retired::contains) shouldBe emptyList()
            }
        }
    })

/**
 * One family of projection facts.
 *
 * [identifierOnlyTypes] narrows the "identifiers only from `leyline.bridge`"
 * rule when a family's builders legitimately see more of the bridge than its
 * fact value does; it defaults to the whole family.
 */
private data class ProjectionFamily(
    val name: String,
    val valueTypes: List<String>,
    val identifierOnlyTypes: List<String>? = null,
    val stateFrameField: Pair<String, Class<*>>? = null,
    val captureObject: String? = null,
    val captureCallers: List<String> = emptyList(),
)

private const val STATE = "leyline.game.state"
private const val ANNOTATIONS = "leyline.game.annotations"
private const val MAPPING = "leyline.game.mapping"
private const val SNAPSHOT = "leyline.game.snapshot"
private const val CAPTURE = "leyline.game.bundle"

/** The shell call sites allowed to materialize a fact value for a live frame. */
private val productionShells =
    listOf(
        "leyline.game.bundle.BundleBuilder",
        "leyline.protocol.HandshakeMessages",
    )

/** Harness equivalents of [productionShells], used by the headless match runtime. */
private val harnessShells =
    listOf(
        "leyline.game.TestHelpersKt",
        "leyline.tooling.headless.MatchFlowHarness",
    )

private val projectionFamilies =
    listOf(
        ProjectionFamily(
            name = "ability exhaustion",
            valueTypes =
                listOf(
                    "$STATE.AbilityExhaustionFacts",
                    "$ANNOTATIONS.AbilityExhaustionProjectionKt",
                ),
            stateFrameField = "abilityExhaustionFacts" to AbilityExhaustionFacts::class.java,
            captureObject = "$CAPTURE.AbilityExhaustionFactsCapture",
            captureCallers = productionShells + harnessShells,
        ),
        ProjectionFamily(
            name = "mechanic source",
            valueTypes =
                listOf(
                    "$STATE.MechanicSourceFacts",
                    "$ANNOTATIONS.MechanicSourceProjection",
                ),
            stateFrameField = "mechanicSourceFacts" to MechanicSourceFacts::class.java,
            captureObject = "$CAPTURE.MechanicSourceFactsCapture",
            captureCallers = productionShells + harnessShells,
        ),
        ProjectionFamily(
            name = "persistent feed",
            valueTypes =
                listOf(
                    "$MAPPING.PersistentFeedBuilder",
                    "$MAPPING.PersistentAbilityWordFeedBuilder",
                    "$MAPPING.PersistentTemporaryFeedBuilder",
                    "$MAPPING.FrameIdResolver",
                    "$STATE.PersistentFeedFacts",
                ),
            identifierOnlyTypes = listOf("$STATE.PersistentFeedFacts"),
            stateFrameField = "persistentFeedFacts" to PersistentFeedFacts::class.java,
            captureObject = "$CAPTURE.PersistentFeedFactsCapture",
            captureCallers = productionShells + harnessShells,
        ),
        ProjectionFamily(
            name = "state-zone semantics",
            valueTypes =
                listOf(
                    "$MAPPING.StateZoneProjection",
                    "$MAPPING.StateProjectionEnvironment",
                    "$MAPPING.MatchProjectionConfig",
                    "$SNAPSHOT.CardSnapshot",
                    "$SNAPSHOT.StackEntry",
                    "$ANNOTATIONS.ZoneTransferDetector",
                    "$ANNOTATIONS.ZoneTransferContext",
                    "$ANNOTATIONS.CombatAnnotations",
                ),
        ),
        ProjectionFamily(
            name = "synthetic effects",
            valueTypes =
                listOf(
                    "$STATE.EffectProjectionFacts",
                    "$STATE.EffectTracker",
                    "$STATE.EarthbendTracker",
                    "$ANNOTATIONS.MechanicAnnotations",
                    "$ANNOTATIONS.VehicleAttachContributor",
                    "$ANNOTATIONS.EarthbendEmitter",
                ),
        ),
    )
