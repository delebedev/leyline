package leyline.bridge

import forge.game.zone.ZoneType
import io.kotest.matchers.shouldBe
import leyline.testkit.BoardTest

class CompanionActionTest :
    BoardTest({
        test("eligible starting library designates a sideboard companion") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Library)
                    addCard("Lurrus of the Dream-Den", human, ZoneType.Sideboard)
                }
            board.human.assignCompanion(board.game, board.human.controller)
            CompanionAction.chosenCard(board.human)?.name shouldBe "Lurrus of the Dream-Den"
        }

        test("three mana permanent in starting library rejects Lurrus") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Centaur Courser", human, ZoneType.Library)
                    addCard("Lurrus of the Dream-Den", human, ZoneType.Sideboard)
                }
            board.human.assignCompanion(board.game, board.human.controller)
            CompanionAction.chosenCard(board.human) shouldBe null
        }

        test("companion ability inherits sorcery timing and is unavailable during upkeep") {
            val board =
                startWithBoard { game, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Library)
                    addCard("Lurrus of the Dream-Den", human, ZoneType.Sideboard)
                    game.phaseHandler.devModeSet(forge.game.phase.PhaseType.UPKEEP, human, false)
                }
            board.human.assignCompanion(board.game, board.human.controller)
            board.game.action.checkStaticAbilities(false)
            val card = CompanionAction.chosenCard(board.human)!!
            getNonManaActivatedAbilities(card, board.human).single { CompanionAction.matches(it) }.canPlay() shouldBe false
        }
    })
