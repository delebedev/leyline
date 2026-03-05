package leyline.frontdoor.service

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldNotBeBlank
import leyline.IntegrationTag
import leyline.bridge.DeckLoader
import leyline.bridge.GameBootstrap

/**
 * Integration tests for [FormatService] deck validation with real card data.
 * Requires full card database — runs under [IntegrationTag].
 */
class FormatValidationIntegrationTest :
    FunSpec({

        tags(IntegrationTag)

        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
        }

        // Nykthos, Shrine to Nyx — Theros (THS), legal in Pioneer/Explorer, not Standard.
        val nonStandardDeck = """
            4 Nykthos, Shrine to Nyx
            56 Forest
        """.trimIndent()

        test("Standard rejects a card not in Standard") {
            val deck = DeckLoader.parseDeckList(nonStandardDeck)
            val error = FormatService.validateDeck(deck, "Standard")
            error.shouldNotBeNull()
            error.shouldNotBeBlank()
        }

        test("same card is accepted in Pioneer") {
            val deck = DeckLoader.parseDeckList(nonStandardDeck)
            val error = FormatService.validateDeck(deck, "Pioneer")
            error.shouldBeNull()
        }

        test("resolve throws for unknown format") {
            shouldThrow<IllegalStateException> {
                FormatService.resolve("Nonexistent")
            }
        }

        test("mapArenaFormat + resolve round-trip") {
            val format = FormatService.resolve(
                FormatService.mapArenaFormat("TraditionalStandard"),
            )
            format.shouldNotBeNull()
        }
    })
