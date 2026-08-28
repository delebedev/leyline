package leyline.game.generator

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import leyline.bridge.bootstrap.GameBootstrap
import leyline.testkit.SyntheticNameLookup

class SealedPoolGeneratorTest :
    FunSpec({

        tags(IntegrationTag)

        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
        }

        test("generates a 6-pack pool with a mapped grpId per card") {
            val pool = SealedPoolGenerator(SyntheticNameLookup()::findGrpIdByName).generate("FDN")

            pool.grpIds.shouldNotBeEmpty()
            pool.collationId shouldBe 100026
        }

        test("throws when a card name has no grpId mapping") {
            val generator = SealedPoolGenerator { null }
            shouldThrow<UnmappedCardNamesException> { generator.generate("FDN") }
        }

        test("supportedSets enumerates the known Arena collation sets with real edition names") {
            val sets = SealedPoolGenerator.supportedSets()

            val fdn = sets.first { it.code == "FDN" }
            fdn.name shouldBe "Foundations"
            sets.size shouldBe 17
        }
    })
