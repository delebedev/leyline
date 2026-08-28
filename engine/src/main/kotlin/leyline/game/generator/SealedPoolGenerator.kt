package leyline.game.generator

import forge.item.generation.UnOpenedProduct
import forge.model.FModel
import leyline.bridge.bootstrap.GameBootstrap
import org.slf4j.LoggerFactory

/**
 * Generates sealed card pools using Forge's booster templates.
 * Opens 6 packs for the given set, maps each card to its Arena grpId
 * via a name-to-grpId lookup.
 *
 * Lives in engine because it depends on Forge's card database
 * and booster template engine ([UnOpenedProduct]). Frontdoor consumes it via an
 * injected `(setCode) -> GeneratedPool` lambda — see [leyline.infra.LeylineServer]
 * wiring. This keeps native frontdoor free of engine dependencies outside the
 * composition layer.
 */
class SealedPoolGenerator(
    private val findGrpIdByName: (String) -> Int?,
) {
    private val log = LoggerFactory.getLogger(SealedPoolGenerator::class.java)

    data class GeneratedPool(
        val grpIds: List<Int>,
        val collationId: Int,
    )

    /**
     * Generate a 6-booster sealed pool for [setCode].
     * Falls back to FDN if the set has no booster template.
     */
    fun generate(setCode: String): GeneratedPool {
        GameBootstrap.initializeCardDatabase()

        val effectiveSet = resolveSet(setCode)
        val boosterTemplate =
            FModel.getMagicDb().getBoosters().get(effectiveSet)
                ?: error("No booster template for set: $effectiveSet")

        val supplier = UnOpenedProduct(boosterTemplate)
        val grpIds = mutableListOf<Int>()
        val unmapped = mutableListOf<String>()

        repeat(6) {
            val pack = supplier.get()
            for (card in pack) {
                val grpId = findGrpIdByName(card.name)
                if (grpId != null) {
                    grpIds.add(grpId)
                } else {
                    unmapped.add(card.name)
                }
            }
        }

        if (unmapped.isNotEmpty()) {
            log.error("Sealed pool: no grpId for {} ({})", unmapped, effectiveSet)
            throw UnmappedCardNamesException(unmapped)
        }

        log.info(
            "Sealed pool generated: set={} cards={}",
            effectiveSet,
            grpIds.size,
        )

        // Use a fixed collation ID for FDN — protocol uses per-set collation IDs
        val collationId = COLLATION_IDS.getOrDefault(effectiveSet, 100026)
        return GeneratedPool(grpIds = grpIds, collationId = collationId)
    }

    private fun resolveSet(setCode: String): String {
        val boosters = FModel.getMagicDb().getBoosters()
        if (boosters.get(setCode) != null) return setCode
        log.warn("No booster template for '{}', falling back to FDN", setCode)
        return "FDN"
    }

    /** A set the sealed pool generator can build a pool for, with real Forge edition metadata. */
    data class SupportedSealedSet(
        val code: String,
        val name: String,
        val type: String,
        val cardCount: Int?,
    )

    companion object {
        // Known Arena collation IDs for sets
        private val COLLATION_IDS =
            mapOf(
                "FDN" to 100026,
                "DSK" to 100050,
                "BLB" to 100048,
                "OTJ" to 100046,
                "MKM" to 100044,
                "LCI" to 100042,
                "WOE" to 100040,
                "MOM" to 100037,
                "NEO" to 100027,
                "ONE" to 100034,
                "BRO" to 100032,
                "DMU" to 100030,
                "SNC" to 100028,
                "TDM" to 100056,
                "FIN" to 100054,
                "DFT" to 100052,
                "ECL" to 100058,
            )

        /**
         * Sets with a known Arena collation ID — the sealed pool generator's supported
         * set list. Name/type/cardCount come straight from Forge's already-loaded
         * edition metadata rather than a second hand-maintained list.
         */
        fun supportedSets(): List<SupportedSealedSet> {
            GameBootstrap.initializeCardDatabase()
            val editions = FModel.getMagicDb().getEditions()
            return COLLATION_IDS.keys.map { code ->
                val edition = editions.get(code)
                SupportedSealedSet(
                    code = code,
                    name = edition?.name ?: code,
                    type = edition?.type?.name?.lowercase() ?: "unknown",
                    cardCount = edition?.cards?.size,
                )
            }
        }
    }
}
