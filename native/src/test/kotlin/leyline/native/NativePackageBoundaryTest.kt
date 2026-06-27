package leyline.native

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Path

class NativePackageBoundaryTest :
    FunSpec({
        tags(NativeTag)

        val cwd = Path.of("").toAbsolutePath()
        val buildDir =
            sequenceOf(
                cwd.resolve("build/classes"),
                cwd.resolve("native/build/classes"),
            ).first { it.resolve("kotlin/main/leyline/native").toFile().isDirectory }

        val classes =
            ClassFileImporter()
                .withImportOption(ImportOption.DoNotIncludeTests())
                .importPaths(
                    buildDir.resolve("kotlin/main"),
                    buildDir.resolve("java/main"),
                )

        test("native account, frontdoor, and matchdoor packages do not cross-depend") {
            noClasses()
                .that()
                .resideInAPackage("leyline.native.account..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("leyline.native.frontdoor..", "leyline.native.matchdoor..")
                .check(classes)

            noClasses()
                .that()
                .resideInAPackage("leyline.native.frontdoor..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("leyline.native.account..", "leyline.native.matchdoor..")
                .check(classes)

            noClasses()
                .that()
                .resideInAPackage("leyline.native.matchdoor..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("leyline.native.account..", "leyline.native.frontdoor..")
                .check(classes)
        }
    })
