package leyline.config

import kotlinx.serialization.Serializable

/** Cast-cost labels selected once by the match host. */
@Serializable
enum class CostChoicePresentation {
    Native,
    ForgeText,
}
