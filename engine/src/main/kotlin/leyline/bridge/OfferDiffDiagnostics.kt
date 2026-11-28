package leyline.bridge

/**
 * Per-match totals of [OfferDiff] results, counted once per published priority
 * window in which each difference appears.
 */
class OfferDiffDiagnostics {
    /** One difference shape: bucket, card, Forge API type, ability and the rule that names it. */
    data class Key(
        val bucket: OfferDiffBucket,
        val card: String,
        val zone: String,
        val apiType: String,
        val ability: String,
        val rule: OfferDiffRule?,
        /** Forge's text for the ability, so two abilities with the same kind and cost stay apart. */
        val description: String = "",
    )

    data class Finding(
        val key: Key,
        val windows: Int,
        /** Window and card state of the first window the difference appeared in. */
        val sample: String = "",
    )

    data class Snapshot(
        val windows: Int,
        val findings: List<Finding>,
        /** Windows whose comparison threw, by exception class. A failed comparison is an unnamed difference. */
        val failures: Map<String, Int>,
    ) {
        val unnamed: List<Finding> get() = findings.filter { it.key.rule == null }

        companion object {
            val Empty = Snapshot(0, emptyList(), emptyMap())
        }
    }

    private val lock = Any()
    private var windows = 0
    private val counts = LinkedHashMap<Key, Int>()
    private val samples = HashMap<Key, String>()
    private val failures = sortedMapOf<String, Int>()

    fun record(entries: List<OfferDiffEntry>) {
        synchronized(lock) {
            windows++
            entries
                .associateBy { Key(it.bucket, it.card, it.zone, it.apiType, it.ability, it.rule, it.description) }
                .forEach { (key, entry) ->
                    counts.merge(key, 1, Int::plus)
                    samples.putIfAbsent(key, entry.sample)
                }
        }
    }

    fun recordFailure(error: Throwable) {
        synchronized(lock) {
            windows++
            failures.merge(error::class.qualifiedName ?: "unknown", 1, Int::plus)
        }
    }

    fun snapshot(): Snapshot =
        synchronized(lock) {
            Snapshot(
                windows,
                counts.map { (key, count) -> Finding(key, count, samples[key].orEmpty()) }.sortedWith(FINDING_ORDER),
                failures.toSortedMap(),
            )
        }

    private companion object {
        val FINDING_ORDER =
            compareBy<Finding>({ it.key.bucket }, { it.key.rule?.name ?: "" }, { it.key.card }, { it.key.apiType }, { it.key.ability })
    }
}
