package leyline.bridge

import forge.ai.LobbyPlayerAi
import forge.deck.Deck
import forge.game.Game
import forge.game.GameRules
import forge.game.GameStage
import forge.game.GameType
import forge.game.Match
import forge.game.phase.PhaseType
import forge.game.player.RegisteredPlayer
import forge.gui.GuiBase
import forge.gui.interfaces.IGuiGame
import forge.localinstance.properties.ForgePreferences.FPref
import forge.model.FModel
import forge.player.PlayerControllerHuman
import forge.util.Lang
import forge.util.Localizer
import java.nio.file.Files
import java.nio.file.Path

/** True when game rules indicate a puzzle (either primary type or applied variant). */
val Game.isPuzzle: Boolean
    get() = rules.gameType == GameType.Puzzle || rules.hasAppliedVariant(GameType.Puzzle)

/** True when game rules indicate Commander. */
val Game.isCommander: Boolean
    get() = rules.gameType == GameType.Commander || rules.hasAppliedVariant(GameType.Commander)

/** Commander-family game type slugs (used for deck validation, game creation). */
val COMMANDER_VARIANTS = setOf("commander", "brawl", "oathbreaker")

fun isCommanderVariant(gameType: String): Boolean = gameType.lowercase() in COMMANDER_VARIANTS

object GameBootstrap {
    private var initialized = false
    private var cardDatabaseInitialized = false

    fun createGame(): Game {
        ensureLocalization()
        ensureGuiBase()

        val players = mutableListOf<RegisteredPlayer>()
        val deck = Deck()

        // Human + AI so startGameLoop creates bridges for the human seat.
        players.add(RegisteredPlayer(deck).setPlayer(forge.player.LobbyPlayerHuman("player1")))
        players.add(RegisteredPlayer(deck).setPlayer(LobbyPlayerAi("player2", null)))

        val rules = GameRules(GameType.Constructed)
        val match = Match(rules, players, "Forge Web")
        val game = Game(players, rules, match)

        // Attach headless GUI so PCHuman callbacks don't NPE
        val humanController = game.players.firstOrNull()?.controller
        if (humanController is PlayerControllerHuman && humanController.gui == null) {
            humanController.gui = headlessGuiGame()
        }

        val activePlayer = game.players.first()
        game.age = GameStage.Play
        game.phaseHandler.devModeSet(PhaseType.MAIN1, activePlayer)
        game.phaseHandler.onStackResolved()

        game.updateTurnForView()
        game.updatePhaseForView()
        game.updatePlayerTurnForView()

        return game
    }

    /** Lightweight placeholder for lobby rooms — two AI players, no GUI init. */
    fun createLobbyPlaceholder(): Game {
        ensureLocalization()
        val deck = Deck()
        val players = mutableListOf<RegisteredPlayer>()
        players.add(RegisteredPlayer(deck).setPlayer(LobbyPlayerAi("p1", null)))
        players.add(RegisteredPlayer(deck).setPlayer(LobbyPlayerAi("p2", null)))
        val rules = GameRules(GameType.Constructed)
        val match = Match(rules, players, "Lobby")
        return Game(players, rules, match)
    }

    fun createPuzzleGame(): Game {
        ensureLocalization()
        val players = mutableListOf<RegisteredPlayer>()
        val deck = Deck()

        val human = RegisteredPlayer(deck)
            .setPlayer(forge.player.GamePlayerUtil.getGuiPlayer())
        human.startingHand = 0
        players.add(human)

        val ai = RegisteredPlayer(deck)
            .setPlayer(LobbyPlayerAi("AI", null))
        ai.startingHand = 0
        players.add(ai)

        val rules = GameRules(GameType.Puzzle)
        val match = Match(rules, players, "Forge Web")
        val game = Game(players, rules, match)

        // Puzzle setup relies on a GUI-tagged human controller. In headless mode,
        // attach a no-op GUI so controller callbacks (mana pool/current player) don't crash.
        val humanController = game.players.firstOrNull()?.controller
        if (humanController is PlayerControllerHuman && humanController.gui == null) {
            humanController.gui = headlessGuiGame()
        }

        return game
    }

    /**
     * Create a two-player constructed game (both human, no AI).
     * Each player gets a separate [LobbyPlayerHuman].
     *
     * Zones are populated by the engine via [Match.startGame] when
     * [GameLoopController.start] is called.
     */
    fun createTwoPlayerGame(
        deck1: Deck,
        deck2: Deck,
        name1: String = "Human",
        name2: String = "Player 2",
    ): Game {
        ensureLocalization()

        val players = mutableListOf<RegisteredPlayer>()

        val p1 = RegisteredPlayer(deck1)
            .setPlayer(forge.player.LobbyPlayerHuman(name1))
        players.add(p1)

        val p2 = RegisteredPlayer(deck2)
            .setPlayer(forge.player.LobbyPlayerHuman(name2))
        players.add(p2)

        val rules = GameRules(GameType.Constructed)
        val match = Match(rules, players, "Forge Web")
        val game = Game(players, rules, match)

        // Attach headless GUI to both human controllers
        for (player in game.players) {
            val ctrl = player.controller
            if (ctrl is PlayerControllerHuman && ctrl.gui == null) {
                ctrl.gui = headlessGuiGame()
            }
        }

        return game
    }

    /**
     * Create a two-player commander-variant game (both human).
     * Used by multiplayer lobby when gameType is commander/brawl/oathbreaker.
     */
    fun createTwoPlayerCommanderGame(
        deck1: Deck,
        deck2: Deck,
        name1: String = "Human",
        name2: String = "Player 2",
        variant: String = "commander",
    ): Game {
        ensureLocalization()

        val gameType = resolveCommanderVariant(variant)
        val players = mutableListOf<RegisteredPlayer>()

        val p1 = RegisteredPlayer.forCommander(deck1)
            .setPlayer(forge.player.LobbyPlayerHuman(name1))
        players.add(p1)

        val p2 = RegisteredPlayer.forCommander(deck2)
            .setPlayer(forge.player.LobbyPlayerHuman(name2))
        players.add(p2)

        val rules = GameRules(gameType)
        rules.addAppliedVariant(gameType)
        val match = Match(rules, players, "Forge Web")
        val game = Game(players, rules, match)

        for (player in game.players) {
            val ctrl = player.controller
            if (ctrl is PlayerControllerHuman && ctrl.gui == null) {
                ctrl.gui = headlessGuiGame()
            }
        }

        return game
    }

    /**
     * Create a constructed game (human vs AI) with decks on [RegisteredPlayer].
     *
     * Zones are populated by the engine via [Match.startGame] when
     * [GameLoopController.start] is called.
     */
    fun createConstructedGame(humanDeck: Deck, aiDeck: Deck): Game {
        ensureLocalization()

        val players = mutableListOf<RegisteredPlayer>()

        val human = RegisteredPlayer(humanDeck)
            .setPlayer(forge.player.GamePlayerUtil.getGuiPlayer())
        players.add(human)

        val ai = RegisteredPlayer(aiDeck)
            .setPlayer(LobbyPlayerAi("AI", null))
        players.add(ai)

        val rules = GameRules(GameType.Constructed)
        val match = Match(rules, players, "Forge Web")
        val game = Game(players, rules, match)

        // Attach headless GUI to human controller (same as puzzle flow)
        val humanController = game.players.firstOrNull()?.controller
        if (humanController is PlayerControllerHuman && humanController.gui == null) {
            humanController.gui = headlessGuiGame()
        }

        return game
    }

    fun createCommanderGame(humanDeck: Deck, aiDeck: Deck, variant: String = "commander"): Game {
        ensureLocalization()

        val gameType = resolveCommanderVariant(variant)
        val players = mutableListOf<RegisteredPlayer>()

        val human = RegisteredPlayer.forCommander(humanDeck)
            .setPlayer(forge.player.GamePlayerUtil.getGuiPlayer())
        players.add(human)

        val ai = RegisteredPlayer.forCommander(aiDeck)
            .setPlayer(LobbyPlayerAi("AI", null))
        players.add(ai)

        val rules = GameRules(gameType)
        rules.addAppliedVariant(gameType)
        val match = Match(rules, players, "Forge Web")
        val game = Game(players, rules, match)

        // Attach headless GUI to human controller
        val humanController = game.players.firstOrNull()?.controller
        if (humanController is PlayerControllerHuman && humanController.gui == null) {
            humanController.gui = headlessGuiGame()
        }

        return game
    }

    /**
     * Create a constructed game between two AI players (spectator mode).
     * No human controller, no headless GUI — both seats get the engine's
     * default [forge.ai.PlayerControllerAi].
     */
    fun createAiVsAiGame(deck1: Deck, deck2: Deck): Game {
        ensureLocalization()

        val players = mutableListOf<RegisteredPlayer>()
        players.add(RegisteredPlayer(deck1).setPlayer(LobbyPlayerAi("AI 1", null)))
        players.add(RegisteredPlayer(deck2).setPlayer(LobbyPlayerAi("AI 2", null)))

        val rules = GameRules(GameType.Constructed)
        val match = Match(rules, players, "Forge Web")
        return Game(players, rules, match)
    }

    /**
     * Create a commander-variant game between two AI players (spectator mode).
     * Same as [createAiVsAiGame] but with Commander/Brawl/Oathbreaker rules.
     */
    fun createAiVsAiCommanderGame(deck1: Deck, deck2: Deck, variant: String = "commander"): Game {
        ensureLocalization()

        val gameType = resolveCommanderVariant(variant)
        val players = mutableListOf<RegisteredPlayer>()
        players.add(RegisteredPlayer.forCommander(deck1).setPlayer(LobbyPlayerAi("AI 1", null)))
        players.add(RegisteredPlayer.forCommander(deck2).setPlayer(LobbyPlayerAi("AI 2", null)))

        val rules = GameRules(gameType)
        rules.addAppliedVariant(gameType)
        val match = Match(rules, players, "Forge Web")
        return Game(players, rules, match)
    }

    /**
     * Finalize a pre-initialized game (puzzle/sandbox) for the game loop.
     * Sets age to Play and preserves the phase/player/turn set by
     * [Puzzle.applyGameOnThread] (via `ActivePhase` in the .pzl file).
     * Only defaults to MAIN1 if the puzzle didn't specify a phase.
     *
     * NOT used for constructed/commander — those go through
     * [Match.startGame] via [GameLoopController.start].
     */
    fun finalizeForPuzzle(game: Game) {
        game.age = GameStage.Play

        // Puzzle.applyGameOnThread calls devModeSet with ActivePhase.
        // Only default to MAIN1 if the puzzle didn't specify a phase.
        if (game.phaseHandler.phase == null) {
            val activePlayer = game.players.first()
            game.phaseHandler.devModeSet(PhaseType.MAIN1, activePlayer, false, 1)
        }

        game.updateTurnForView()
        game.updatePhaseForView()
        game.updatePlayerTurnForView()
    }

    fun initializeLocalization() {
        ensureLocalization()
    }

    fun initializeCardDatabase(quiet: Boolean = false) {
        if (quiet) forge.card.CardDb.quietInit = true
        ensureCardDatabaseLoaded()
    }

    private fun ensureLocalization() {
        if (initialized) {
            return
        }

        // GameType enum uses Localizer during static init; headless boot must initialize first.
        Lang.createInstance("en-US")
        Localizer.getInstance().initialize("en-US", resolveLanguagesDir().toString())
        initialized = true
    }

    private fun resolveLanguagesDir(): Path =
        resolveForgeResource("forge-gui/res/languages") { Files.isDirectory(it) }

    private fun ensureGuiBase() {
        if (GuiBase.getInterface() == null) {
            GuiBase.setInterface(WebGuiBase(resolveAssetsDir().toString()))
        }
    }

    private fun ensureCardDatabaseLoaded() {
        if (cardDatabaseInitialized) {
            return
        }

        ensureGuiBase()

        FModel.initialize(null) { preferences ->
            preferences.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, true)
            preferences.setPref(FPref.UI_LANGUAGE, "en-US")
            null
        }
        cardDatabaseInitialized = true
    }

    private fun resolveAssetsDir(): Path =
        resolveForgeResource("forge-gui") { Files.isDirectory(it.resolve("res")) }

    private fun headlessGuiGame(): IGuiGame {
        ensureGuiBase()
        return GuiBase.getInterface().getNewGuiGame()
    }

    /** Map a lowercase variant string to the engine [GameType]. */
    private fun resolveCommanderVariant(variant: String): GameType = when (variant.lowercase()) {
        "commander" -> GameType.Commander
        "brawl" -> GameType.Brawl
        "oathbreaker" -> GameType.Oathbreaker
        else -> GameType.Commander
    }
}
