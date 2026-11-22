package leyline.behavior.cards

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import leyline.testkit.beInGraveyardOf
import leyline.testkit.beInHandOf
import wotc.mtgo.gre.external.messaging.Messages.CardType
import wotc.mtgo.gre.external.messaging.Messages.StaticList

/** Resolution choices without a dedicated prompt are asked over the options the player can be shown. */
class ResolutionChoiceFallbackTest :
    SessionTest({
        session(
            "Creeping Renaissance asks for a permanent type and returns the chosen cards",
            """
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanbattlefield=Forest;Forest;Forest;Forest;Forest;Forest;Forest
            humanhand=Creeping Renaissance
            humangraveyard=Grizzly Bears;Forest;Pacifism;Lightning Bolt
            humanlibrary=Forest;Forest;Forest;Forest;Forest
            ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain
            """.trimIndent(),
        ) {
            val req = castSpellUntilSelectNReq("Creeping Renaissance")
            assertSoftly {
                req.staticList shouldBe StaticList.CardTypes
                // Forge's domain also holds Stickers, which has no card type id and is left out.
                req.idsList shouldContainExactlyInAnyOrder
                    listOf(
                        CardType.Artifact_a80b,
                        CardType.Battle,
                        CardType.Conspiracy,
                        CardType.Creature,
                        CardType.Dungeon,
                        CardType.Enchantment,
                        CardType.Land_a80b,
                        CardType.Phenomenon,
                        CardType.Plane,
                        CardType.Planeswalker,
                        CardType.Scheme,
                        CardType.Vanguard_a80b,
                    ).map { it.number }
                req.minSel shouldBe 1
                req.maxSel shouldBe 1
            }
            respondToSelectN(listOf(CardType.Creature.number))
            passUntilResolved()
            assertSoftly {
                "Grizzly Bears" should beInHandOf(human)
                listOf("Forest", "Pacifism", "Lightning Bolt", "Creeping Renaissance").forEach { it should beInGraveyardOf(human) }
                game().stackZone.size() shouldBe 0
            }
        }

        session(
            "Circle of Protection: Red asks for a red source without section headers",
            """
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanbattlefield=Circle of Protection: Red;Plains;Plains
            humanlibrary=Plains;Plains;Plains;Plains;Plains
            aibattlefield=Raging Goblin;Goblin Piker
            ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain
            """.trimIndent(),
            fullControl = true,
        ) {
            activateAbility("Circle of Protection: Red").shouldBeTrue()
            passUntil(maxPasses = 10) { bridge.cutCoordinator.compatibilityCostSelection.current() != null }.shouldBeTrue()
            val req = allMessages.last { it.hasSelectTargetsReq() }.selectTargetsReq
            val offered =
                req.targetsList
                    .single()
                    .targetsList
                    .map { it.targetInstanceId }
            val piker = ai.battlefield.iid("Goblin Piker")
            offered shouldContainExactlyInAnyOrder listOf(ai.battlefield.iid("Raging Goblin"), piker)
            selectTargets(listOf(piker))
            passUntil(maxPasses = 10) { game().stackZone.size() == 0 }.shouldBeTrue()
            assertSoftly {
                bridge.hasPendingNonActionInteraction() shouldBe false
                human.getZone(ZoneType.Command).cards.any { it.name.contains("Circle of Protection") } shouldBe true
            }
        }

        // Seed 1 rolls 4 or 5, the branch that deals damage to a creature or player.
        session(
            "Comet, Stellar Pup asks which creature or player takes the damage",
            """
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanbattlefield=Comet, Stellar Pup|Counters:LOYALTY=5;Wall of Stone
            humanlibrary=Plains;Plains;Plains;Plains;Plains
            aibattlefield=Wall of Roots
            ailibrary=Forest;Forest;Forest;Forest;Forest
            """.trimIndent(),
            seed = 1L,
            fullControl = true,
        ) {
            activateAbility("Comet, Stellar Pup").shouldBeTrue()
            passPriority()
            passUntil(maxPasses = 10) { bridge.cutCoordinator.compatibilityCostSelection.current() != null }.shouldBeTrue()
            val req = allMessages.last { it.hasSelectTargetsReq() }.selectTargetsReq
            val wallOfRoots = ai.battlefield.iid("Wall of Roots")
            req.targetsList
                .single()
                .targetsList
                .map { it.targetInstanceId } shouldContainExactlyInAnyOrder
                listOf(human.battlefield.iid("Wall of Stone"), wallOfRoots, 1, 2)
            selectTargets(listOf(wallOfRoots))
            passUntil(maxPasses = 10) { game().stackZone.size() == 0 }.shouldBeTrue()
            val comet = human.battlefield.card("Comet, Stellar Pup")
            assertSoftly {
                "Wall of Roots" should beInGraveyardOf(ai)
                human.battlefield.card("Wall of Stone").damage shouldBe 0
                comet.currentLoyalty shouldBe 3
            }
        }

        for (creatureInstead in listOf(true, false)) {
            session(
                "Thirst for Identity asks whether to discard a creature card instead (creature=$creatureInstead)",
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanbattlefield=Island;Island;Island
                humanhand=Thirst for Identity;Grizzly Bears;Forest
                humanlibrary=Island;Island;Island;Island;Island
                ailibrary=Forest;Forest;Forest;Forest;Forest
                """.trimIndent(),
            ) {
                val branch = castSpellUntilSelectNReq("Thirst for Identity")
                assertSoftly {
                    branch.prompt.parametersList.map { it.stringValue } shouldBe
                        listOf("Discard 2 cards", "Discard a Creature card")
                    branch.idsList shouldBe listOf(1, 2)
                }
                respondToSelectN(listOf(if (creatureInstead) 2 else 1))
                if (!creatureInstead) {
                    val discard = lastSelectNReq()
                    discard.minSel shouldBe 2
                    respondToSelectN(listOf(human.hand.iid("Grizzly Bears"), human.hand.iid("Forest")))
                }
                passUntilResolved()
                assertSoftly {
                    "Grizzly Bears" should beInGraveyardOf(human)
                    if (creatureInstead) "Forest" should beInHandOf(human) else "Forest" should beInGraveyardOf(human)
                    game().stackZone.size() shouldBe 0
                }
            }
        }
    })
