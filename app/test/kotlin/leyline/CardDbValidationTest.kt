package leyline

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import java.io.File

class CardDbValidationTest :
    FunSpec({

        tags(UnitTag)

        test("card DB validation rejects missing file") {
            val missing =
                File(
                    System.getProperty("java.io.tmpdir"),
                    "leyline-missing-card-db-${System.nanoTime()}.sqlite",
                )

            val thrown =
                shouldThrow<IllegalArgumentException> {
                    validateCardDbFile(missing)
                }

            thrown.message shouldContain "Card database not found"
        }

        test("card DB validation rejects placeholder-sized file") {
            val placeholder =
                File.createTempFile("leyline-card-db-placeholder-", ".sqlite").apply {
                    deleteOnExit()
                    writeText("placeholder")
                }

            val thrown =
                shouldThrow<IllegalArgumentException> {
                    validateCardDbFile(placeholder)
                }

            thrown.message shouldContain "too small to be a real DB"
        }

        test("card DB validation rejects DBs with no usable card rows") {
            val thrown =
                shouldThrow<IllegalStateException> {
                    requireUsableCardRows("card-db.sqlite", emptyList())
                }

            thrown.message shouldContain "no usable Cards rows"
        }
    })
