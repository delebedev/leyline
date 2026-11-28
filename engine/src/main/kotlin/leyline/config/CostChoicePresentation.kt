package leyline.config

import kotlinx.serialization.Serializable

/**
 * Choice label policy selected by the host for the match's lifetime.
 * [Native] retains numeric prompt bindings and enum-list values; [ForgeText]
 * carries Forge-formatted cost-branch descriptions as non-localized strings,
 * and offers a type choice with a type outside the enum list as named labels.
 * The cost descriptions are not recalculated total-mana quotes, and do not
 * establish native renderer support.
 */
@Serializable
enum class CostChoicePresentation {
    Native,
    ForgeText,
}
