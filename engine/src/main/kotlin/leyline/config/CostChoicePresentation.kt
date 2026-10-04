package leyline.config

import kotlinx.serialization.Serializable

/** Alternate additional-cost labels selected once by the match host. */
@Serializable
enum class CostChoicePresentation {
    Native,
    ForgeText,
}
