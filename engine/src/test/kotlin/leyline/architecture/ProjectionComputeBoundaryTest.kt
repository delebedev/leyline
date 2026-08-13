package leyline.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.game.mapping.StateMapper
import leyline.game.state.GameBridge

class ProjectionComputeBoundaryTest :
    FunSpec({
        tags(UnitTag)

        val classes =
            ClassFileImporter()
                .withImportOption(ImportOption.DoNotIncludeTests())
                .importPackages("leyline.game")
        val computeTypes =
            listOf(
                "leyline.game.mapping.StateMapper",
                "leyline.game.annotations.AnnotationContext",
                "leyline.game.annotations.AnnotationPipeline",
                "leyline.game.annotations.ZoneTransferAdapter",
                "leyline.game.mapping.ZoneMapper",
                "leyline.game.mapping.LinkedFaceCompanionProjector",
                "leyline.game.annotations.RevealStateContributor",
            )
        val computePattern = namedTypes(computeTypes)

        test("state projection compute has no GameBridge dependency") {
            noClasses()
                .that()
                .haveNameMatching(computePattern)
                .should()
                .dependOnClassesThat()
                .haveNameMatching("leyline\\.game\\.state\\.GameBridge(\\$.*)?")
                .because("the shell supplies immutable inputs and StateMapper owns its private editor")
                .check(classes)
        }

        test("projection helpers with value-only topology have no Forge dependency") {
            noClasses()
                .that()
                .haveNameMatching(namedTypes(computeTypes - "leyline.game.mapping.StateMapper"))
                .should()
                .dependOnClassesThat()
                .resideInAPackage("forge..")
                .because("only the snapshot phase value remains at the outer mapper boundary")
                .check(classes)
        }

        test("StateMapper public projection entrypoints accept explicit values") {
            StateMapper::class.java.declaredMethods
                .filter { it.name == "buildFromSnapshot" || it.name == "buildDiff" }
                .flatMap { it.parameterTypes.toList() }
                .filter { it == GameBridge::class.java }
                .shouldBeEmpty()
        }

        test("mapper-only active editor accessors are absent from GameBridge") {
            val prohibited =
                setOf(
                    "activeRevealProxies",
                    "activeOpponentKnowledgeState",
                    "activePersistentAnnotationState",
                    "activeHolderRecords",
                    "activeEffectPlanner",
                    "applyHolderBatch",
                    "applyProjectionHistory",
                )
            GameBridge::class.java.declaredMethods
                .map { it.name }
                .filter(prohibited::contains)
                .shouldBeEmpty()
            classes.any { it.name == "leyline.game.mapping.ProjectionCompiler" } shouldBe false
        }
    })

private fun namedTypes(names: List<String>): String = "(" + names.joinToString("|") { Regex.escape(it) } + ")(\\$.*)?"
