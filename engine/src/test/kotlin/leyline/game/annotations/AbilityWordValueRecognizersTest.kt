package leyline.game.annotations

import forge.game.ability.AbilityKey
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import leyline.testkit.BoardTest
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

            val lion = human.getZone(ZoneType.Hand).cards.first { it.name == "Savannah Lions" }
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

            val plains = human.getZone(ZoneType.Hand).cards.first { it.name == "Plains" }
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
            val source = human.getZone(ZoneType.Battlefield).cards.first { it.name == "Reverberating Summons" }
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
    })
