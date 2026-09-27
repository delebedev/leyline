package leyline.native.frontdoor.service

/** Stub responses for unimplemented lobby endpoints. Each graduates to a real service when implemented. */
@Suppress(
    "FunctionOnlyReturningConstant",
    "MaxLineLength",
    "ktlint:standard:max-line-length",
)
object LobbyStubs {
    fun activeMatches() = """{"MatchesV3":[]}"""

    fun carousel() =
        """[{"Name":"Play","Priority":1,"AssetTreeItem":"URL_MTGA_Decklists","TitleKey":"Events/Event_Title_Play","DescriptionKey":"Events/Event_Desc_Play","Actions":[{"Arguments":"Play","Type":"GoToEvent"}],"HoverActions":[]}]"""

    fun currencies() = """{"Currencies":[]}"""

    fun boosters() = """{"Boosters":[]}"""

    fun quests() = """{"Quests":[]}"""

    fun periodicRewards() = """{}"""

    fun cosmetics() = """{"Cosmetics":[]}"""

    fun netDeckFolders() = """[]"""

    fun playerInbox() = """{"Messages":[]}"""

    fun staticContent() = """{}"""

    fun storeStatus() = """{"DisabledTags":[],"DisabledListings":[],"CodeRedemptionEnabled":false,"StoreEnabled":false}"""

    fun entitlements() = """{"InventoryInfo":{}}"""

    fun skusAndListings() = """{"SkusCacheVersionHash":"","Skus":{},"ListingCacheVersionsHash":{},"Listings":{}}"""

    fun rankSeasonDetails() = """{}"""

    fun preferredPrintings() = """{}"""

    fun prizeWalls() = """{"ActivePrizeWalls":[]}"""

    fun rankInfo() =
        """{"playerId":null,"constructedSeasonOrdinal":0,"constructedClass":"Bronze","constructedLevel":0,"constructedStep":0,"constructedMatchesWon":0,"constructedMatchesLost":0,"constructedMatchesDrawn":0,"limitedSeasonOrdinal":0,"limitedClass":"Bronze","limitedLevel":0,"limitedStep":0,"limitedMatchesWon":0,"limitedMatchesLost":0,"limitedMatchesDrawn":0}"""

    fun telemetryAck() = "Success"

    fun killSwitches() = """{"KillSwitches":{},"UxKillSwitches":{}}"""
}
