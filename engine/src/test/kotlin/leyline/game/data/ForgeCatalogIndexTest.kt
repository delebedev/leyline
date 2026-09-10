package leyline.game.data

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import java.nio.file.Files

class ForgeCatalogIndexTest :
    FunSpec({
        tags(UnitTag)

        val descriptor =
            ForgeCatalogDescriptor(
                mapOf("First Face" to 0),
                mapOf("face:First Face:1:Second Face" to 300_000_000),
                mapOf("Second Face" to listOf(FaceAlias("Second Face", "First Face", "face:First Face:1:Second Face", false))),
                "definition-version",
            )
        val archiveHash = "a".repeat(64)

        test("generated identities retain primary and secondary face lookup without loading definitions") {
            val path = Files.createTempFile("catalog-index-", ".json")
            ForgeCatalogIndex.write(path, archiveHash, descriptor)
            val catalog = ForgeCardRepository.open(path, archiveHash)
            assertSoftly {
                catalog.findAllGrpIds() shouldBe listOf(200_000_000)
                catalog.findNameByGrpId(200_000_000) shouldBe "First Face"
                catalog.findNameByGrpId(300_000_000) shouldBe "Second Face"
                catalog.catalogVersion shouldBe "definition-version"
            }
        }

        test("an index from another resource archive or identity scheme is rejected") {
            val path = Files.createTempFile("catalog-index-", ".json")
            ForgeCatalogIndex.write(path, archiveHash, descriptor)
            shouldThrow<IllegalArgumentException> { ForgeCardRepository.open(path, "b".repeat(64)) }
            Files.writeString(path, Files.readString(path).replace(ForgeCatalogIndex.IDENTITY_SCHEME, "unknown-scheme"))
            shouldThrow<IllegalArgumentException> { ForgeCardRepository.open(path, archiveHash) }
        }
    })
