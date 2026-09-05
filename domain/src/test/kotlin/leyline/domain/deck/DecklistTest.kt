package leyline.domain.deck

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class DecklistTest :
    FunSpec({

        test("parses a plain bundled decklist") {
            val decklist = parseDecklist("24 Forest\n36 Grizzly Bears")

            assertSoftly {
                decklist.entries shouldHaveSize 2
                decklist.entries[0] shouldBe DecklistEntry(DecklistSection.Main, 24, "Forest")
                decklist.entries[1] shouldBe DecklistEntry(DecklistSection.Main, 36, "Grizzly Bears")
            }
        }

        test("parses set code and collector number suffix") {
            val entry = parseDecklist("4 Hallowed Priest (ANB) 9").entries.single()

            assertSoftly {
                entry.name shouldBe "Hallowed Priest"
                entry.setCode shouldBe "ANB"
                entry.quantity shouldBe 4
            }
        }

        test("parses x quantities and optional printing details") {
            val entries =
                parseDecklist(
                    """
                    1x High Tide (FEM) 18a
                    1 Control Magic (ARENA)
                    """.trimIndent(),
                ).entries

            assertSoftly {
                entries[0] shouldBe DecklistEntry(DecklistSection.Main, 1, "High Tide", "FEM")
                entries[1] shouldBe DecklistEntry(DecklistSection.Main, 1, "Control Magic", "ARENA")
            }
        }

        test("routes bare label section headers, including Deck and Main") {
            val decklist =
                parseDecklist(
                    """
                    Deck
                    4 Lightning Bolt
                    Sideboard
                    2 Negate
                    Commander
                    1 Atraxa, Praetors' Voice
                    Companion
                    1 Lurrus of the Dream-Den
                    Main
                    1 Mountain
                    """.trimIndent(),
                )

            val bySection = decklist.entries.associate { it.name to it.section }
            assertSoftly {
                bySection["Lightning Bolt"] shouldBe DecklistSection.Main
                bySection["Negate"] shouldBe DecklistSection.Sideboard
                bySection["Atraxa, Praetors' Voice"] shouldBe DecklistSection.Commander
                bySection["Lurrus of the Dream-Den"] shouldBe DecklistSection.Companion
                bySection["Mountain"] shouldBe DecklistSection.Main
            }
        }

        test("routes bracketed section headers") {
            val decklist = parseDecklist("[Commander]\n1 Empress Galina\n[Deck]\n99 Island\n[Sideboard]\n1 Negate")

            decklist.entries.map { it.section } shouldBe
                listOf(DecklistSection.Commander, DecklistSection.Main, DecklistSection.Sideboard)
        }

        test("parses a representative bundled deck header sequence") {
            // Matches the real data/decks/*.txt shape: bare `Deck`, main-deck lines with
            // an Arena export suffix, then bare `Sideboard`.
            val decklist =
                parseDecklist(
                    """
                    Deck
                    4 Lightning Bolt (M10) 146
                    Sideboard
                    2 Negate (M10) 55
                    """.trimIndent(),
                )

            val bySection = decklist.entries.associate { it.name to it.section }
            assertSoftly {
                decklist.entries shouldHaveSize 2
                bySection["Lightning Bolt"] shouldBe DecklistSection.Main
                bySection["Negate"] shouldBe DecklistSection.Sideboard
            }
        }

        test("skips blank lines and comments") {
            val decklist =
                parseDecklist(
                    """
                    # a comment
                    4 Lightning Bolt

                    ; another comment
                    // yet another
                    """.trimIndent(),
                )

            decklist.entries shouldHaveSize 1
        }

        test("rejects a card line without a leading quantity") {
            val ex = shouldThrow<DecklistException> { parseDecklist("Lightning Bolt") }

            ex.errors shouldHaveSize 1
        }

        test("rejects an unknown bracket header instead of silently routing to Main") {
            val ex = shouldThrow<DecklistException> { parseDecklist("[Maybeboard]\n4 Lightning Bolt") }

            ex.errors shouldHaveSize 1
        }

        test("rejects an unmatched section bracket") {
            val ex = shouldThrow<DecklistException> { parseDecklist("[Deck\n4 Lightning Bolt") }

            ex.errors shouldHaveSize 1
        }

        test("rejects a zero quantity line") {
            val ex = shouldThrow<DecklistException> { parseDecklist("0 Lightning Bolt") }

            ex.errors shouldHaveSize 1
        }

        test("rejects blank input") {
            shouldThrow<DecklistException> { parseDecklist("   \n  \n") }
        }

        test("resolveCards buckets by section into DeckCards") {
            val decklist =
                parseDecklist(
                    """
                    4 Lightning Bolt
                    Sideboard
                    2 Negate
                    """.trimIndent(),
                )

            val cards = decklist.resolveCards { name, _ -> if (name == "Lightning Bolt") 1 else 2 }

            cards.mainDeck.map { it.grpId to it.quantity } shouldBe listOf(1 to 4)
            cards.sideboard.map { it.grpId to it.quantity } shouldBe listOf(2 to 2)
        }

        test("resolveCards aggregates every unresolved entry and rejects the whole deck") {
            val decklist = parseDecklist("4 Made Up Card\n2 Also Made Up")

            val ex = shouldThrow<DecklistException> { decklist.resolveCards { _, _ -> null } }

            ex.errors shouldHaveSize 2
        }
    })
