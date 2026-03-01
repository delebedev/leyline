package leyline.bridge

import forge.LobbyPlayer
import forge.ai.GameState
import forge.game.GameEntityView
import forge.game.GameView
import forge.game.card.Card
import forge.game.card.CardView
import forge.game.event.GameEventSpellAbilityCast
import forge.game.event.GameEventSpellRemovedFromStack
import forge.game.phase.PhaseType
import forge.game.player.DelayedReveal
import forge.game.player.IHasIcon
import forge.game.player.PlayerView
import forge.game.spellability.SpellAbilityView
import forge.game.zone.ZoneType
import forge.gui.control.PlaybackSpeed
import forge.gui.interfaces.IGuiGame
import forge.interfaces.IGameController
import forge.item.PaperCard
import forge.localinstance.skin.FSkinProp
import forge.player.PlayerZoneUpdate
import forge.player.PlayerZoneUpdates
import forge.trackable.TrackableCollection
import forge.util.FSerializableFunction
import forge.util.ITriggerEvent
import org.slf4j.LoggerFactory

/**
 * Web adapter for [IGuiGame]. Routes interactive choice methods through
 * [InteractivePromptBridge] and stubs desktop-only UI methods as no-ops.
 *
 * This allows [WebPlayerController] to extend [forge.player.PlayerControllerHuman]
 * and inherit all 157 correctly-implemented card interaction methods.
 */
class WebGuiGame(
    private val bridge: InteractivePromptBridge,
) : IGuiGame {

    companion object {
        private val log = LoggerFactory.getLogger(WebGuiGame::class.java)
    }

    // ── Choice primitives → bridge.requestChoice() ────────────────────

    override fun confirm(c: CardView?, question: String): Boolean = confirm(c, question, true, listOf("Yes", "No"))

    override fun confirm(c: CardView?, question: String, options: List<String>): Boolean = confirm(c, question, true, options)

    override fun confirm(c: CardView?, question: String, defaultIsYes: Boolean, options: List<String>?): Boolean {
        val opts = if (options.isNullOrEmpty()) listOf("Yes", "No") else options
        val request = PromptRequest(
            promptType = "confirm",
            message = question,
            options = opts,
            min = 1,
            max = 1,
            defaultIndex = if (defaultIsYes) 0 else 1,
        )
        val result = bridge.requestChoice(request)
        return result.firstOrNull() == 0
    }

    override fun showConfirmDialog(message: String, title: String): Boolean = confirm(null, "$title: $message")

    override fun showConfirmDialog(message: String, title: String, defaultYes: Boolean): Boolean = confirm(null, "$title: $message", defaultYes, null)

    override fun showConfirmDialog(message: String, title: String, yesText: String, noText: String): Boolean = confirm(null, "$title: $message", true, listOf(yesText, noText))

    override fun showConfirmDialog(
        message: String,
        title: String,
        yesText: String,
        noText: String,
        defaultYes: Boolean,
    ): Boolean = confirm(null, "$title: $message", defaultYes, listOf(yesText, noText))

    override fun <T : Any?> one(message: String, choices: List<T>): T {
        if (choices.isEmpty()) throw IllegalArgumentException("one() called with empty list")
        if (choices.size == 1) return choices[0]
        val labels = choices.map { it?.toString() ?: "(none)" }
        val request = PromptRequest(
            promptType = "choose_one",
            message = message,
            options = labels,
            min = 1,
            max = 1,
            defaultIndex = 0,
        )
        val result = bridge.requestChoice(request)
        val idx = result.firstOrNull() ?: 0
        return choices.getOrElse(idx) { choices[0] }
    }

    override fun <T : Any?> one(message: String, choices: List<T>, display: FSerializableFunction<T, String>?): T {
        if (choices.isEmpty()) throw IllegalArgumentException("one() called with empty list")
        if (choices.size == 1) return choices[0]
        val labels = choices.map { display?.apply(it) ?: it?.toString() ?: "(none)" }
        val request = PromptRequest(
            promptType = "choose_one",
            message = message,
            options = labels,
            min = 1,
            max = 1,
            defaultIndex = 0,
        )
        val result = bridge.requestChoice(request)
        val idx = result.firstOrNull() ?: 0
        return choices.getOrElse(idx) { choices[0] }
    }

    override fun <T : Any?> oneOrNone(message: String, choices: List<T>): T? {
        if (choices.isNullOrEmpty()) return null
        if (choices.size == 1) return choices[0]
        val labels = choices.map { it?.toString() ?: "(none)" }
        val request = PromptRequest(
            promptType = "choose_one",
            message = message,
            options = labels,
            min = 0,
            max = 1,
            defaultIndex = 0,
        )
        val result = bridge.requestChoice(request)
        val idx = result.firstOrNull() ?: return null
        return choices.getOrElse(idx) { null }
    }

    override fun <T : Any?> getChoices(
        message: String,
        min: Int,
        max: Int,
        choices: List<T>,
    ): List<T> = getChoices(message, min, max, choices, null, null)

    override fun <T : Any?> getChoices(
        message: String,
        min: Int,
        max: Int,
        choices: List<T>,
        selected: List<T>?,
        display: FSerializableFunction<T, String>?,
    ): List<T> {
        if (choices.isEmpty()) return emptyList()
        val effectiveMax = if (max < 0) choices.size else max.coerceAtMost(choices.size)
        val effectiveMin = min.coerceAtLeast(0).coerceAtMost(effectiveMax)
        if (choices.size <= effectiveMin) return choices.toList()
        val labels = choices.map { display?.apply(it) ?: it?.toString() ?: "(none)" }
        val request = PromptRequest(
            promptType = "choose_cards",
            message = message,
            options = labels,
            min = effectiveMin,
            max = effectiveMax,
            defaultIndex = 0,
        )
        val indices = bridge.requestChoice(request)
        return indices.filter { it in choices.indices }.map { choices[it] }
    }

    override fun getInteger(message: String, min: Int): Int? = getInteger(message, min, Int.MAX_VALUE, 9)

    override fun getInteger(message: String, min: Int, max: Int): Int? = getInteger(message, min, max, 9)

    override fun getInteger(message: String, min: Int, max: Int, sortDesc: Boolean): Int? = getInteger(message, min, max, 9)

    override fun getInteger(message: String, min: Int, max: Int, cutoff: Int): Int? {
        // Present as numbered options for small ranges, or just pick from list
        val range = max - min + 1
        if (range <= 0) return min
        val effectiveCutoff = range.coerceAtMost(cutoff.coerceAtMost(20))
        val options = (min..min + effectiveCutoff - 1).map { it.toString() }
        val request = PromptRequest(
            promptType = "choose_one",
            message = message,
            options = options,
            min = 1,
            max = 1,
            defaultIndex = 0,
        )
        val result = bridge.requestChoice(request)
        val idx = result.firstOrNull() ?: 0
        return (min + idx).coerceIn(min, max)
    }

    override fun <T : Any?> many(
        title: String,
        topCaption: String,
        cnt: Int,
        sourceChoices: List<T>,
        c: CardView?,
    ): List<T> = many(title, topCaption, cnt, cnt, sourceChoices, c)

    override fun <T : Any?> many(
        title: String,
        topCaption: String,
        min: Int,
        max: Int,
        sourceChoices: List<T>,
        c: CardView?,
    ): List<T> = getChoices(title, min, max, sourceChoices)

    override fun <T : Any?> many(
        title: String,
        topCaption: String,
        min: Int,
        max: Int,
        sourceChoices: List<T>,
        destChoices: List<T>,
        c: CardView?,
    ): List<T> = getChoices(title, min, max, sourceChoices)

    override fun <T : Any?> order(
        title: String,
        top: String,
        sourceChoices: List<T>,
        c: CardView?,
    ): List<T> = order(title, top, 0, 0, sourceChoices, listOf(), c, false)

    override fun <T : Any?> order(
        title: String,
        top: String,
        remainingObjectsMin: Int,
        remainingObjectsMax: Int,
        sourceChoices: List<T>,
        destChoices: List<T>,
        referenceCard: CardView?,
        sideboardingMode: Boolean,
    ): List<T> {
        if (sourceChoices.size <= 1) return sourceChoices.toList()
        // Simplified: present as repeated "pick next" via choose_one
        val remaining = sourceChoices.toMutableList()
        val result = mutableListOf<T>()
        while (remaining.size > 1) {
            val labels = remaining.map { it?.toString() ?: "(none)" }
            val request = PromptRequest(
                promptType = "choose_one",
                message = "$title (pick ${result.size + 1} of ${sourceChoices.size})",
                options = labels,
                min = 1,
                max = 1,
                defaultIndex = 0,
            )
            val indices = bridge.requestChoice(request)
            val idx = (indices.firstOrNull() ?: 0).coerceIn(0, remaining.size - 1)
            result.add(remaining.removeAt(idx))
        }
        result.addAll(remaining)
        return result
    }

    override fun <T : Any?> insertInList(title: String, newItem: T, oldItems: List<T>): List<T> {
        // Ask where to insert: present positions as options
        val labels = (0..oldItems.size).map { pos ->
            if (pos == 0) {
                "First"
            } else if (pos == oldItems.size) {
                "Last (after ${oldItems[pos - 1]})"
            } else {
                "After ${oldItems[pos - 1]}"
            }
        }
        val request = PromptRequest(
            promptType = "choose_one",
            message = "$title — where to place $newItem?",
            options = labels,
            min = 1,
            max = 1,
            defaultIndex = 0,
        )
        val result = bridge.requestChoice(request)
        val pos = (result.firstOrNull() ?: 0).coerceIn(0, oldItems.size)
        val combined = oldItems.toMutableList()
        combined.add(pos, newItem)
        return combined
    }

    override fun chooseSingleEntityForEffect(
        title: String,
        optionList: List<out GameEntityView>,
        delayedReveal: DelayedReveal?,
        isOptional: Boolean,
    ): GameEntityView? {
        if (optionList.isEmpty()) return null
        if (optionList.size == 1 && !isOptional) return optionList[0]
        val labels = optionList.map { it?.toString() ?: "(none)" }
        val request = PromptRequest(
            promptType = "choose_cards",
            message = title ?: "Choose one",
            options = labels,
            min = if (isOptional) 0 else 1,
            max = 1,
            defaultIndex = 0,
        )
        val indices = bridge.requestChoice(request)
        val idx = indices.firstOrNull()
        if (idx != null && idx in optionList.indices) return optionList[idx]
        return if (isOptional) null else optionList.firstOrNull()
    }

    override fun chooseEntitiesForEffect(
        title: String,
        optionList: List<out GameEntityView>,
        min: Int,
        max: Int,
        delayedReveal: DelayedReveal?,
    ): List<GameEntityView> {
        if (optionList.isEmpty()) return emptyList()
        val effectiveMax = max.coerceAtMost(optionList.size)
        val effectiveMin = min.coerceAtLeast(0).coerceAtMost(effectiveMax)
        if (optionList.size <= effectiveMin) return optionList.toList()
        val labels = optionList.map { it?.toString() ?: "(none)" }
        val request = PromptRequest(
            promptType = "choose_cards",
            message = title ?: "Choose cards",
            options = labels,
            min = effectiveMin,
            max = effectiveMax,
            defaultIndex = 0,
        )
        val indices = bridge.requestChoice(request)
        return indices.filter { it in optionList.indices }.map { optionList[it] }
    }

    override fun getAbilityToPlay(
        hostCard: CardView,
        abilities: List<SpellAbilityView>,
        triggerEvent: ITriggerEvent?,
    ): SpellAbilityView? {
        if (abilities.isEmpty()) return null
        if (abilities.size == 1) return abilities[0]
        val labels = abilities.map { it?.toString() ?: "(ability)" }
        val request = PromptRequest(
            promptType = "choose_one",
            message = "Choose ability for ${hostCard.name}",
            options = labels,
            min = 1,
            max = 1,
            defaultIndex = 0,
        )
        val result = bridge.requestChoice(request)
        val idx = result.firstOrNull() ?: 0
        return abilities.getOrElse(idx) { abilities[0] }
    }

    override fun assignCombatDamage(
        attacker: CardView,
        blockers: List<CardView>,
        damage: Int,
        defender: GameEntityView?,
        overrideOrder: Boolean,
        maySkip: Boolean,
    ): Map<CardView, Int> {
        // Simplified: assign all damage to first blocker (or defender)
        // TODO: proper damage assignment UI
        if (blockers.isEmpty()) return emptyMap()
        val result = mutableMapOf<CardView, Int>()
        var remaining = damage
        for (blocker in blockers) {
            if (remaining <= 0) break
            // Assign toughness worth of damage to each blocker
            val toAssign = blocker.currentState?.toughness ?: remaining
            val assigned = remaining.coerceAtMost(toAssign)
            result[blocker] = assigned
            remaining -= assigned
        }
        // Assign leftover to last blocker (trample goes through defender)
        if (remaining > 0 && blockers.isNotEmpty()) {
            val last = blockers.last()
            result[last] = (result[last] ?: 0) + remaining
        }
        return result
    }

    override fun assignGenericAmount(
        effectSource: CardView,
        target: Map<Any, Int>,
        amount: Int,
        atLeastOne: Boolean,
        amountLabel: String,
    ): Map<Any, Int> {
        // Simplified: distribute evenly
        if (target.isEmpty()) return emptyMap()
        val perTarget = amount / target.size
        val remainder = amount % target.size
        var i = 0
        return target.keys.associateWith { key ->
            val extra = if (i < remainder) 1 else 0
            i++
            perTarget + extra
        }
    }

    override fun sideboard(sideboard: forge.deck.CardPool, main: forge.deck.CardPool, message: String): List<PaperCard> {
        // Sideboarding not yet supported via web UI
        log.info("Sideboard requested but not implemented in web UI")
        return emptyList()
    }

    override fun showOptionDialog(
        message: String,
        title: String,
        icon: FSkinProp,
        options: List<String>,
        defaultOption: Int,
    ): Int {
        if (options.isEmpty()) return -1
        val request = PromptRequest(
            promptType = "choose_one",
            message = "$title: $message",
            options = options,
            min = 1,
            max = 1,
            defaultIndex = defaultOption.coerceIn(0, options.size - 1),
        )
        val result = bridge.requestChoice(request)
        return result.firstOrNull() ?: defaultOption
    }

    override fun showInputDialog(message: String, title: String, isNumeric: Boolean): String = showInputDialog(message, title, null, null, null, isNumeric)

    override fun showInputDialog(message: String, title: String, icon: FSkinProp): String = showInputDialog(message, title, icon, null, null, false)

    override fun showInputDialog(message: String, title: String, icon: FSkinProp?, initialInput: String?): String = showInputDialog(message, title, icon, initialInput, null, false)

    override fun showInputDialog(
        message: String,
        title: String,
        icon: FSkinProp?,
        initialInput: String?,
        inputOptions: List<String>?,
        isNumeric: Boolean,
    ): String {
        // If options are provided, use choose_one
        if (!inputOptions.isNullOrEmpty()) {
            val request = PromptRequest(
                promptType = "choose_one",
                message = "$title: $message",
                options = inputOptions,
                min = 1,
                max = 1,
                defaultIndex = 0,
            )
            val result = bridge.requestChoice(request)
            val idx = result.firstOrNull() ?: 0
            return inputOptions.getOrElse(idx) { initialInput ?: "" }
        }
        // Free text input — return initial or empty for now
        // TODO: text_input prompt type
        log.info("Free text input requested: $title: $message (returning '${initialInput ?: ""}')")
        return initialInput ?: ""
    }

    override fun manipulateCardList(
        title: String,
        cards: Iterable<CardView>,
        manipulable: Iterable<CardView>,
        toTop: Boolean,
        toBottom: Boolean,
        toAnywhere: Boolean,
    ): MutableList<CardView> {
        // Return manipulable cards as-is (no reordering in web yet)
        return manipulable.toMutableList()
    }

    // ── Information display → log or no-op ─────────────────────────────

    override fun <T : Any?> reveal(message: String, items: List<T>) {
        log.debug("Reveal: $message — ${items.size} items")
    }

    override fun message(message: String) {
        log.debug("Message: $message")
    }

    override fun message(message: String, title: String) {
        log.debug("Message [$title]: $message")
    }

    override fun showErrorDialog(message: String) {
        log.warn("Error dialog: $message")
    }

    override fun showErrorDialog(message: String, title: String) {
        log.warn("Error dialog [$title]: $message")
    }

    // ── UI state management → no-op ────────────────────────────────────

    private var gameView: GameView? = null

    override fun setGameView(gameView: GameView) {
        this.gameView = gameView
    }
    override fun getGameView(): GameView? = gameView
    override fun setOriginalGameController(view: PlayerView, gameController: IGameController) {}
    override fun setGameController(player: PlayerView, gameController: IGameController) {}
    override fun setSpectator(spectator: IGameController) {}
    override fun openView(myPlayers: TrackableCollection<PlayerView>) {}
    override fun afterGameEnd() {}
    override fun showCombat() {}
    override fun showPromptMessage(playerView: PlayerView, message: String) {}
    override fun showCardPromptMessage(playerView: PlayerView, message: String, card: CardView) {}
    override fun updateButtons(owner: PlayerView, okEnabled: Boolean, cancelEnabled: Boolean, focusOk: Boolean) {}
    override fun updateButtons(owner: PlayerView, label1: String, label2: String, enable1: Boolean, enable2: Boolean, focus1: Boolean) {}
    override fun flashIncorrectAction() {}
    override fun alertUser() {}
    override fun updatePhase(saveState: Boolean) {}
    override fun updateTurn(player: PlayerView) {}
    override fun updatePlayerControl() {}
    override fun enableOverlay() {}
    override fun disableOverlay() {}
    override fun finishGame() {}
    override fun showManaPool(player: PlayerView) {}
    override fun hideManaPool(player: PlayerView) {}
    override fun updateStack() {}
    override fun notifyStackAddition(event: GameEventSpellAbilityCast) {}
    override fun notifyStackRemoval(event: GameEventSpellRemovedFromStack) {}
    override fun handleLandPlayed(land: Card) {}
    override fun tempShowZones(controller: PlayerView, zonesToUpdate: Iterable<PlayerZoneUpdate>): Iterable<PlayerZoneUpdate> = zonesToUpdate
    override fun hideZones(controller: PlayerView, zonesToUpdate: Iterable<PlayerZoneUpdate>) {}
    override fun updateZones(zonesToUpdate: Iterable<PlayerZoneUpdate>) {}
    override fun updateSingleCard(card: CardView) {}
    override fun updateCards(cards: Iterable<CardView>) {}
    override fun updateRevealedCards(collection: TrackableCollection<CardView>) {}
    override fun refreshCardDetails(cards: Iterable<CardView>) {}
    override fun refreshField() {}
    override fun getGamestate(): GameState? = null
    override fun updateManaPool(manaPoolUpdate: Iterable<PlayerView>) {}
    override fun updateLives(livesUpdate: Iterable<PlayerView>) {}
    override fun updateShards(shardsUpdate: Iterable<PlayerView>) {}
    override fun updateDependencies() {}
    override fun setPanelSelection(hostCard: CardView) {}
    override fun setCard(card: CardView) {}
    override fun setPlayerAvatar(player: LobbyPlayer, ihi: IHasIcon) {}
    override fun openZones(controller: PlayerView, zones: Collection<ZoneType>, players: Map<PlayerView, Any>, backupLastZones: Boolean): PlayerZoneUpdates = PlayerZoneUpdates()
    override fun restoreOldZones(playerView: PlayerView, playerZoneUpdates: PlayerZoneUpdates) {}
    override fun setHighlighted(pv: PlayerView, b: Boolean) {}
    override fun setUsedToPay(card: CardView, value: Boolean) {}
    override fun setSelectables(cards: Iterable<CardView>) {}
    override fun clearSelectables() {}
    override fun isSelecting(): Boolean = false
    override fun isGamePaused(): Boolean = false
    override fun setgamePause(pause: Boolean) {}
    override fun setGameSpeed(gameSpeed: PlaybackSpeed) {}
    override fun getDayTime(): String = "day"
    override fun updateDayTime(daytime: String) {}
    override fun awaitNextInput() {}
    override fun cancelAwaitNextInput() {}
    override fun isUiSetToSkipPhase(playerTurn: PlayerView, phase: PhaseType): Boolean = false
    override fun autoPassUntilEndOfTurn(player: PlayerView) {}
    override fun mayAutoPass(player: PlayerView): Boolean = false
    override fun autoPassCancel(player: PlayerView) {}
    override fun updateAutoPassPrompt() {}
    override fun shouldAutoYield(key: String): Boolean = false
    override fun setShouldAutoYield(key: String, autoYield: Boolean) {}
    override fun shouldAlwaysAcceptTrigger(trigger: Int): Boolean = false
    override fun shouldAlwaysDeclineTrigger(trigger: Int): Boolean = false
    override fun setShouldAlwaysAcceptTrigger(trigger: Int) {}
    override fun setShouldAlwaysDeclineTrigger(trigger: Int) {}
    override fun setShouldAlwaysAskTrigger(trigger: Int) {}
    override fun clearAutoYields() {}
    override fun setCurrentPlayer(player: PlayerView) {}
    override fun showWaitingTimer(forPlayer: PlayerView, waitingForPlayerName: String) {}
    override fun isNetGame(): Boolean = false
    override fun setNetGame() {}
}
