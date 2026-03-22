package leyline.conformance

/**
 * Hand-written structural invariants observed in real server recordings.
 *
 * Patterns are validated against recording data by [RelationshipValidator].
 * Grow this catalog as bugs reveal new invariants.
 */
object RelationshipCatalog {

    val patterns: List<Relationship> =
        listOf(
            // --- CastSpell ---
            Relationship.AlwaysPresent("CastSpell", "ObjectIdChanged"),
            Relationship.AlwaysPresent("CastSpell", "ZoneTransfer"),
            Relationship.ValueIn("CastSpell", "annotations[ZoneTransfer].details.zone_dest", setOf("27")),
            Relationship.ValueIn("CastSpell", "annotations[ZoneTransfer].details.category", setOf("CastSpell")),
            Relationship.NonEmpty("CastSpell", "gameObjects"),

            // --- PlayLand ---
            Relationship.AlwaysPresent("PlayLand", "ObjectIdChanged"),
            Relationship.AlwaysPresent("PlayLand", "ZoneTransfer"),
            Relationship.ValueIn("PlayLand", "annotations[ZoneTransfer].details.zone_dest", setOf("28")),
            Relationship.ValueIn("PlayLand", "annotations[ZoneTransfer].details.category", setOf("PlayLand")),

            // --- Resolve ---
            Relationship.AlwaysPresent("Resolve", "ZoneTransfer"),
            Relationship.AlwaysPresent("Resolve", "ResolutionComplete"),

            // --- SearchReq ---
            Relationship.NonEmpty("SearchReq", "searchReq.itemsToSearch"),
            Relationship.NonEmpty("SearchReq", "searchReq.itemsSought"),
            Relationship.NonEmpty("SearchReq", "searchReq.zonesToSearch"),

            // --- Draw ---
            Relationship.AlwaysPresent("Draw", "ZoneTransfer"),
            Relationship.AlwaysPresent("Draw", "ObjectIdChanged"),
            Relationship.ValueIn("Draw", "annotations[ZoneTransfer].details.category", setOf("Draw")),
        )

    fun forCategory(category: String): List<Relationship> = patterns.filter { it.category == category }
}
