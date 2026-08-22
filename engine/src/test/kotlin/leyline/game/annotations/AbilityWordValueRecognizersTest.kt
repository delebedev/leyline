package leyline.game.annotations

import forge.game.ability.AbilityKey
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import leyline.testkit.BoardTest
import leyline.testkit.aiPlayer
import leyline.testkit.humanPlayer

class AbilityWordValueRecognizersTest :
    BoardTest({
        test("Devotion projects the live count, threshold, and static ability identity") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Heliod, Sun-Crowned", human, ZoneType.Battlefield)
                    repeat(3) { addCard("Savannah Lions", human, ZoneType.Battlefield) }
                    addCard("Savannah Lions", human, ZoneType.Hand)
                }
            val human = board.game.humanPlayer

            fun devotion(): AbilityWordScanner.AbilityWordEntry =
                AbilityWordScanner
                    .scan(
                        battlefieldCards = human.getZone(ZoneType.Battlefield).cards.toList(),
                        instanceIdResolver = { board.bridge.getOrAllocInstanceId(it) },
                        registryResolver = { card ->
                            val grpId = board.bridge.cardRepository.findGrpIdByName(card.name) ?: 0
                            board.bridge.abilityRegistryFor(card, board.bridge.cardRepository.findByGrpId(grpId))
                        },
                    ).first { it.abilityWordName == "Devotion" }

            assertSoftly {
                devotion().value shouldBe 4
                devotion().threshold shouldBe 5
                devotion().abilityGrpId shouldBe 100654
            }

            val lion = human.hand.card("Savannah Lions")
            board.game.action.moveToPlay(lion, null, AbilityKey.newMap())
            devotion().value shouldBe 5
            board.game.action.moveToGraveyard(lion, null)
            devotion().value shouldBe 4
        }

        test("Devotion count on a spell is not projected as the supported permanent threshold") {
            val (bridge, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Aspect of Hydra", human, ZoneType.Battlefield)
                }

            AbilityWordScanner
                .scan(
                    battlefieldCards =
                        game.humanPlayer
                            .getZone(ZoneType.Battlefield)
                            .cards
                            .toList(),
                    instanceIdResolver = { bridge.getOrAllocInstanceId(it) },
                    registryResolver = { _ -> null },
                ).filter { it.abilityWordName == "Devotion" }
                .shouldBeEmpty()
        }

        test("Descend projects permanent cards in graveyard and trigger identity") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("The Everflowing Well", human, ZoneType.Battlefield)
                    repeat(7) { addCard("Plains", human, ZoneType.Graveyard) }
                    addCard("Plains", human, ZoneType.Hand)
                }
            val human = board.game.humanPlayer

            fun descend(): AbilityWordScanner.AbilityWordEntry =
                AbilityWordScanner
                    .scan(
                        battlefieldCards = human.getZone(ZoneType.Battlefield).cards.toList(),
                        instanceIdResolver = { board.bridge.getOrAllocInstanceId(it) },
                        registryResolver = { card ->
                            val grpId = board.bridge.cardRepository.findGrpIdByName(card.name) ?: 0
                            board.bridge.abilityRegistryFor(card, board.bridge.cardRepository.findByGrpId(grpId))
                        },
                    ).first { it.abilityWordName == "Descend" }

            assertSoftly {
                descend().value shouldBe 7
                descend().threshold shouldBe 8
                descend().abilityGrpId shouldBe 169501
            }

            val plains = human.hand.card("Plains")
            board.game.action.moveToGraveyard(plains, null)
            descend().value shouldBe 8
        }

        test("a Descend 4 attack trigger is not projected as the supported Descend 8 tracker") {
            val (bridge, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Stinging Cave Crawler", human, ZoneType.Battlefield)
                }

            AbilityWordScanner
                .scan(
                    battlefieldCards =
                        game.humanPlayer
                            .getZone(ZoneType.Battlefield)
                            .cards
                            .toList(),
                    instanceIdResolver = { bridge.getOrAllocInstanceId(it) },
                    registryResolver = { _ -> null },
                ).filter { it.abilityWordName == "Descend" }
                .shouldBeEmpty()
        }

        test("spell-count tracker projects crossing and turn reset") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Reverberating Summons", human, ZoneType.Battlefield)
                }
            val human = board.game.humanPlayer
            val source = human.battlefield.card("Reverberating Summons")
            val castAbility = source.firstSpellAbility.also { it.activatingPlayer = human }

            fun spellCount(): AbilityWordScanner.AbilityWordEntry =
                AbilityWordScanner
                    .scan(
                        battlefieldCards = human.getZone(ZoneType.Battlefield).cards.toList(),
                        instanceIdResolver = { board.bridge.getOrAllocInstanceId(it) },
                        registryResolver = { card ->
                            val grpId = board.bridge.cardRepository.findGrpIdByName(card.name) ?: 0
                            board.bridge.abilityRegistryFor(card, board.bridge.cardRepository.findByGrpId(grpId))
                        },
                    ).first { it.abilityWordName == "NumberOfSpellsCast" }

            assertSoftly {
                spellCount().value shouldBe 0
                spellCount().threshold shouldBe 2
                spellCount().abilityGrpId shouldBe 188817
            }
            board.game.stack.spellsCastThisTurn
                .add(castAbility)
            spellCount().value shouldBe 1
            board.game.stack.spellsCastThisTurn
                .add(castAbility)
            spellCount().value shouldBe 2
            board.game.stack.spellsCastThisTurn
                .clear()
            spellCount().value shouldBe 0
        }

        test("a no-spells end-step trigger is not projected as the two-spell tracker") {
            val (bridge, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Nightpack Ambusher", human, ZoneType.Battlefield)
                }

            AbilityWordScanner
                .scan(
                    battlefieldCards =
                        game.humanPlayer
                            .getZone(ZoneType.Battlefield)
                            .cards
                            .toList(),
                    instanceIdResolver = { bridge.getOrAllocInstanceId(it) },
                    registryResolver = { _ -> null },
                ).filter { it.abilityWordName == "NumberOfSpellsCast" }
                .shouldBeEmpty()
        }

        test("creature-death tracker crosses two and resets at cleanup") {
            val board =
                startWithBoard { _, human, ai ->
                    addCard("Emeritus of Woe", human, ZoneType.Battlefield)
                    addCard("Soul Warden", ai, ZoneType.Battlefield)
                    addCard("Soul Warden", ai, ZoneType.Battlefield)
                }
            val human = board.game.humanPlayer
            val ai = board.game.registeredPlayers.first { it != human }

            fun deathCount(): AbilityWordScanner.AbilityWordEntry =
                AbilityWordScanner
                    .scan(
                        battlefieldCards = human.getZone(ZoneType.Battlefield).cards.toList(),
                        instanceIdResolver = { board.bridge.getOrAllocInstanceId(it) },
                        registryResolver = { card ->
                            val grpId = board.bridge.cardRepository.findGrpIdByName(card.name) ?: 0
                            board.bridge.abilityRegistryFor(card, board.bridge.cardRepository.findByGrpId(grpId))
                        },
                    ).first { it.abilityWordName == "NumberOfCreaturesDiedThisTurn" }

            assertSoftly {
                deathCount().value shouldBe 0
                deathCount().threshold shouldBe 2
                deathCount().abilityGrpId shouldBe 204360
            }
            val victims = ai.getZone(ZoneType.Battlefield).cards.toList()
            board.game.action.moveToGraveyard(victims[0], null)
            deathCount().value shouldBe 1
            board.game.action.moveToGraveyard(victims[1], null)
            deathCount().value shouldBe 2
            board.game.onCleanupPhase()
            deathCount().value shouldBe 0
        }

        test("enchantment-count tracker crosses seven") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Hallowed Haunting", human, ZoneType.Battlefield)
                    repeat(5) { addCard("Pacifism", human, ZoneType.Battlefield) }
                    addCard("Hallowed Haunting", human, ZoneType.Hand)
                }
            val human = board.game.humanPlayer

            fun enchantmentCount(): AbilityWordScanner.AbilityWordEntry =
                AbilityWordScanner
                    .scan(
                        battlefieldCards = human.getZone(ZoneType.Battlefield).cards.toList(),
                        instanceIdResolver = { board.bridge.getOrAllocInstanceId(it) },
                        registryResolver = { card ->
                            val grpId = board.bridge.cardRepository.findGrpIdByName(card.name) ?: 0
                            board.bridge.abilityRegistryFor(card, board.bridge.cardRepository.findByGrpId(grpId))
                        },
                    ).first { it.abilityWordName == "NumberOfEnchantmentYouControl" }

            assertSoftly {
                enchantmentCount().value shouldBe 6
                enchantmentCount().threshold shouldBe 7
                enchantmentCount().abilityGrpId shouldBe 146545
            }
            val enchantment = human.hand.card("Hallowed Haunting")
            board.game.action.moveToPlay(enchantment, null, AbilityKey.newMap())
            enchantmentCount().value shouldBe 7
            board.game.action.moveToGraveyard(enchantment, null)
            enchantmentCount().value shouldBe 6
        }

        test("unsolved Case projects its running solve count and retires once solved") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Case of the Gateway Express", human, ZoneType.Battlefield)
                    repeat(3) { addCard("Savannah Lions", human, ZoneType.Battlefield) }
                }
            val human = board.game.humanPlayer

            fun toSolve(): List<AbilityWordScanner.AbilityWordEntry> =
                AbilityWordScanner
                    .scan(
                        battlefieldCards = human.getZone(ZoneType.Battlefield).cards.toList(),
                        instanceIdResolver = { board.bridge.getOrAllocInstanceId(it) },
                        registryResolver = { card ->
                            val grpId = board.bridge.cardRepository.findGrpIdByName(card.name) ?: 0
                            board.bridge.abilityRegistryFor(card, board.bridge.cardRepository.findByGrpId(grpId))
                        },
                    ).filter { it.abilityWordName == "ToSolveCondition" }

            assertSoftly {
                toSolve().single().value shouldBe 0
                toSolve().single().threshold shouldBe 3
                toSolve().single().abilityGrpId shouldBe 170350
            }

            val attackers = human.getZone(ZoneType.Battlefield).cards.filter { it.name == "Savannah Lions" }
            attackers.forEach { human.addCreaturesAttackedThisTurn(it, board.game.aiPlayer) }
            toSolve().single().value shouldBe 3

            val case = human.getZone(ZoneType.Battlefield).cards.first { it.name == "Case of the Gateway Express" }
            case.setSolved(true)
            toSolve().shouldBeEmpty()
        }
    })
