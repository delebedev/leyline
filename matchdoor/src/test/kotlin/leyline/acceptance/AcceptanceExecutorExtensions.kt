package leyline.acceptance

import forge.game.zone.ZoneType
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage

internal fun AcceptanceZone.toForgeZone(): ZoneType =
    when (this) {
        AcceptanceZone.Battlefield -> ZoneType.Battlefield
        AcceptanceZone.Hand -> ZoneType.Hand
        AcceptanceZone.Graveyard -> ZoneType.Graveyard
        AcceptanceZone.Exile -> ZoneType.Exile
        AcceptanceZone.Library -> ZoneType.Library
        AcceptanceZone.Sideboard -> ZoneType.Sideboard
    }

internal fun AcceptanceCastingTimeOption.toProtoType(): CastingTimeOptionType =
    when (this) {
        AcceptanceCastingTimeOption.Done -> CastingTimeOptionType.Done
        AcceptanceCastingTimeOption.Kicker -> CastingTimeOptionType.Kicker
        AcceptanceCastingTimeOption.AdditionalCost -> CastingTimeOptionType.AdditionalCost
        AcceptanceCastingTimeOption.Bargain -> CastingTimeOptionType.Bargain
        AcceptanceCastingTimeOption.Cleave -> CastingTimeOptionType.CastThroughAbility
        AcceptanceCastingTimeOption.Overload -> CastingTimeOptionType.CastThroughAbility
    }

internal fun GREToClientMessage.isPromptMessage(): Boolean =
    hasCastingTimeOptionsReq() ||
        hasDeclareAttackersReq() ||
        hasDeclareBlockersReq() ||
        hasGroupReq() ||
        hasPayCostsReq() ||
        hasSelectNReq() ||
        hasSelectTargetsReq()

internal fun GREToClientMessage.promptName(): String =
    when {
        hasCastingTimeOptionsReq() -> "CastingTimeOptionsReq"
        hasDeclareAttackersReq() -> "DeclareAttackersReq"
        hasDeclareBlockersReq() -> "DeclareBlockersReq"
        hasGroupReq() -> "GroupReq"
        hasPayCostsReq() -> "PayCostsReq"
        hasSelectNReq() -> "SelectNReq"
        hasSelectTargetsReq() -> "SelectTargetsReq"
        else -> "UnknownPrompt"
    }

internal fun GREToClientMessage.matchesPrompt(prompt: String): Boolean =
    when (prompt) {
        "CastingTimeOptionsReq" -> hasCastingTimeOptionsReq()
        "DeclareAttackersReq" -> hasDeclareAttackersReq()
        "DeclareBlockersReq" -> hasDeclareBlockersReq()
        "GroupReq" -> hasGroupReq()
        "PayCostsReq" -> hasPayCostsReq()
        "SelectNReq" -> hasSelectNReq()
        "SelectTargetsReq" -> hasSelectTargetsReq()
        else -> error("unknown prompt condition: $prompt")
    }

internal fun String.toForgePhaseName(): String =
    when (replace("-", "").replace(" ", "").uppercase()) {
        "MAIN1", "PRECOMBATMAIN" -> "MAIN1"
        "MAIN2", "POSTCOMBATMAIN" -> "MAIN2"
        "DECLAREATTACKERS", "COMBATDECLAREATTACKERS" -> "COMBAT_DECLARE_ATTACKERS"
        "DECLAREBLOCKERS", "COMBATDECLAREBLOCKERS" -> "COMBAT_DECLARE_BLOCKERS"
        "COMBATDAMAGE" -> "COMBAT_DAMAGE"
        else -> uppercase()
    }
