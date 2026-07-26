package leyline.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import io.kotest.core.spec.style.FunSpec
import leyline.UnitTag
import leyline.bridge.handoff.DamageAssignmentPrompt
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.NumericInputPrompt
import leyline.bridge.handoff.OptionalActionPrompt
import leyline.bridge.types.PriorityActionFacts
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
    })
