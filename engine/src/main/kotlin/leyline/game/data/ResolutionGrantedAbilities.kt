package leyline.game.data

private val RESOLUTION_GRANT_APIS = setOf("Animate", "AnimateAll")

/**
 * Ability variables that an Animate or AnimateAll effect adds to the cards it affects when it resolves.
 * The catalog publishes one hidden row per variable, and runtime identity resolves grants against the same list.
 */
fun resolutionGrantedAbilityVariables(params: Map<String, String>): List<String> =
    if ((params["DB"] ?: params["AB"] ?: params["SP"]) in RESOLUTION_GRANT_APIS) {
        params["Abilities"]
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            .orEmpty()
    } else {
        emptyList()
    }
