package leyline.config

import kotlinx.serialization.Serializable

/**
 * Cast-cost label policy selected by the host for the match's lifetime.
 * [Native] retains numeric prompt bindings; [ForgeText] carries Forge-formatted
 * branch descriptions as non-localized strings. Those descriptions are not
 * recalculated total-mana quotes, and do not establish native renderer support.
 */
@Serializable
enum class CostChoicePresentation {
    Native,
    ForgeText,
}
