package leyline.game

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
        val forgeCardId: Int,
        val seatId: Int,
        val colorBitmasks: List<Int> = emptyList(),
    ) : GameEvent

    /** One mana globe spent to pay for a spell. */
    data class ManaPayment(
        val sourceForgeCardId: Int,
        val color: Int,
    )

    /** A spell or ability was cast (hand/battlefield → stack). */
    data class SpellCast(
        val forgeCardId: Int,
        val seatId: Int,
        val manaPayments: List<ManaPayment> = emptyList(),
    ) : GameEvent

    /** A spell was placed on the stack before costs were paid.
     *  Signals that this GSM should be split into QueuedGSM triplet.
     *  Wired from GameEventSpellMovedToStack. */
    data class SpellMovedToStack(
        val forgeCardId: Int,
        val seatId: Int,
    ) : GameEvent

    /** A spell or ability finished resolving (stack → battlefield/graveyard/exile). */
    data class SpellResolved(
        val forgeCardId: Int,
        val hasFizzled: Boolean,
    ) : GameEvent

    /** A card changed zones (generic — covers destroy, exile, sacrifice, bounce, etc.). */
    data class ZoneChanged(
        val forgeCardId: Int,
        val from: Zone,
        val to: Zone,
    ) : GameEvent

    /** A permanent was tapped or untapped. */
    data class CardTapped(
        val forgeCardId: Int,
        val tapped: Boolean,
    ) : GameEvent

    /** Damage was dealt to a creature. */
    data class DamageDealtToCard(
        val sourceForgeId: Int,
        val targetForgeId: Int,
        val amount: Int,
    ) : GameEvent

    /** Damage was dealt to a player. */
    data class DamageDealtToPlayer(
        val sourceForgeId: Int,
        val targetSeatId: Int,
        val amount: Int,
        val combat: Boolean,
    ) : GameEvent

    /** A player's life total changed. */
    data class LifeChanged(
        val seatId: Int,
        val oldLife: Int,
        val newLife: Int,
    ) : GameEvent

    /** Attackers were declared. */
    data class AttackersDeclared(
        val attackerForgeIds: List<Int>,
        val seatId: Int,
    ) : GameEvent

    /** Blockers were declared. */
    data class BlockersDeclared(
        val blockerForgeIds: List<Int>,
        val seatId: Int,
    ) : GameEvent

    // -- Group A: zone-transition disambiguation --
    // These replace generic ZoneChanged for specific zone pairs, enabling
    // direct category mapping without the zoneChangedCategory() fallback.

    /** A legendary permanent was put into graveyard by the legend rule SBA.
     *  More specific than [CardDestroyed] — produces `SBA_LegendRule` category. */
    data class LegendRuleDeath(
        val forgeCardId: Int,
        val seatId: Int,
    ) : GameEvent

    /** A permanent was destroyed (BF→GY, not sacrifice).
     *  [sourceForgeCardId] = host card of the ability that caused the destruction (for affectorId). */
    data class CardDestroyed(
        val forgeCardId: Int,
        val seatId: Int,
        val sourceForgeCardId: Int? = null,
    ) : GameEvent

    /** A permanent was sacrificed (BF→GY via sacrifice effect). */
    data class CardSacrificed(
        val forgeCardId: Int,
        val seatId: Int,
    ) : GameEvent

    /** A permanent was bounced (BF→Hand or BF→Library). */
    data class CardBounced(
        val forgeCardId: Int,
        val seatId: Int,
    ) : GameEvent

    /** A card was exiled (any zone → Exile).
     *  [sourceForgeCardId] = the permanent that exiled this card (from Card.exiledWith),
     *  present only for "exile-under-permanent" effects (Banishing Light, Stasis Snare, etc.).
     *  When set, triggers [AnnotationBuilder.displayCardUnderCard] persistent annotation. */
    data class CardExiled(
        val forgeCardId: Int,
        val seatId: Int,
        val sourceForgeCardId: Int? = null,
    ) : GameEvent

    /** A card was discarded (Hand→GY). */
    data class CardDiscarded(
        val forgeCardId: Int,
        val seatId: Int,
    ) : GameEvent

    /** A card was milled (Library→GY). */
    data class CardMilled(
        val forgeCardId: Int,
        val seatId: Int,
    ) : GameEvent

    /** A card was moved Library→Hand via a search effect (tutor, ChangeZone).
     *  Produces [TransferCategory.Put] instead of [TransferCategory.Draw].
     *  [sourceForgeCardId] = host card of the search ability (for affectorId). */
    data class CardSearchedToHand(
        val forgeCardId: Int,
        val sourceForgeCardId: Int? = null,
    ) : GameEvent

    /** A card was surveiled to graveyard (Library→GY via surveil).
     *  [sourceForgeCardId] = host card of the ability that caused the surveil
     *  (e.g. Wary Thespian). Used to resolve the ability's instanceId for affectorId. */
    data class CardSurveiled(
        val forgeCardId: Int,
        val seatId: Int,
        val sourceForgeCardId: Int? = null,
    ) : GameEvent

    /** A spell was countered (Stack→GY without resolving).
     *  Not wired from a dedicated Forge event — inferred from zone pair. */
    data class SpellCountered(
        val forgeCardId: Int,
        val seatId: Int,
    ) : GameEvent

    /** A token was created.
     *  Wired from GameEventTokenCreated (enriched with List<Card> tokens). */
    data class TokenCreated(
        val forgeCardId: Int,
        val seatId: Int,
        val sourceForgeCardId: Int? = null,
    ) : GameEvent

    /** A token was destroyed (left the battlefield).
     *  Wired from GameEventCardChangeZone when card.isToken && from=Battlefield. */
    data class TokenDestroyed(
        val forgeCardId: Int,
        val seatId: Int,
    ) : GameEvent

    // -- Group A+: attachment events --

    /** A card was attached to another permanent (aura enchanting, equipment equipping). */
    data class CardAttached(
        val forgeCardId: Int,
        val targetForgeId: Int,
        val seatId: Int,
    ) : GameEvent

    /** A card was detached from its target (aura falling off, equipment unequipped). */
    data class CardDetached(
        val forgeCardId: Int,
        val seatId: Int,
    ) : GameEvent

    // -- Group B: annotation-producing events --

    /** A mana ability was activated and produced mana.
     *  Wired from GameEventManaAbilityActivated (fires in AbilityManaPart.produceMana).
     *  Used to attach mana-ability annotations to Sacrifice zone transfers (Treasure tokens). */
    data class ManaAbilityActivated(
        val forgeCardId: Int,
        val seatId: Int,
        val produced: String,
    ) : GameEvent

    /** Counters added or removed on a card (+1/+1, loyalty, poison, stun, etc.). */
    data class CountersChanged(
        val forgeCardId: Int,
        val counterType: String,
        val oldCount: Int,
        val newCount: Int,
    ) : GameEvent

    /** A card's power or toughness changed (pump, anthem, equipment, SBA). */
    data class PowerToughnessChanged(
        val forgeCardId: Int,
        val oldPower: Int,
        val newPower: Int,
        val oldToughness: Int,
        val newToughness: Int,
    ) : GameEvent

    /** A player's library was shuffled. */
    data class LibraryShuffled(
        val seatId: Int,
    ) : GameEvent

    /** A player scried (looked at top N, put some on top / some on bottom). */
    data class Scry(
        val seatId: Int,
        val topCount: Int,
        val bottomCount: Int,
    ) : GameEvent

    /** A player surveilled (looked at top N, put some in library / some in graveyard). */
    data class Surveil(
        val seatId: Int,
        val toLibrary: Int,
        val toGraveyard: Int,
    ) : GameEvent

    // -- Group B+: reveal events --
    // Not from EventBus — captured via InteractivePromptBridge.drainReveals()
    // in WebPlayerController.reveal() override.

    /** Cards were revealed to all players (e.g. draw-and-reveal, Explore, etc.). */
    data class CardsRevealed(
        val forgeCardIds: List<Int>,
        val ownerSeatId: Int,
    ) : GameEvent

    // -- Group C: combat enrichment --

    /** Combat phase ended — signal to clear combat state. */
    data object CombatEnded : GameEvent

    // -- Group D: phase/turn events --

    /** Phase or step changed. Wired from GameEventTurnPhase.
     *  [phase] and [step] are proto enum ordinals (Phase/Step from messages.proto). */
    data class PhaseChanged(
        val seatId: Int,
        val phase: Int,
        val step: Int,
    ) : GameEvent
}
