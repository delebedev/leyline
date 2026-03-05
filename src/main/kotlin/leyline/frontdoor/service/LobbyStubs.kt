package leyline.frontdoor.service

/** Stub responses for unimplemented lobby endpoints. Each graduates to a real service when implemented. */
object LobbyStubs {
    fun activeMatches() = """{"MatchesV3":[]}"""
    fun courses() = """{"Courses":[]}"""
    fun currencies() = """{"Currencies":[]}"""
    fun boosters() = """{"Boosters":[]}"""
    fun quests() = """{"Quests":[]}"""
    fun periodicRewards() = """{}"""
    fun cosmetics() = """{"Cosmetics":[]}"""
    fun netDeckFolders() = """[]"""
    fun playerInbox() = """{"Messages":[]}"""
    fun staticContent() = """{}"""

    /** Starter card collection: 4x each of 10 real cards. Enough for deck editor to load. */
    fun cardCollection(): String {
        // Real grpIds: Plains, Forest, Serra Angel, Llanowar Elves, Elvish Mystic,
        // Giant Growth, Lightning Bolt, Pacifism, Banishing Light, Ajani's Pridemate
        val grpIds = listOf(75515, 95189, 93860, 93940, 93941, 93942, 93848, 93715, 75516, 93943)
        val cards = grpIds.joinToString(",") { "\"$it\":4" }
        return """{"cacheVersion":-1,"cards":{$cards}}"""
    }
    fun storeStatus() = """{"CatalogStatus":[]}"""
    fun rankSeasonDetails() = """{}"""
    fun carousel() = """[]"""
    fun preferredPrintings() = """{}"""
    fun prizeWalls() = """{"ActivePrizeWalls":[]}"""
    fun rankInfo() = """{"playerId":null,"constructedSeasonOrdinal":0,"constructedClass":"Bronze","constructedLevel":0,"constructedStep":0,"constructedMatchesWon":0,"constructedMatchesLost":0,"constructedMatchesDrawn":0,"limitedSeasonOrdinal":0,"limitedClass":"Bronze","limitedLevel":0,"limitedStep":0,"limitedMatchesWon":0,"limitedMatchesLost":0,"limitedMatchesDrawn":0}"""
    fun telemetryAck() = "Success"
}
