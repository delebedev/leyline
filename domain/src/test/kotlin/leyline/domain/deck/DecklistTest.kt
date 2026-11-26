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

        test("accepts common mainboard labels and trailing colons") {
            for (header in listOf("Mainboard", "MAINBOARD:", "[Mainboard]", "[Mainboard]:")) {
                val decklist = parseDecklist("Commander:\n1 Empress Galina\n$header\n2 Island\n[Sideboard]:\n3 Negate")

                decklist.entries shouldBe
                    listOf(
                        DecklistEntry(DecklistSection.Commander, 1, "Empress Galina"),
                        DecklistEntry(DecklistSection.Main, 2, "Island"),
                        DecklistEntry(DecklistSection.Sideboard, 3, "Negate"),
                    )
            }
        }

        test("known card type categories preserve the current section") {
            val categories =
                listOf(
                    "Artifact",
                    "Artifacts",
                    "Battle",
                    "Battles",
                    "Creature",
                    "Creatures",
                    "Enchantment",
                    "Enchantments",
                    "Instant",
                    "Instants",
                    "Land",
                    "Lands",
                    "Planeswalker",
                    "Planeswalkers",
                    "Sorcery",
                    "Sorceries",
                )
            val text = "Sideboard\n" + categories.joinToString("\n") { "[$it]:\n2 Island" }
            val entries = parseDecklist(text).entries

            assertSoftly {
                entries shouldHaveSize categories.size
                entries.all { it.section == DecklistSection.Sideboard && it.quantity == 2 } shouldBe true
                parseDecklist("Creatures\n1 Grizzly Bears").entries.single().section shouldBe DecklistSection.Main
                parseDecklist("Creatures (12)\n1 Grizzly Bears").entries.single().section shouldBe DecklistSection.Main
            }
        }

        test("a category header or blank line after the commander starts the main deck") {
            for (separator in listOf("Creature", "Lands (36)", "")) {
                val entries =
                    parseDecklist("Commander (1)\n1 Empress Galina\n$separator\n1 Grizzly Bears\n36 Forest").entries

                entries.map { it.name to it.section } shouldBe
                    listOf(
                        "Empress Galina" to DecklistSection.Commander,
                        "Grizzly Bears" to DecklistSection.Main,
                        "Forest" to DecklistSection.Main,
                    )
            }
            assertSoftly {
                parseDecklist("Commander (1)\n1x Empress Galina (cmr) 1 [Commander{top}]\nCreature (1)\n1 Grizzly Bears")
                    .entries
                    .map { it.section } shouldBe listOf(DecklistSection.Commander, DecklistSection.Main)
                parseDecklist("Commander\n\n1 Empress Galina").entries.single().section shouldBe DecklistSection.Commander
                parseDecklist("Companion\n1 Lurrus of the Dream-Den\n\n1 Forest").entries.last().section shouldBe
                    DecklistSection.Main
            }
        }

        test("inline category tags are dropped and section tags move the card") {
            val entries =
                parseDecklist(
                    """
                    1 Ashling, the Limitless [Commander]
                    1x Abandoned Air Temple (tla) 386 [Land]
                    1 Adeline, Resplendent Cathar (pmid) 1p [Tokens]
                    1x Sol Ring (cmm) 410 *F* [Ramp,Artifact] ^Have,#37d67a^
                    1x Atraxa, Praetors' Voice (2xm) 190 [Commander{top}]
                    [Commander] 1 Lurrus of the Dream-Den
                    [Commander] Kenrith, the Returned King
                    1 The Ur-Dragon *CMDR*
                    1 Negate [Sideboard]
                    1 Not In Deck [Maybeboard]
                    """.trimIndent(),
                ).entries

            entries shouldBe
                listOf(
                    DecklistEntry(DecklistSection.Commander, 1, "Ashling, the Limitless"),
                    DecklistEntry(DecklistSection.Main, 1, "Abandoned Air Temple", "TLA"),
                    DecklistEntry(DecklistSection.Main, 1, "Adeline, Resplendent Cathar", "PMID"),
                    DecklistEntry(DecklistSection.Main, 1, "Sol Ring", "CMM"),
                    DecklistEntry(DecklistSection.Commander, 1, "Atraxa, Praetors' Voice", "2XM"),
                    DecklistEntry(DecklistSection.Commander, 1, "Lurrus of the Dream-Den"),
                    DecklistEntry(DecklistSection.Commander, 1, "Kenrith, the Returned King"),
                    DecklistEntry(DecklistSection.Commander, 1, "The Ur-Dragon"),
                    DecklistEntry(DecklistSection.Sideboard, 1, "Negate"),
                )
        }

        test("a line without a quantity is one copy") {
            val entries = parseDecklist("Fracture\narahbo, roar of the world\n1 Welcome to... // Jurassic Park").entries

            entries shouldBe
                listOf(
                    DecklistEntry(DecklistSection.Main, 1, "Fracture"),
                    DecklistEntry(DecklistSection.Main, 1, "arahbo, roar of the world"),
                    DecklistEntry(DecklistSection.Main, 1, "Welcome to... // Jurassic Park"),
                )
        }

        test("strips straight and curly quotes left around a name") {
            val entries =
                parseDecklist(
                    "1 Akki Lavarunner // Tok-Tok, Volcano Born\"\n1 \"Ben-Ben, Akki Hermit\"\n1 \u201CGrenzo, Dungeon Warden\"",
                ).entries

            entries.map { it.name } shouldBe
                listOf("Akki Lavarunner // Tok-Tok, Volcano Born", "Ben-Ben, Akki Hermit", "Grenzo, Dungeon Warden")
        }

        test("strips trailing tags, labels and markers in any order") {
            val entries =
                parseDecklist(
                    """
                    1 Sol Ring [Ramp] *F*
                    1 Arcane Signet *F* [Ramp]
                    1 Mind Stone (cmm) 100 [Ramp] ^Have^ *E*
                    1 The Ur-Dragon *CMDR* *F*
                    1 Empress Galina *F* *CMDR* [Legend]
                    """.trimIndent(),
                ).entries

            entries shouldBe
                listOf(
                    DecklistEntry(DecklistSection.Main, 1, "Sol Ring"),
                    DecklistEntry(DecklistSection.Main, 1, "Arcane Signet"),
                    DecklistEntry(DecklistSection.Main, 1, "Mind Stone", "CMM"),
                    DecklistEntry(DecklistSection.Commander, 1, "The Ur-Dragon"),
                    DecklistEntry(DecklistSection.Commander, 1, "Empress Galina"),
                )
        }

        test("a CSV header with padded column names sets the columns") {
            parseDecklist("Name , Quantity\nSol Ring,2\n\"Grenzo, Dungeon Warden\" , 1").entries shouldBe
                listOf(
                    DecklistEntry(DecklistSection.Main, 2, "Sol Ring"),
                    DecklistEntry(DecklistSection.Main, 1, "Grenzo, Dungeon Warden"),
                )
        }

        test("parses CSV rows with and without a header row") {
            parseDecklist("1,Eldrazi Monument\n1,\"Grenzo, Dungeon Warden\"\n17,Mountain").entries shouldBe
                listOf(
                    DecklistEntry(DecklistSection.Main, 1, "Eldrazi Monument"),
                    DecklistEntry(DecklistSection.Main, 1, "Grenzo, Dungeon Warden"),
                    DecklistEntry(DecklistSection.Main, 17, "Mountain"),
                )
            parseDecklist("\"Count\",\"Tradelist Count\",\"Name\",\"Edition\"\n\"2\",\"0\",\"Sol Ring\",\"cmm\"").entries shouldBe
                listOf(DecklistEntry(DecklistSection.Main, 2, "Sol Ring"))
        }

        test("skips deck-name metadata and reads short sideboard markers") {
            val entries =
                parseDecklist(
                    """
                    About
                    Name Mono Green Stompy

                    Deck
                    20 Forest
                    [Side]
                    2 Negate
                    SB: 1 Duress
                    """.trimIndent(),
                ).entries

            entries shouldBe
                listOf(
                    DecklistEntry(DecklistSection.Main, 20, "Forest"),
                    DecklistEntry(DecklistSection.Sideboard, 2, "Negate"),
                    DecklistEntry(DecklistSection.Sideboard, 1, "Duress"),
                )
            parseDecklist("Name Stella Lee cEDH\n1 Sol Ring").entries.single().name shouldBe "Sol Ring"
        }

        test("maybeboard cards are excluded without changing later sections") {
            val decklist =
                parseDecklist(
                    """
                    Commander
                    1 Empress Galina
                    Mainboard
                    Artifacts
                    2 Sol Ring
                    Maybeboard:
                    [Creatures]
                    1 Not In Catalog
                    Sideboard
                    Instants:
                    3 Negate
                    Main
                    Lands
                    4 Island
                    """.trimIndent(),
                )
            val names = mutableListOf<String>()
            val cards =
                decklist.resolveCards { name, _ ->
                    names.add(name)
                    names.size
                }

            assertSoftly {
                names shouldBe listOf("Empress Galina", "Sol Ring", "Negate", "Island")
                cards.commandZone.map { it.grpId to it.quantity } shouldBe listOf(1 to 1)
                cards.mainDeck.map { it.grpId to it.quantity } shouldBe listOf(2 to 2, 4 to 4)
                cards.sideboard.map { it.grpId to it.quantity } shouldBe listOf(3 to 3)
            }
        }

        test("excluded sections still reject unknown headings and invalid quantities") {
            for (line in listOf("[Collection]", "[Mainboard", "Mainboard::", "0 Island", "-1 Island", "999999999999 Island")) {
                val ex = shouldThrow<DecklistException> { parseDecklist("1 Forest\nMaybeboard\n$line") }

                ex.errors shouldHaveSize 1
            }
            shouldThrow<DecklistException> { parseDecklist("[Maybeboard]\n1 Island") }
        }

        test("strips a trailing foil or etched marker") {
            val entries = parseDecklist("1 The Ur-Dragon (SLD) 11 *F*\n1 Sol Ring (SLD) 142 *E*").entries

            assertSoftly {
                entries[0] shouldBe DecklistEntry(DecklistSection.Main, 1, "The Ur-Dragon", "SLD")
                entries[1] shouldBe DecklistEntry(DecklistSection.Main, 1, "Sol Ring", "SLD")
            }
        }

        test("strips star collector numbers") {
            val entries = parseDecklist("1 Arcane Signet (SLD) 1492★\n1 Necron Deathmark (40K) 42★").entries

            assertSoftly {
                entries[0] shouldBe DecklistEntry(DecklistSection.Main, 1, "Arcane Signet", "SLD")
                entries[1] shouldBe DecklistEntry(DecklistSection.Main, 1, "Necron Deathmark", "40K")
            }
        }

        test("strips hyphenated collector numbers and upper-cases the set code") {
            val entries = parseDecklist("1 Skullclamp (PLST) C17-222\n1 Kaya's Ghostform (plst) WAR-94").entries

            assertSoftly {
                entries[0] shouldBe DecklistEntry(DecklistSection.Main, 1, "Skullclamp", "PLST")
                entries[1] shouldBe DecklistEntry(DecklistSection.Main, 1, "Kaya's Ghostform", "PLST")
            }
        }

        test("strips a collector number written without a space after the set code") {
            val entry = parseDecklist("1 Etali, Primal Conqueror / Etali, Primal Sickness (MOM)137").entries.single()

            assertSoftly {
                entry.name shouldBe "Etali, Primal Conqueror / Etali, Primal Sickness"
                entry.setCode shouldBe "MOM"
            }
        }

        test("parses a Forge .dck file with metadata and pipe printing details") {
            val decklist =
                parseDecklist(
                    """
                    [metadata]
                    Name=Import Fixture
                    Deck Type=Constructed
                    Format=Commander

                    [Commander]
                    1 Atraxa, Praetors' Voice|2XM|219

                    [Main]
                    1 Sol Ring|C21|263
                    1 Arcane Signet|SLD|1492★

                    [Sideboard]
                    1 Negate|M10|55
                    """.trimIndent(),
                )

            decklist.entries shouldBe
                listOf(
                    DecklistEntry(DecklistSection.Commander, 1, "Atraxa, Praetors' Voice", "2XM"),
                    DecklistEntry(DecklistSection.Main, 1, "Sol Ring", "C21"),
                    DecklistEntry(DecklistSection.Main, 1, "Arcane Signet", "SLD"),
                    DecklistEntry(DecklistSection.Sideboard, 1, "Negate", "M10"),
                )
        }

        test("rejects non metadata lines inside a [metadata] block") {
            val ex = shouldThrow<DecklistException> { parseDecklist("[metadata]\nName=\n1 Sol Ring") }

            ex.errors shouldBe listOf("Unrecognized line: 1 Sol Ring")
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

        test("rejects header-like lines that are not a known section") {
            for (line in listOf("Lightning Bolt:", "[Collection]", "---", "1,")) {
                val ex = shouldThrow<DecklistException> { parseDecklist("4 Forest\n$line") }

                ex.errors shouldHaveSize 1
            }
        }

        test("rejects an unknown bracket header instead of silently routing to Main") {
            val ex = shouldThrow<DecklistException> { parseDecklist("[Collection]\n4 Lightning Bolt") }

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
