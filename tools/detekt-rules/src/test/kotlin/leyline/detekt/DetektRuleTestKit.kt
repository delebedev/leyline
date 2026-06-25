package leyline.detekt

import io.gitlab.arturbosch.detekt.api.Finding
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

fun List<Finding>.shouldHaveSingleFinding(
    ruleId: String,
    messageContains: String,
): Finding {
    this shouldHaveSize 1
    val finding = single()
    finding.id shouldBe ruleId
    finding.message shouldContain messageContains
    return finding
}
