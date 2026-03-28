package leyline.game

import leyline.bridge.ForgeCardId
import leyline.bridge.SeatId

/**
 * Lightweight zone enum for [GameEvent]. Decoupled from forge.game.zone.ZoneType
 * so the event/annotation layer has zero forge dependencies. Mapping from
 * forge ZoneType happens in [GameEventCollector] at the bridge boundary.
 */
enum class Zone {
    Hand,
    Library,
    Graveyard,
    Battlefield,
    Exile,
    Stack,
    Command,
    Other,
    ;

    companion object {
        /** Map from forge ZoneType name. Called only in GameEventCollector. */
        fun fromForge(forgeZone: forge.game.zone.ZoneType): Zone = when (forgeZone) {
            forge.game.zone.ZoneType.Hand -> Hand
            forge.game.zone.ZoneType.Library -> Library
            forge.game.zone.ZoneType.Graveyard -> Graveyard
            forge.game.zone.ZoneType.Battlefield -> Battlefield
            forge.game.zone.ZoneType.Exile -> Exile
            forge.game.zone.ZoneType.Stack -> Stack
            forge.game.zone.ZoneType.Command -> Command
            else -> Other
        }
    }
}

/**
 * Protocol-oriented game events captured from the Forge engine's EventBus.
 *
 * These replace the "infer from diff" heuristic in [StateMapper.buildFromGame]:
 * instead of comparing zone snapshots and guessing what happened, the
 * [GameEventCollector] captures rich events as they fire and the annotation
 * builder converts them directly into client protocol annotations.
 *
 * All IDs here are **Forge card IDs** (not client instanceIds). The bridge
 * resolves them to instanceIds at annotation-build time so the event layer
 * stays decoupled from protocol ID allocation.
 *
 * **Forge extension pattern:** When Forge's built-in events lack per-card
 * granularity (e.g. GameEventSurveil carries counts but no card IDs), we add
 * per-card events to our fork (e.g. GameEventCardSurveiled) and fire them from
 * the engine. This keeps the collector simple — one visit method per event type —
 * instead of correlating summary events with zone changes after the fact.
 * Consider extending Forge when a new mechanic needs per-card category resolution.
 */
sealed interface GameEvent {

    /** A land was played from hand to battlefield.
     *  [colorBitmasks] = per-mana-ability color bitmask (1=W, 2=U, 4=B, 8=R, 16=G).
     *  Single-ability lands produce one entry; dual/multi-lands produce multiple. */
    data class LandPlayed(
        val cardId: ForgeCardId,
        val seatId: SeatId,
        val colorBitmasks: List<Int> = emptyList(),
    ) : GameEvent

    /** One mana globe spent to pay for a spell. */
    data class ManaPayment(
        val sourceCardId: ForgeCardId,
        val color: Int,
    )

    /** A spell or ability was cast (hand/battlefield → stack). */
    data class SpellCast(
        val cardId: ForgeCardId,
        val seatId: SeatId,
        val manaPayments: List<ManaPayment> = emptyList(),
        val isAdventure: Boolean = false,
    ) : GameEvent

    /** A spell was placed on the stack before costs were paid.
     *  Signals that this GSM should be split into QueuedGSM triplet.
     *  Wired from GameEventSpellMovedToStack. */
    data class SpellMovedToStack(
        val cardId: ForgeCardId,
        val seatId: SeatId,
    ) : GameEvent

    /** A spell or ability finished resolving (stack → battlefield/graveyard/exile). */
    data class SpellResolved(
        val cardId: ForgeCardId,
        val hasFizzled: Boolean,
    ) : GameEvent

    /** A card changed zones (generic — covers destroy, exile, sacrifice, bounce, etc.). */
    data class ZoneChanged(
        val cardId: ForgeCardId,
        val from: Zone,
        val to: Zone,
    ) : GameEvent

    /** A permanent was tapped or untapped. */
    data class CardTapped(
        val cardId: ForgeCardId,
        val tapped: Boolean,
    ) : GameEvent

    /** Damage was dealt to a creature. */
    data class DamageDealtToCard(
        val sourceCardId: ForgeCardId,
        val targetCardId: ForgeCardId,
        val amount: Int,
    ) : GameEvent

    /** Damage was dealt to a player. */
    data class DamageDealtToPlayer(
        val sourceCardId: ForgeCardId,
        val targetSeatId: SeatId,
        val amount: Int,
        val combat: Boolean,
    ) : GameEvent

    /** A player's life total changed. */
    data class LifeChanged(
        val seatId: SeatId,
        val oldLife: Int,
        val newLife: Int,
    ) : GameEvent

    /** Attackers were declared. */
    data class AttackersDeclared(
        val attackerCardIds: List<ForgeCardId>,
        val seatId: SeatId,
    ) : GameEvent

    /** Blockers were declared. */
    data class BlockersDeclared(
        val blockerCardIds: List<ForgeCardId>,
        val seatId: SeatId,
    ) : GameEvent

    // -- Group A: zone-transition disambiguation --
    // These replace generic ZoneChanged for specific zone pairs, enabling
    // direct category mapping without the zoneChangedCategory() fallback.

    /** A legendary permanent was put into graveyard by the legend rule SBA.
     *  More specific than [CardDestroyed] — produces `SBA_LegendRule` category. */
    data class LegendRuleDeath(
        val cardId: ForgeCardId,
        val seatId: SeatId,
    ) : GameEvent

    /** A permanent was destroyed (BF→GY, not sacrifice).
     *  [sourceCardId] = host card of the ability that caused the destruction (for affectorId). */
    data class CardDestroyed(
        val cardId: ForgeCardId,
        val seatId: SeatId,
        val sourceCardId: ForgeCardId? = null,
    ) : GameEvent

    /** A permanent was sacrificed (BF→GY via sacrifice effect). */
    data class CardSacrificed(
        val cardId: ForgeCardId,
        val seatId: SeatId,
    ) : GameEvent

    /** A permanent was bounced (BF→Hand or BF→Library). */
    data class CardBounced(
        val cardId: ForgeCardId,
        val seatId: SeatId,
    ) : GameEvent

    /** A card was exiled (any zone → Exile).
     *  [sourceCardId] = the permanent that exiled this card (from Card.exiledWith),
     *  present only for "exile-under-permanent" effects (Banishing Light, Stasis Snare, etc.).
     *  When set, triggers [AnnotationBuilder.displayCardUnderCard] persistent annotation. */
    data class CardExiled(
        val cardId: ForgeCardId,
        val seatId: SeatId,
        val sourceCardId: ForgeCardId? = null,
        val fromBattlefield: Boolean = false,
    ) : GameEvent

    /** A card was discarded (Hand→GY). */
    data class CardDiscarded(
        val cardId: ForgeCardId,
        val seatId: SeatId,
    ) : GameEvent

    /** A card was milled (Library→GY). */
    data class CardMilled(
        val cardId: ForgeCardId,
        val seatId: SeatId,
        val sourceCardId: ForgeCardId? = null,
    ) : GameEvent

    /** A card was moved Library→Hand via a search effect (tutor, ChangeZone).
     *  Produces [TransferCategory.Put] instead of [TransferCategory.Draw].
     *  [sourceCardId] = host card of the search ability (for affectorId). */
    data class CardSearchedToHand(
        val cardId: ForgeCardId,
        val sourceCardId: ForgeCardId? = null,
    ) : GameEvent

    /** A card was surveiled to graveyard (Library→GY via surveil).
     *  [sourceCardId] = host card of the ability that caused the surveil
     *  (e.g. Wary Thespian). Used to resolve the ability's instanceId for affectorId. */
    data class CardSurveiled(
        val cardId: ForgeCardId,
        val seatId: SeatId,
        val sourceCardId: ForgeCardId? = null,
    ) : GameEvent

    /** A spell was countered (Stack→GY without resolving).
     *  Not wired from a dedicated Forge event — inferred from zone pair. */
    data class SpellCountered(
        val cardId: ForgeCardId,
        val seatId: SeatId,
    ) : GameEvent

    /** A token was created.
     *  Wired from GameEventTokenCreated (enriched with List<Card> tokens). */
    data class TokenCreated(
        val cardId: ForgeCardId,
        val seatId: SeatId,
        val sourceCardId: ForgeCardId? = null,
    ) : GameEvent

    /** A token was destroyed (left the battlefield).
     *  Wired from GameEventCardChangeZone when card.isToken && from=Battlefield. */
    data class TokenDestroyed(
        val cardId: ForgeCardId,
        val seatId: SeatId,
    ) : GameEvent

    // -- Group A+: attachment events --

    /** A card was attached to another permanent (aura enchanting, equipment equipping). */
    data class CardAttached(
        val cardId: ForgeCardId,
        val targetCardId: ForgeCardId,
        val seatId: SeatId,
    ) : GameEvent

    /** A card was detached from its target (aura falling off, equipment unequipped). */
    data class CardDetached(
        val cardId: ForgeCardId,
        val seatId: SeatId,
    ) : GameEvent

    // -- Group B: annotation-producing events --

    /** A mana ability was activated and produced mana.
     *  Wired from GameEventManaAbilityActivated (fires in AbilityManaPart.produceMana).
     *  Used to attach mana-ability annotations to Sacrifice zone transfers (Treasure tokens). */
    data class ManaAbilityActivated(
        val cardId: ForgeCardId,
        val seatId: SeatId,
        val produced: String,
    ) : GameEvent

    /** Counters added or removed on a card (+1/+1, loyalty, poison, stun, etc.). */
    data class CountersChanged(
        val cardId: ForgeCardId,
        val counterType: String,
        val oldCount: Int,
        val newCount: Int,
    ) : GameEvent

    /** A card's power or toughness changed (pump, anthem, equipment, SBA). */
    data class PowerToughnessChanged(
        val cardId: ForgeCardId,
        val oldPower: Int,
        val newPower: Int,
        val oldToughness: Int,
        val newToughness: Int,
    ) : GameEvent

    /** A card changed state (DFC transform, flip, modal face switch).
     *  [newStateName] is the Forge CardStateName after the change.
     *  Detected by [GameEventCollector] from [GameEventCardStatsChanged]. */
    data class CardTransformed(
        val cardId: ForgeCardId,
        val newStateName: forge.card.CardStateName,
    ) : GameEvent {
        /** Convenience — true when the card flipped to its back face. */
        val isBackSide: Boolean get() = newStateName == forge.card.CardStateName.Backside
    }

    /** A player's library was shuffled. */
    data class LibraryShuffled(
        val seatId: SeatId,
    ) : GameEvent

    /** A player scried (looked at top N, put some on top / some on bottom). */
    data class Scry(
        val seatId: SeatId,
        val topCount: Int,
        val bottomCount: Int,
    ) : GameEvent

    /** A player surveilled (looked at top N, put some in library / some in graveyard). */
    data class Surveil(
        val seatId: SeatId,
        val toLibrary: Int,
        val toGraveyard: Int,
    ) : GameEvent

    // -- Group B+: reveal events --
    // Not from EventBus — captured via InteractivePromptBridge.drainReveals()
    // in WebPlayerController.reveal() override.

    /** Cards were revealed to all players (e.g. draw-and-reveal, Explore, etc.). */
    data class CardsRevealed(
        val cardIds: List<ForgeCardId>,
        val ownerSeatId: SeatId,
    ) : GameEvent

    /** A permanent's controller changed (steal effect or revert).
     *  Fires both on steal (Claim the Firstborn) and on revert (end of turn).
     *  [sourceCardId] resolved later from events list (affectorSourceFromEvents pattern). */
    data class ControllerChanged(
        val cardId: ForgeCardId,
        val oldControllerSeatId: SeatId,
        val newControllerSeatId: SeatId,
    ) : GameEvent

    // -- Group C: combat enrichment --

    /** Combat phase ended — signal to clear combat state. */
    data object CombatEnded : GameEvent

    // -- Group D: phase/turn events --

    /** Phase or step changed. Wired from GameEventTurnPhase.
     *  [phase] and [step] are proto enum ordinals (Phase/Step from messages.proto). */
    data class PhaseChanged(
        val seatId: SeatId,
        val phase: Int,
        val step: Int,
    ) : GameEvent
}
