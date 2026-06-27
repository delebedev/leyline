package leyline.domain.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/** Tests for [CollectionService.toJson] — content-derived cache version. */
class CollectionServiceTest :
    FunSpec({

        fun versionOf(json: String): String = Regex("\"cacheVersion\":(-?\\d+)").find(json)!!.groupValues[1]

        fun cardsOf(json: String): String = Regex("\"cards\":\\{(.*)}}").find(json)!!.groupValues[1]

        test("empty and full collections produce different cache versions") {
            val empty = CollectionService { emptyList() }.toJson(emptyMap())
            val full = CollectionService { listOf(1, 2, 3) }.toJson(mapOf(1 to 250, 2 to 250, 3 to 250))
            versionOf(empty) shouldNotBe versionOf(full)
        }

        test("identical collections produce a stable cache version regardless of map order") {
            val a = CollectionService { listOf(3, 1, 2) }.toJson(linkedMapOf(3 to 250, 1 to 250, 2 to 250))
            val b = CollectionService { listOf(1, 2, 3) }.toJson(linkedMapOf(1 to 250, 2 to 250, 3 to 250))
            a shouldBe b
        }

        test("a changed count yields a new cache version") {
            val before = CollectionService { listOf(1) }.toJson(mapOf(1 to 250))
            val after = CollectionService { listOf(1) }.toJson(mapOf(1 to 4))
            versionOf(before) shouldNotBe versionOf(after)
        }

        test("cache version is non-negative") {
            val json = CollectionService { listOf(1, 2) }.toJson(mapOf(1 to 250, 2 to 250))
            versionOf(json).toInt() shouldBe versionOf(json).toInt().coerceAtLeast(0)
        }

        test("cards payload is emitted sorted by grpId") {
            val json = CollectionService { listOf(10, 2) }.toJson(linkedMapOf(10 to 1, 2 to 1))
            cardsOf(json) shouldBe "\"2\":1,\"10\":1"
        }
    })
