package leyline.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import java.io.File

class ForgeFlavorNameAliasesTest :
    FunSpec({
        tags(UnitTag)

        test("parses Universes Within flavor-name aliases per card face") {
            val cardFile =
                File.createTempFile("leyline-flavor-name-alias-", ".txt").apply {
                    deleteOnExit()
                    writeText(
                        """
                        Name:Gwen Stacy
                        Variant:UniversesWithin:FlavorName:Nia, Skysail Storyteller
                        ManaCost:1 R

                        ALTERNATE

                        Name:Ghost-Spider
                        Variant:UniversesWithin:FlavorName:Nia, Fabled Skyclimber
                        ManaCost:2 U R W
                        """.trimIndent(),
                    )
                }

            ForgeFlavorNameAliases.parseFile(cardFile) shouldContainExactly
                mapOf(
                    "Gwen Stacy" to "Nia, Skysail Storyteller",
                    "Ghost-Spider" to "Nia, Fabled Skyclimber",
                )
        }

        test("resolver uses flavor-name alias only after exact lookup misses") {
            val names = mapOf("Detect Intrusion" to 97862, "Spider-Sense" to 99999)
            val resolver =
                ImportedCardNameResolver(
                    findByName = { names[it] },
                    findByNameAndSet = { name, set -> if (name == "Spider-Sense" && set == "SPM") 99999 else null },
                    flavorNameAliases = mapOf("Spider-Sense" to "Detect Intrusion"),
                )

            resolver.resolve("Spider-Sense", "SPM") shouldBe 99999
        }

        test("resolver falls back to flavor-name alias for imported display names") {
            val resolver =
                ImportedCardNameResolver(
                    findByName = { name -> if (name == "Detect Intrusion") 97862 else null },
                    findByNameAndSet = { _, _ -> null },
                    flavorNameAliases = mapOf("Spider-Sense" to "Detect Intrusion"),
                )

            resolver.resolve("Spider-Sense", "SPM") shouldBe 97862
        }
    })
