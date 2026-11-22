package leyline.architecture

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage
import com.tngtech.archunit.core.domain.JavaMethodCall
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import io.kotest.core.spec.style.FunSpec
import leyline.UnitTag
import leyline.architecture.EngineArchitecture.kotlinName
import leyline.architecture.EngineArchitecture.named

/**
 * Keeps host-selected settings in projection.
 *
 * An embedding host may choose how the same game is presented: Forge-text cost
 * labels ([leyline.config.CostChoicePresentation]) or effective keyword rows
 * ([leyline.config.KeywordProjection]). Rules, action offers, prompt routing
 * and Copilot decisions must stay identical across hosts, so only snapshot
 * building, object mapping and bundle construction may read these values.
 *
 * Two rules cover the two ways a reader appears in bytecode: comparing against
 * an enum constant depends on the type, while forwarding the value only calls
 * its [leyline.config.EngineSettings] getter.
 */
class HostOptInBoundaryTest :
    FunSpec({
        tags(UnitTag)

        // Production output only: hosts and the headless harness select these settings, the engine reads them.
        val classes = EngineArchitecture.mainClasses
        val outsideProjection =
            DescribedPredicate.not(resideInAnyPackage(*projectionPackages, "leyline.config.."))

        test("only projection packages depend on host opt-in types") {
            noClasses()
                .that(outsideProjection)
                .should()
                .dependOnClassesThat()
                .haveNameMatching(named(hostOptInTypes))
                .because("host opt-ins change presentation, never rules, offers, prompts or decisions")
                .check(classes)
        }

        test("only projection packages read host opt-ins from engine settings") {
            noClasses()
                .that(outsideProjection)
                .should()
                .callMethodWhere(hostOptInGetter)
                .because("forwarding a host opt-in out of projection would let it reach a decision path")
                .check(classes)
        }
    })

private val projectionPackages =
    arrayOf(
        "leyline.game.snapshot..",
        "leyline.game.mapping..",
        "leyline.game.bundle..",
    )

private val hostOptInTypes =
    listOf(
        "leyline.config.CostChoicePresentation",
        "leyline.config.KeywordProjection",
    )

private val hostOptInGetter =
    object : DescribedPredicate<JavaMethodCall>("read a host opt-in from EngineSettings") {
        override fun test(call: JavaMethodCall): Boolean =
            call.targetOwner.name == "leyline.config.EngineSettings" &&
                kotlinName(call.target.name) in setOf("getCostChoicePresentation", "getKeywordProjection")
    }
