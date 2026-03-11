package leyline.frontdoor.wire

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import leyline.frontdoor.domain.Deck
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Builds the StartHook response (CmdType 1) from scratch with synthetic data.
 *
 * DeckSummariesV2 and Decks are built from the player's DB decks.
 * Everything else is static synthetic state appropriate for a local
 * playtesting server — generous inventory, default cosmetics, no
 * achievements or progression.
 */
object StartHookBuilder {

    private val ISO_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSS'+00:00'")

    fun build(decks: List<Deck>): String {
        val now = Instant.now().atOffset(ZoneOffset.UTC).format(ISO_FORMAT)
        val json = buildJsonObject {
            put("InventoryInfo", inventoryInfo())
            put(
                "DeckSummariesV2",
                buildJsonArray {
                    decks.forEach { add(DeckWireBuilder.toV2Summary(it)) }
                },
            )
            put(
                "Decks",
                buildJsonObject {
                    for (d in decks) put(d.id.value, DeckWireBuilder.toStartHookEntry(d))
                },
            )
            put("SystemMessages", buildJsonArray {})
            put("PreferredCosmetics", preferredCosmetics())
            put("HomePageAchievements", buildJsonObject {})
            put("DeckLimit", 100)
            put("TimewalkInfo", buildJsonObject {})
            put("TokenDefinitions", buildJsonArray {})
            put("KillSwitchNotification", killSwitches())
            put(
                "CardMetadataInfo",
                buildJsonObject {
                    put("NonCraftableCardList", buildJsonArray {})
                    put("NonCollectibleCardList", buildJsonArray {})
                    put("UnreleasedSets", buildJsonArray {})
                },
            )
            put("ClientPeriodicRewards", clientPeriodicRewards(now))
            put("UpdatedGraphs", buildJsonObject {})
            put("ServerTime", now)
        }
        return json.toString()
    }

    private fun inventoryInfo() = buildJsonObject {
        put("SeqId", 1)
        put("Changes", buildJsonArray {})
        put("Gems", 99999)
        put("Gold", 99999)
        put("TotalVaultProgress", 0)
        put("WildCardCommons", 999)
        put("WildCardUnCommons", 999)
        put("WildCardRares", 999)
        put("WildCardMythics", 999)
        put("CustomTokens", buildJsonObject {})
        put("Boosters", buildJsonArray {})
        put("Vouchers", buildJsonObject {})
        put("PrizeWallsUnlocked", buildJsonArray {})
        put(
            "Cosmetics",
            buildJsonObject {
                put("ArtStyles", buildJsonArray {})
                put("Avatars", buildJsonArray {})
                put("Pets", buildJsonArray {})
                put("Sleeves", buildJsonArray {})
                put("Emotes", buildJsonArray {})
                put("Titles", buildJsonArray {})
            },
        )
    }

    private fun preferredCosmetics() = buildJsonObject {
        put(
            "Avatar",
            buildJsonObject {
                put("Type", "Avatar")
                put("Id", "Avatar_Basic_Adventurer")
            },
        )
        put(
            "Sleeve",
            buildJsonObject {
                put("Type", "Sleeve")
                put("Id", "")
            },
        )
        put(
            "Pet",
            buildJsonObject {
                put("Type", "Pet")
                put("Id", "")
            },
        )
        put(
            "Emotes",
            buildJsonArray {
                for ((id, category) in DEFAULT_EMOTES) {
                    add(
                        buildJsonObject {
                            put("Type", "Emote")
                            put("AcquisitionFlags", "DefaultLoginGrant")
                            put("Id", id)
                            put("Category", category)
                            put("Treatment", "")
                            put("AcquisitionFlagsInternal", 1)
                        },
                    )
                }
            },
        )
    }

    private fun killSwitches() = buildJsonObject {
        put(
            "KillSwitches",
            buildJsonObject {
                put("AchievementsStatus", false)
                put("MatchAchievementsToastStatus", false)
                put("AchievementSceneStatus", false)
                put("TitlesStatus", false)
                put("BinaryMessaging", false)
                put("ChallengeStatus", false)
            },
        )
        put(
            "UxKillSwitches",
            buildJsonObject {
                put("AchievementSceneStatus", false)
                put("TitlesStatus", false)
            },
        )
    }

    private fun clientPeriodicRewards(now: String) = buildJsonObject {
        put("_dailyRewardResetTimestamp", now)
        put("_weeklyRewardResetTimestamp", now)
        put(
            "_dailyRewardChestDescriptions",
            buildJsonObject {
                for (i in 1..15) {
                    put(
                        i.toString(),
                        buildJsonObject {
                            put("image1", "ObjectiveIcon_MasteryXP")
                            put("prefab", "RewardPopup3DIcon_XP")
                            put("headerLocKey", "EPP/Level/XP")
                            put("locParams", buildJsonObject { put("number1", 25) })
                        },
                    )
                }
            },
        )
        put(
            "_weeklyRewardChestDescriptions",
            buildJsonObject {
                for (i in 1..15) {
                    put(
                        i.toString(),
                        buildJsonObject {
                            put("image1", "ObjectiveIcon_MasteryXP")
                            put("prefab", "RewardPopup3DIcon_XP")
                            put("headerLocKey", "EPP/Level/XP")
                            put("locParams", buildJsonObject { put("number1", 250) })
                        },
                    )
                }
            },
        )
    }

    /** Client expects exactly these 5 default emotes for the emote wheel. */
    private val DEFAULT_EMOTES = listOf(
        "Phrase_Basic_Hello" to "Greeting",
        "Phrase_Basic_Nice_Thanks" to "Kudos",
        "Phrase_Basic_Thinking_YourGo" to "Priority",
        "Phrase_Basic_Oops_Sorry" to "Accident",
        "Phrase_Basic_GoodGame" to "GoodGame",
    )
}
