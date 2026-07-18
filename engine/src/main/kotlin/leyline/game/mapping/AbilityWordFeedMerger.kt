package leyline.game.mapping

import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo

/** Combines player-relational ability-word rows that share one protocol identity. */
internal object AbilityWordFeedMerger {
    private val aggregatedNames = setOf("Opus", "Void")

    fun merge(annotations: List<AnnotationInfo>): List<AnnotationInfo> {
        val result = mutableListOf<AnnotationInfo>()
        val indexes = linkedMapOf<Pair<String, Int>, Int>()
        for (annotation in annotations) {
            val name = annotation.abilityWordName()
            if (name == null || name !in aggregatedNames) {
                result.add(annotation)
                continue
            }
            val key = name to annotation.affectorId
            val index = indexes[key]
            if (index == null) {
                indexes[key] = result.size
                result.add(annotation)
            } else {
                val current = result[index]
                result[index] =
                    current
                        .toBuilder()
                        .clearAffectedIds()
                        .addAllAffectedIds((current.affectedIdsList + annotation.affectedIdsList).distinct())
                        .build()
            }
        }
        return result
    }

    private fun AnnotationInfo.abilityWordName(): String? =
        detailsList
            .firstOrNull { it.key == "AbilityWordName" }
            ?.valueStringList
            ?.firstOrNull()
}
