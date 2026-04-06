package leyline.game

import forge.item.generation.UnOpenedProduct
import forge.model.FModel
import leyline.bridge.GameBootstrap
import org.slf4j.LoggerFactory

/**
 * Generates 3 draft booster packs using Forge's booster templates.
 * Each pack is a list of Arena grpIds (typically 13-15 cards).
 *
 * Same module boundary pattern as [SealedPoolGenerator] — lives in matchdoor
 * because it depends on Forge; frontdoor consumes via injected lambda.
 */
class DraftPackGenerator(private val cards: CardRepository) {

    private val log = LoggerFactory.getLogger(DraftPackGenerator::class.java)

    fun generate(setCode: String): List<List<Int>> {
        GameBootstrap.initializeCardDatabase()

        val effectiveSet = resolveSet(setCode)
        val boosterTemplate = FModel.getMagicDb().getBoosters().get(effectiveSet)
            ?: error("No booster template for set: $effectiveSet")

        val packs = mutableListOf<List<Int>>()
        var totalUnmapped = 0

        repeat(PACKS) {
            val supplier = UnOpenedProduct(boosterTemplate)
            val pack = supplier.get()
            val grpIds = mutableListOf<Int>()
            for (card in pack) {
                val grpId = cards.findGrpIdByName(card.name)
                if (grpId != null) {
                    grpIds.add(grpId)
                } else {
                    totalUnmapped++
                    log.warn("Draft pack: no grpId for '{}' ({})", card.name, effectiveSet)
                }
            }
            packs.add(grpIds)
        }

        log.info(
            "Draft packs generated: set={} packs={} cards={} unmapped={}",
            effectiveSet,
            packs.size,
            packs.sumOf { it.size },
            totalUnmapped,
        )

        return packs
    }

    private fun resolveSet(setCode: String): String {
        val boosters = FModel.getMagicDb().getBoosters()
        if (boosters.get(setCode) != null) return setCode
        log.warn("No booster template for '{}', falling back to FDN", setCode)
        return "FDN"
    }

    companion object {
        const val PACKS = 3
    }
}
