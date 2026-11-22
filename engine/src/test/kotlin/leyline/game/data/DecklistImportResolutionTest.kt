package leyline.game.data

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.domain.DeckCard
import leyline.domain.deck.parseDecklist
import leyline.domain.deck.resolveCards

/**
 * Import lines the Web decklist route must accept: export suffixes, printing
 * hints that the catalog does not carry, and multi-face names. Resolution goes
 * through the same repository seam the Web import route supplies.
 */
class DecklistImportResolutionTest :
    FunSpec({
        tags(UnitTag)

        test("exported lines with printing suffixes and multi-face names resolve to their cards") {
            val repo = ForgeCardRepository.open()
            val cards =
                repo.resolveDecklistText(
                    """
                    1 Lightning Bolt (M10) 146
                    1 The Ur-Dragon (SLD) 11 *F*
                    1 Arcane Signet (SLD) 1492★
                    1 Necron Deathmark (40K) 42★
                    1 Skullclamp (PLST) C17-222
                    1 Kaya's Ghostform (plst) WAR-94
                    1 Etali, Primal Conqueror / Etali, Primal Sickness (MOM)137
                    1 Fell the Profane / Fell Mire
                    1 Ojer Kaslem, Deepest Growth / Temple of Cultivation
                    1 Fire/Ice
                    1 Fire//Ice
                    1 Fire / Ice
                    1 Insult/Injury
                    """.trimIndent(),
                )

            assertSoftly {
                cards.mainDeck.map { it.quantity } shouldContainExactly List(13) { 1 }
                repo.namesOf(cards.mainDeck) shouldContainExactly
                    listOf(
                        "Lightning Bolt",
                        "The Ur-Dragon",
                        "Arcane Signet",
                        "Necron Deathmark",
                        "Skullclamp",
                        "Kaya's Ghostform",
                        "Etali, Primal Conqueror",
                        "Fell the Profane",
                        "Ojer Kaslem, Deepest Growth",
                        "Fire // Ice",
                        "Fire // Ice",
                        "Fire // Ice",
                        "Insult // Injury",
                    )
                cards.sideboard shouldBe emptyList()
                cards.commandZone shouldBe emptyList()
            }
        }

        test("Forge .dck text parses metadata and pipe printing details into the same resolution") {
            val repo = ForgeCardRepository.open()
            val cards =
                repo.resolveDecklistText(
                    """
                    [metadata]
                    Name=Import Fixture
                    Format=Commander

                    [Commander]
                    1 Atraxa, Praetors' Voice|2XM|219

                    [Main]
                    1 Sol Ring|C21|263
                    1 Fire // Ice|ICE|1

                    [Sideboard]
                    1 Negate|M10|55
                    """.trimIndent(),
                )

            assertSoftly {
                repo.namesOf(cards.commandZone) shouldContainExactly listOf("Atraxa, Praetors' Voice")
                repo.namesOf(cards.mainDeck) shouldContainExactly listOf("Sol Ring", "Fire // Ice")
                repo.namesOf(cards.sideboard) shouldContainExactly listOf("Negate")
            }
        }
    })

/** The lookup the Web import route supplies to [resolveCards]. */
private fun ForgeCardRepository.resolveDecklistText(text: String) =
    parseDecklist(text).resolveCards { name, setCode ->
        setCode?.let { findDeckGrpIdByNameAndSet(name, it) } ?: findDeckGrpIdByName(name)
    }

private fun ForgeCardRepository.namesOf(cards: List<DeckCard>): List<String> = cards.map { requireNotNull(findNameByGrpId(it.grpId)) }
