package leyline.domain.service

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Tests for [EventRegistry.mapArenaFormat] — pure Arena → Forge format mapping.
 * Engine-dependent tests (resolve, validateDeck) live in [FormatValidationIntegrationTest].
 */
class FormatServiceTest :
    FunSpec({

        test("mapArenaFormat strips Traditional prefix") {
            assertSoftly {
                EventRegistry.mapArenaFormat("TraditionalStandard") shouldBe "Standard"
                EventRegistry.mapArenaFormat("TraditionalExplorer") shouldBe "Pioneer"
                EventRegistry.mapArenaFormat("TraditionalHistoric") shouldBe "Historic"
            }
        }

        test("mapArenaFormat maps Explorer to Pioneer") {
            EventRegistry.mapArenaFormat("Explorer") shouldBe "Pioneer"
        }

        test("mapArenaFormat returns null for unmapped formats") {
            assertSoftly {
                EventRegistry.mapArenaFormat("Timeless").shouldBeNull()
                EventRegistry.mapArenaFormat("Alchemy").shouldBeNull()
                EventRegistry.mapArenaFormat("TraditionalTimeless").shouldBeNull()
            }
        }

        test("mapArenaFormat passes through base formats") {
            EventRegistry.mapArenaFormat("Standard") shouldBe "Standard"
            EventRegistry.mapArenaFormat("Historic") shouldBe "Historic"
        }
    })
