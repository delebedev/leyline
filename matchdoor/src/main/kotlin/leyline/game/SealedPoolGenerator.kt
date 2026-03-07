package leyline.game

import forge.item.generation.UnOpenedProduct
import forge.model.FModel
import leyline.bridge.GameBootstrap
import org.slf4j.LoggerFactory

/**
 * Generates sealed card pools using Forge's booster templates.
 * Opens 6 packs for the given set, maps each card to its Arena grpId.
 *
 * Lives in matchdoor (not frontdoor) because it depends on Forge's card database
 * and booster template engine ([UnOpenedProduct]). Frontdoor consumes it via an
 * injected `(setCode) -> GeneratedPool` lambda — see [leyline.infra.LeylineServer]
 * wiring. This keeps the frontdoor module free of engine dependencies per the
 * architecture rule (frontdoor → domain model only, zero matchdoor imports).
 */
class SealedPoolGenerator(private val cards: CardRepository) {

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
        val boosterTemplate = FModel.getMagicDb().getBoosters().get(effectiveSet)
            ?: error("No booster template for set: $effectiveSet")

        val supplier = UnOpenedProduct(boosterTemplate)
        val grpIds = mutableListOf<Int>()
        var unmapped = 0

        repeat(6) {
            val pack = supplier.get()
            for (card in pack) {
                val grpId = cards.findGrpIdByName(card.name)
                if (grpId != null) {
                    grpIds.add(grpId)
                } else {
                    unmapped++
                    log.warn("Sealed pool: no grpId for '{}' ({})", card.name, effectiveSet)
                }
            }
        }

        log.info(
            "Sealed pool generated: set={} cards={} unmapped={}",
            effectiveSet,
            grpIds.size,
            unmapped,
        )

        // Use a fixed collation ID for FDN — real server uses per-set collation IDs
        val collationId = COLLATION_IDS.getOrDefault(effectiveSet, 100026)
        return GeneratedPool(grpIds = grpIds, collationId = collationId)
    }

    private fun resolveSet(setCode: String): String {
        val boosters = FModel.getMagicDb().getBoosters()
        if (boosters.get(setCode) != null) return setCode
        log.warn("No booster template for '{}', falling back to FDN", setCode)
        return "FDN"
    }

    companion object {
        // Known Arena collation IDs for sets (from proxy recordings)
        private val COLLATION_IDS = mapOf(
            "FDN" to 100026,
            "DSK" to 100050,
            "BLB" to 100048,
            "OTJ" to 100046,
            "MKM" to 100044,
            "LCI" to 100042,
            "WOE" to 100040,
            "MOM" to 100036,
            "ONE" to 100034,
            "BRO" to 100032,
            "DMU" to 100030,
            "SNC" to 100028,
            "TDM" to 100056,
            "FIN" to 100054,
            "DFT" to 100052,
        )
    }
}
