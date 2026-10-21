package leyline.config

/** Host-owned coverage of effective battlefield keyword rows, separate from printed metadata. */
enum class KeywordProjection(
    val coverage: List<String>,
) {
    Native(emptyList()),
    WebEffectiveFlying(listOf("Flying")),
}
