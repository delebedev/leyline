package leyline.testkit

import forge.card.CardStateName
import forge.game.zone.ZoneType
import leyline.tooling.headless.AdvanceGoal
import leyline.tooling.headless.ClientStateSnapshot
import leyline.tooling.headless.HeadlessCard
import leyline.tooling.headless.HeadlessMatch
import leyline.tooling.headless.MatchCheckpoint
import leyline.tooling.headless.MatchIntent
import leyline.tooling.headless.MatchResult
import leyline.tooling.headless.SpellZone
import leyline.tooling.headless.cardNameByGrpId
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionsReq
import wotc.mtgo.gre.external.messaging.Messages.DamageRecipient
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GroupReq
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import wotc.mtgo.gre.external.messaging.Messages.OrderReq
import wotc.mtgo.gre.external.messaging.Messages.SelectNReq
import leyline.tooling.headless.actionMatchesAlternative as headlessActionMatchesAlternative
import leyline.tooling.headless.cardGrpId as headlessCardGrpId
import leyline.tooling.headless.enabledStops as headlessEnabledStops
import leyline.tooling.headless.keywordAbilityGrpId as headlessKeywordAbilityGrpId

fun HeadlessMatch.cardGrpId(cardName: String): Int? = this.headlessCardGrpId(cardName)

fun HeadlessMatch.keywordAbilityGrpId(
    cardName: String,
    keywordAbilityId: Int,
): Int? = this.headlessKeywordAbilityGrpId(cardName, keywordAbilityId)

fun HeadlessMatch.keywordAbilityGrpId(
    cardGrpId: Int,
    keywordAbilityId: Int,
): Int? = this.headlessKeywordAbilityGrpId(cardGrpId, keywordAbilityId)

fun HeadlessMatch.actionMatchesAlternative(
    action: Action,
    keywordAbilityId: Int,
): Boolean = this.headlessActionMatchesAlternative(action, keywordAbilityId)

fun HeadlessMatch.enabledStops(seat: Int = 1): Set<String> = this.headlessEnabledStops(seat)

/** Semantic test vocabulary over [HeadlessMatch]. No runtime handle is exposed. */
val HeadlessMatch.allMessages: List<GREToClientMessage> get() = observe().messages

val HeadlessMatch.allRawMessages get() = observe().rawMessages

fun HeadlessMatch.isGameOver(): Boolean = observe().gameOver

fun HeadlessMatch.phase(): String? = observe().phase

fun HeadlessMatch.turn(): Int = observe().turn ?: 0

fun HeadlessMatch.isAiTurn(): Boolean = observe().aiTurn

fun HeadlessMatch.hasPendingAction(): Boolean = observe().pendingAction

fun HeadlessMatch.messageSnapshot(): Int = observe().messages.size

fun HeadlessMatch.messagesSince(snapshot: Int): List<GREToClientMessage> = messagesSince(MatchCheckpoint(snapshot))

fun HeadlessMatch.drainSink(): MatchResult = submit(MatchIntent.Flush)

fun HeadlessMatch.passPriority(): MatchResult = submit(MatchIntent.PassPriority)

fun HeadlessMatch.playLand(name: String? = null): Boolean = submit(MatchIntent.PlayLand(name)).accepted

@Suppress("ElseCaseInsteadOfExhaustiveWhen")
fun HeadlessMatch.castSpellByName(
    cardName: String,
    zone: ZoneType = ZoneType.Hand,
    alternativeGrpId: Int? = null,
): Boolean =
    submit(
        MatchIntent.CastSpell(
            cardName = cardName,
            zone =
                when (zone) {
                    ZoneType.Hand -> SpellZone.Hand
                    ZoneType.Graveyard -> SpellZone.Graveyard
                    ZoneType.Exile -> SpellZone.Exile
                    ZoneType.Battlefield -> SpellZone.Battlefield
                    else -> error("Unsupported spell zone: $zone")
                },
            alternativeGrpId = alternativeGrpId,
        ),
    ).accepted

fun HeadlessMatch.castFromGraveyard(cardName: String): Boolean = castSpellByName(cardName, ZoneType.Graveyard)

fun HeadlessMatch.castFromExile(cardName: String): Boolean = castSpellByName(cardName, ZoneType.Exile)

fun HeadlessMatch.activateAbility(
    cardName: String,
    abilityIndex: Int = 0,
    selectedColor: ManaColor? = null,
): Boolean = submit(MatchIntent.ActivateAbility(cardName, SpellZone.Battlefield, abilityIndex, selectedColor)).accepted

fun HeadlessMatch.activateMana(
    cardName: String,
    abilityIndex: Int = 0,
    selectedColor: ManaColor? = null,
): Boolean = submit(MatchIntent.ActivateMana(cardName, abilityIndex, selectedColor)).accepted

fun HeadlessMatch.activateAbilityFromHand(
    cardName: String,
    abilityIndex: Int = 0,
): Boolean = submit(MatchIntent.ActivateAbility(cardName, SpellZone.Hand, abilityIndex)).accepted

fun HeadlessMatch.activateAbilityFromGraveyard(
    cardName: String,
    abilityIndex: Int = 0,
): Boolean = submit(MatchIntent.ActivateAbility(cardName, SpellZone.Graveyard, abilityIndex)).accepted

fun HeadlessMatch.resolveSpell(cardName: String): Boolean {
    if (!castSpellByName(cardName)) return false
    passUntilResolved()
    return true
}

fun HeadlessMatch.instanceIdOf(
    cardName: String,
    seat: HeadlessSeat = human,
    zone: ZoneType = ZoneType.Battlefield,
): Int = findInstanceId(cardName, seat.seat, zone) ?: error("No $cardName in ${zone.name}")

fun HeadlessMatch.instanceIdOf(cardName: String): Int =
    findInstanceId(cardName, 1) ?: findInstanceId(cardName, 2) ?: error("No $cardName in observed cards")

fun HeadlessMatch.gameStateMessagesSince(snapshot: Int) =
    messagesSince(snapshot)
        .filter {
            it.hasGameStateMessage()
        }.map { it.gameStateMessage }

fun HeadlessMatch.respondToGroupReq(
    awayInstanceIds: List<Int>,
    allInstanceIds: List<Int>,
): MatchResult = submit(MatchIntent.Group(awayInstanceIds, allInstanceIds))

fun HeadlessMatch.respondToScry(
    bottomInstanceIds: List<Int>,
    allInstanceIds: List<Int>,
): MatchResult = submit(MatchIntent.Scry(bottomInstanceIds, allInstanceIds))

fun HeadlessMatch.castSpellUntilGroupReq(
    cardName: String,
    advanceAfterCast: HeadlessMatch.() -> Unit = { passPriority() },
): wotc.mtgo.gre.external.messaging.Messages.GroupReq =
    castSpellUntil(cardName, advanceAfterCast) { it.takeIf { msg -> msg.hasGroupReq() }?.groupReq }
        ?: error("No GroupReq after casting $cardName")

fun HeadlessMatch.castSpellUntilCastingTimeOptionsReq(
    cardName: String,
    advanceAfterCast: HeadlessMatch.() -> Unit = { passPriority() },
): wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionsReq =
    castSpellUntil(cardName, advanceAfterCast) { it.takeIf { msg -> msg.hasCastingTimeOptionsReq() }?.castingTimeOptionsReq }
        ?: error("No CastingTimeOptionsReq after casting $cardName")

fun HeadlessMatch.castSpellUntilSelectNReq(
    cardName: String,
    advanceAfterCast: HeadlessMatch.() -> Unit = { passPriority() },
): wotc.mtgo.gre.external.messaging.Messages.SelectNReq =
    castSpellUntil(cardName, advanceAfterCast) { it.takeIf { msg -> msg.hasSelectNReq() }?.selectNReq }
        ?: error("No SelectNReq after casting $cardName")

fun <T> HeadlessMatch.castSpellUntil(
    cardName: String,
    advanceAfterCast: HeadlessMatch.() -> Unit = { passPriority() },
    extract: (GREToClientMessage) -> T?,
): T? {
    check(castSpellByName(cardName)) { "Could not cast $cardName" }
    advanceAfterCast()
    return allMessages.asReversed().firstNotNullOfOrNull(extract)
}

val HeadlessMatch.accumulator: ClientStateSnapshot get() = observe().client

fun HeadlessMatch.latestPromptMsgId(): Int = observe().latestPromptMsgId

fun HeadlessMatch.latestPromptGsId(): Int = observe().latestPromptGsId

fun HeadlessMatch.cardName(instanceId: Int): String =
    cardByIid(instanceId)?.name
        ?: observe()
            .client.objects[instanceId]
            ?.let { cardNameByGrpId(it.grpId) }
        ?: error("No card with instance id $instanceId")

fun HeadlessMatch.annotationsSince(snapshot: Int) =
    messagesSince(snapshot).flatMap { msg -> if (msg.hasGameStateMessage()) msg.gameStateMessage.annotationsList else emptyList() }

fun HeadlessMatch.deselectAttackers(ids: List<Int>): List<GREToClientMessage> {
    val checkpoint = checkpoint()
    submit(MatchIntent.DeselectAttackers(ids))
    return messagesSince(checkpoint)
}

fun HeadlessMatch.submitAction(action: Action): MatchResult = submit(MatchIntent.Action(action))

fun HeadlessMatch.submitAction(message: wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage): MatchResult =
    submit(MatchIntent.Action(message.performActionResp.actionsList.single()))

fun HeadlessMatch.selectTargets(ids: List<Int>): MatchResult = submit(MatchIntent.Targets(ids))

fun HeadlessMatch.selectTargetsIterative(ids: List<Int>): MatchResult = submit(MatchIntent.TargetsIterative(ids))

fun HeadlessMatch.unselectTargets(ids: List<Int>): MatchResult = submit(MatchIntent.UnselectTargets(ids))

fun HeadlessMatch.addIntrinsicKeyword(
    instanceId: Int,
    keyword: String,
): MatchResult = submit(MatchIntent.AddIntrinsicKeyword(instanceId, keyword))

fun HeadlessMatch.addStaticAbility(
    instanceId: Int,
    script: String,
): MatchResult = submit(MatchIntent.AddStaticAbility(instanceId, script))

fun HeadlessMatch.submitTargets(): MatchResult = submit(MatchIntent.SubmitTargets)

fun HeadlessMatch.cancelAction(): MatchResult = submit(MatchIntent.CancelAction)

fun HeadlessMatch.declareAttackers(ids: List<Int>): MatchResult = submit(MatchIntent.Attackers(ids))

fun HeadlessMatch.declareAttackersWithoutRecipients(ids: List<Int>): MatchResult = submit(MatchIntent.AttackersWithoutRecipients(ids))

fun HeadlessMatch.declareAttackers(
    ids: List<Int>,
    damageRecipients: Map<Int, DamageRecipient>,
): MatchResult = submit(MatchIntent.Attackers(ids, damageRecipients))

fun HeadlessMatch.toggleAttackers(
    ids: List<Int>,
    alternatives: Map<Int, Int> = emptyMap(),
    damageRecipients: Map<Int, DamageRecipient> = emptyMap(),
): List<GREToClientMessage> {
    val checkpoint = checkpoint()
    submit(MatchIntent.ToggleAttackers(ids, alternatives, damageRecipients))
    return messagesSince(checkpoint)
}

fun HeadlessMatch.declareNoAttackers(): MatchResult = submit(MatchIntent.NoAttackers)

fun HeadlessMatch.declareAllAttackers(): MatchResult = submit(MatchIntent.AllAttackers)

fun HeadlessMatch.submitAttackers(): MatchResult = submit(MatchIntent.SubmitAttackers)

fun HeadlessMatch.declareBlockers(assignments: Map<Int, Int>): MatchResult = submit(MatchIntent.Blockers(assignments))

fun HeadlessMatch.declareNoBlockers(): MatchResult = submit(MatchIntent.NoBlockers)

fun HeadlessMatch.toggleBlockers(assignments: Map<Int, Int>): List<GREToClientMessage> {
    val checkpoint = checkpoint()
    submit(MatchIntent.ToggleBlockers(assignments))
    return messagesSince(checkpoint)
}

fun HeadlessMatch.deselectBlocker(blockerInstanceId: Int): List<GREToClientMessage> {
    val checkpoint = checkpoint()
    submit(MatchIntent.DeselectBlocker(blockerInstanceId))
    return messagesSince(checkpoint)
}

fun HeadlessMatch.submitBlockers(): MatchResult = submit(MatchIntent.SubmitBlockers)

fun HeadlessMatch.assignDamage(assigners: List<Pair<Int, List<Pair<Int, Int>>>>): MatchResult =
    submit(MatchIntent.DamageAssignment(assigners))

fun HeadlessMatch.respondToSelectN(ids: List<Int>): MatchResult = submit(MatchIntent.SelectN(ids))

fun HeadlessMatch.respondToOrder(ids: List<Int>): MatchResult = submit(MatchIntent.Order(ids))

fun HeadlessMatch.respondToSearch(ids: List<Int>): MatchResult = submit(MatchIntent.Search(ids))

fun HeadlessMatch.respondToEffectCost(ids: List<Int>): MatchResult = submit(MatchIntent.EffectCost(ids))

fun HeadlessMatch.respondToGatherCounters(gatherings: List<Pair<Int, Int>>): MatchResult = submit(MatchIntent.GatherCounters(gatherings))

fun HeadlessMatch.respondModalChoice(ids: List<Int>): MatchResult = submit(MatchIntent.ModalChoice(ids))

fun HeadlessMatch.respondToOptionalCost(ctoId: Int): MatchResult = submit(MatchIntent.OptionalCost(ctoId))

fun HeadlessMatch.respondToAlternateCost(
    ctoId: Int,
    optionIndex: Int,
): MatchResult = submit(MatchIntent.AlternateCost(ctoId, optionIndex))

fun HeadlessMatch.respondToManaTypeChoices(choices: List<Pair<Int, ManaColor>>): MatchResult = submit(MatchIntent.ManaTypeChoices(choices))

fun HeadlessMatch.respondToOptionalAction(accept: Boolean): MatchResult = submit(MatchIntent.OptionalAction(accept))

fun HeadlessMatch.respondToNumericInput(value: Int): MatchResult = submit(MatchIntent.NumericInput(value))

fun HeadlessMatch.holdNextOptionalAction(): MatchResult = submit(MatchIntent.HoldNextOptionalAction)

fun HeadlessMatch.sendSettings(vararg stops: wotc.mtgo.gre.external.messaging.Messages.Stop): MatchResult =
    submit(MatchIntent.Settings(stops.toList()))

fun HeadlessMatch.setAutoPass(option: wotc.mtgo.gre.external.messaging.Messages.AutoPassOption): MatchResult =
    submit(MatchIntent.AutoPass(option))

fun HeadlessMatch.declineNextOptionalAction(): MatchResult = submit(MatchIntent.DeclineNextOptionalAction)

fun HeadlessMatch.passUntil(
    maxPasses: Int = 20,
    stopWhen: HeadlessMatch.() -> Boolean,
): Boolean {
    repeat(maxPasses) {
        if (isGameOver() || stopWhen()) return true
        when (phase()) {
            "COMBAT_DECLARE_ATTACKERS" -> submit(MatchIntent.NoAttackers)
            "COMBAT_DECLARE_BLOCKERS" -> submit(MatchIntent.NoBlockers)
            else -> submit(MatchIntent.PassPriority)
        }
    }
    return isGameOver() || stopWhen()
}

fun HeadlessMatch.passUntilResolved(maxPasses: Int = 10) {
    advance(AdvanceGoal.Resolved(maxPasses))
}

fun HeadlessMatch.passUntilTurn(
    targetTurn: Int,
    maxPasses: Int = 30,
) {
    advance(AdvanceGoal.UntilTurn(targetTurn, maxPasses))
}

fun HeadlessMatch.passThroughCombat(
    startTurn: Int = turn(),
    maxPasses: Int = 15,
) {
    advance(AdvanceGoal.ThroughCombat(startTurn, maxPasses))
}

fun HeadlessMatch.advanceToPhase(
    phase: String,
    turn: Int? = null,
): MatchResult = advance(AdvanceGoal.Phase(phase, turn))

fun HeadlessMatch.advanceToMain1(): MatchResult = advance(AdvanceGoal.Main1)

fun HeadlessMatch.advanceToCombat(turn: Int? = null): MatchResult = advance(AdvanceGoal.Combat(turn))

fun HeadlessMatch.advanceToMain2(turn: Int? = null): MatchResult = advance(AdvanceGoal.Main2(turn))

fun HeadlessMatch.triggerAutoPass(): MatchResult = advance(AdvanceGoal.TriggerAutoPass)

fun HeadlessMatch.humanBattlefieldCreatures(): List<Pair<Int, String>> =
    observe().cards.filter { it.seat == 1 && it.zone == ZoneType.Battlefield.name && it.power != null }.map { it.instanceId to it.name }

fun HeadlessMatch.cardByIid(iid: Int): HeadlessCard? = observe().cards.firstOrNull { it.instanceId == iid }

fun HeadlessMatch.findInstanceId(
    cardName: String,
    seat: Int = 1,
    zone: ZoneType? = null,
): Int? = observe().cards.firstOrNull { it.seat == seat && it.name == cardName && (zone == null || it.zone == zone.name) }?.instanceId

fun HeadlessMatch.findInstanceId(
    ids: List<Int>,
    cardName: String,
): Int = ids.firstOrNull { cardByIid(it)?.name == cardName } ?: error("No $cardName among $ids")

fun HeadlessMatch.lastSelectNReq(): SelectNReq = allMessages.last { it.hasSelectNReq() }.selectNReq

fun HeadlessMatch.lastGroupReq(): GroupReq = allMessages.last { it.hasGroupReq() }.groupReq

fun HeadlessMatch.lastOrderReq(): OrderReq = allMessages.last { it.hasOrderReq() }.orderReq

fun HeadlessMatch.lastCastingTimeOptionsReq(): CastingTimeOptionsReq =
    allMessages.last { it.hasCastingTimeOptionsReq() }.castingTimeOptionsReq

fun HeadlessMatch.takeConsumedPromptMsgIds(): List<Int> = observe().consumedPromptMsgIds

data class HeadlessSeat(
    val match: HeadlessMatch,
    val seat: Int,
) {
    val id: Int get() = seat
    val life: Int get() =
        match
            .observe()
            .client.players[seat]
            ?.lifeTotal ?: 0
    val poisonCounters: Int get() = match.observe().poisonCountersBySeat[seat] ?: 0

    fun cards(zone: ZoneType): List<HeadlessCard> = match.observe().cards.filter { it.seat == seat && it.zone == zone.name }

    fun getZone(zone: ZoneType): HeadlessZone = HeadlessZone(match, seat, zone)

    fun hasCard(
        name: String,
        zone: ZoneType,
    ): Boolean = cards(zone).any { it.name == name }

    fun hasCardAnywhereExceptHand(name: String): Boolean =
        match.observe().cards.any { it.seat == seat && it.name == name && it.zone != ZoneType.Hand.name }

    val battlefield: HeadlessZone get() = getZone(ZoneType.Battlefield)
    val hand: HeadlessZone get() = getZone(ZoneType.Hand)
    val graveyard: HeadlessZone get() = getZone(ZoneType.Graveyard)
    val exile: HeadlessZone get() = getZone(ZoneType.Exile)
    val library: HeadlessZone get() = getZone(ZoneType.Library)
}

data class HeadlessZone(
    private val match: HeadlessMatch,
    private val seat: Int,
    private val zone: ZoneType,
) {
    val cards: List<HeadlessCard> get() = match.observe().cards.filter { it.seat == seat && it.zone == zone.name }

    fun card(name: String): HeadlessCard = cards.first { it.name == name }

    fun hasCard(name: String): Boolean = cards.any { it.name == name }

    fun iid(name: String): Int = cards.firstOrNull { it.name == name }?.instanceId ?: error("No $name in ${zone.name}")

    fun iid(card: HeadlessCard): Int = card.instanceId

    fun iids(vararg names: String): List<Int> = names.map(::iid)

    fun size(): Int = cards.size
}

val HeadlessMatch.human: HeadlessSeat get() = HeadlessSeat(this, 1)
val HeadlessMatch.ai: HeadlessSeat get() = HeadlessSeat(this, 2)

fun HeadlessSeat.hasWon(): Boolean = match.observe().gameOver && life > 0

fun HeadlessSeat.hasLost(): Boolean = match.observe().gameOver && life <= 0

fun HeadlessMatch.assertConsistent() {
    check(observe().client.actionInstanceIdsMissingFromObjects().isEmpty())
}

fun HeadlessMatch.assertConsistent(label: String) {
    try {
        assertConsistent()
    } catch (error: IllegalStateException) {
        error.message?.let { message -> error("$label: $message") } ?: throw error
    }
}

val HeadlessCard.netPower: Int get() = power ?: 0
val HeadlessCard.netToughness: Int get() = toughness ?: 0
val HeadlessCard.isFaceDown: Boolean get() = faceDown
val HeadlessCard.isCloaked: Boolean get() = faceDown
val HeadlessCard.currentLoyalty: Int get() = power ?: 0
val HeadlessCard.id: Int get() = instanceId
val HeadlessCard.isCreature: Boolean get() = power != null

fun HeadlessCard.getCounters(counter: forge.game.card.CounterEnumType): Int =
    counters[counter.getName()] ?: counters[counter.name] ?: counters[counter.toString()] ?: 0

val HeadlessCard.cardTypesList: List<String> get() = cardTypes.toList()
val HeadlessCard.isBackSide: Boolean get() = faceDown || currentStateName == CardStateName.Backside

fun HeadlessCard.hasKeyword(keyword: String): Boolean = keywords.any { it.startsWith(keyword, ignoreCase = true) }

fun HeadlessCard.isAttachedToEntity(other: HeadlessCard): Boolean = attachedToInstanceId == other.instanceId

fun HeadlessCard.hasSickness(): Boolean = hasSickness

fun HeadlessCard.hasSVar(name: String): Boolean = name in sVars
