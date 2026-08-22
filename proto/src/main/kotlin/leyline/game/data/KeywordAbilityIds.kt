package leyline.game.data

/** Well-known ability identifiers carried by the client protocol. */
object KeywordAbilityIds {
    const val CONVOKE = 52
    const val HASTE = 9
    const val WARD_TWO = 141939
    const val PROWESS = 137
    const val IMPROVISE = 157
    const val TRAINING = 220
    const val ENLIST = 261
    const val STATION = 373
    const val FIREBENDING = 379
    const val TEAMWORK = 412
    const val WATERBEND = 8100003
    const val RECONFIGURE_UNATTACH = 244
    const val CONVOKE_PAYMENT = 172
    const val KICKER = 34
    const val FLASHBACK = 35
    const val MADNESS = 36
    const val RETRACE = 82
    const val EVOKE = 75
    const val OVERLOAD = 97
    const val EMERGE = 147
    const val JUMP_START = 170
    const val SPECTACLE = 174
    const val SURGE = 356
    const val MENTOR = 171
    const val ESCAPE = 199
    const val MUTATE = 203
    const val FORETELL = 208
    const val DECAYED = 214
    const val DISTURB = 215
    const val CLEAVE = 221
    const val BLITZ = 240
    const val DASH = 274
    const val DISGUISE = 307
    const val CLOAK = 349
    const val MANIFEST_DREAD = 351
    const val IMPENDING = 352
    const val HARMONIZE = 362
    const val PLOT = 328
    const val BACKUP = 287
    const val MOBILIZE = 363
    const val WARP = 371
    const val SNEAK = 394
    const val PARADIGM = 405
    const val RECONFIGURE = 237
    const val AIRBEND = 8100006
    const val PARADIGM_DELAYED_TRIGGER = 205572
    const val CASCADE = 86

    private val forgeAltCostKeywordIds =
        mapOf(
            "WARP" to WARP,
            "SNEAK" to SNEAK,
            "OVERLOAD" to OVERLOAD,
            "EVOKE" to EVOKE,
            "BLITZ" to BLITZ,
            "DASH" to DASH,
            "EMERGE" to EMERGE,
            "SPECTACLE" to SPECTACLE,
            "SURGE" to SURGE,
            "HARMONIZE" to HARMONIZE,
            "JUMPSTART" to JUMP_START,
            "JUMP_START" to JUMP_START,
            "JUMP-START" to JUMP_START,
            "FLASHBACK" to FLASHBACK,
            "MADNESS" to MADNESS,
            "PLOT" to PLOT,
            "PLOTTED" to PLOT,
            "FORETELL" to FORETELL,
            "FORETOLD" to FORETELL,
            "DISTURB" to DISTURB,
            "ESCAPE" to ESCAPE,
            "MUTATE" to MUTATE,
            "CLEAVE" to CLEAVE,
            "IMPENDING" to IMPENDING,
            "MOBILIZE" to MOBILIZE,
            "DISGUISE" to DISGUISE,
            "PARADIGM" to PARADIGM,
        )

    fun fromForgeAltCostName(name: String): Int? = forgeAltCostKeywordIds[name.uppercase()]
}
