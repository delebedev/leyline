package leyline.detekt

import dev.detekt.api.Finding
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.string.shouldContain

fun List<Finding>.shouldHaveSingleFinding(
    messageContains: String,
): Finding {
    this shouldHaveSize 1
    val finding = single()
    finding.message shouldContain messageContains
    return finding
}
