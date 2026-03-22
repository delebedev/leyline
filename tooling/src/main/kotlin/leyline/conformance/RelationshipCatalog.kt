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
            Relationship.NonEmpty("CastSpell", "gameStateMessage.gameObjects"),

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

            // --- Sacrifice ---
            Relationship.AlwaysPresent("Sacrifice", "ObjectIdChanged"),
            Relationship.AlwaysPresent("Sacrifice", "ZoneTransfer"),
            Relationship.AlwaysPresent("Sacrifice", "AbilityInstanceDeleted"),
            Relationship.ValueIn("Sacrifice", "annotations[ZoneTransfer].details.category", setOf("Sacrifice")),

            // --- SBA_Damage (state-based lethal damage) ---
            Relationship.AlwaysPresent("SBA_Damage", "DamageDealt"),
            Relationship.AlwaysPresent("SBA_Damage", "ObjectIdChanged"),
            Relationship.AlwaysPresent("SBA_Damage", "ZoneTransfer"),
            Relationship.ValueIn("SBA_Damage", "annotations[ZoneTransfer].details.category", setOf("SBA_Damage")),

            // --- Destroy ---
            Relationship.AlwaysPresent("Destroy", "ObjectIdChanged"),
            Relationship.AlwaysPresent("Destroy", "ZoneTransfer"),
            Relationship.ValueIn("Destroy", "annotations[ZoneTransfer].details.category", setOf("Destroy")),

            // --- Discard ---
            Relationship.AlwaysPresent("Discard", "ObjectIdChanged"),
            Relationship.AlwaysPresent("Discard", "ZoneTransfer"),
            Relationship.ValueIn("Discard", "annotations[ZoneTransfer].details.category", setOf("Discard")),

            // --- DeclareAttackersReq ---
            Relationship.NonEmpty("DeclareAttackersReq", "declareAttackersReq.attackers"),
            Relationship.NonEmpty("DeclareAttackersReq", "declareAttackersReq.qualifiedAttackers"),
            Relationship.ValueIn("DeclareAttackersReq", "declareAttackersReq.canSubmitAttackers", setOf("true")),
            Relationship.ValueIn("DeclareAttackersReq", "prompt.promptId", setOf("6")),

            // --- SelectTargetsReq ---
            Relationship.NonEmpty("SelectTargetsReq", "selectTargetsReq.targets"),
            Relationship.ValueIn("SelectTargetsReq", "prompt.promptId", setOf("10")),

            // --- DeclareBlockersReq ---
            Relationship.NonEmpty("DeclareBlockersReq", "declareBlockersReq.blockers"),

            // --- MulliganReq ---
            Relationship.NonEmpty("MulliganReq", "mulliganReq.mulliganType"),

            // --- ConnectResp ---
            Relationship.NonEmpty("ConnectResp", "connectResp.protoVer"),
        )

    fun forCategory(category: String): List<Relationship> = patterns.filter { it.category == category }
}
