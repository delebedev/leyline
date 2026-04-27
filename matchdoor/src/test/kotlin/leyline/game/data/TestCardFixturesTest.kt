package leyline.game.data

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.game.InMemoryCardRepository
import leyline.game.codes.SlotKind
import leyline.game.mapping.ZoneMapper

/**
 * Self-contained: no Arena SQLite required. Asserts the per-card YAML
 * fixtures parse, hydrate a [CardRepository], and round-trip the five sample
 * categories (vanilla, DFC, token producer, Saga, modal).
 */
class TestCardFixturesTest :
    FunSpec({
        val tag = setOf(UnitTag)

        test("repository loads all sample fixtures").config(tags = tag) {
            val repo = TestCardFixtures.repository()
            // 5 named cards + their closures (1 DFC back, 2 tokens) = 8 entries
            repo.findAllGrpIds() shouldHaveSize 8
            repo.findGrpIdByName("Grizzly Bears").shouldNotBeNull()
            repo.findGrpIdByName("Delver of Secrets").shouldNotBeNull()
            repo.findGrpIdByName("Resolute Reinforcements").shouldNotBeNull()
            repo.findGrpIdByName("History of Benalia").shouldNotBeNull()
            repo.findGrpIdByName("Cryptic Command").shouldNotBeNull()
        }

        test("vanilla creature round-trips fields").config(tags = tag) {
            val repo = TestCardFixtures.repository()
            val grpId = repo.findGrpIdByName("Grizzly Bears")!!
            val data = repo.findByGrpId(grpId)!!
            data.power shouldBe "2"
            data.toughness shouldBe "2"
            data.abilityIds.shouldHaveSize(0)
            data.linkedFaceGrpIds.shouldHaveSize(0)
            data.tokenGrpIds.size shouldBe 0
        }

        test("DFC linked faces are bidirectional").config(tags = tag) {
            val repo = TestCardFixtures.repository()
            val front = repo.findGrpIdByName("Delver of Secrets")!!
            val frontData = repo.findByGrpId(front)!!
            frontData.linkedFaceGrpIds shouldHaveSize 1
            val back = frontData.linkedFaceGrpIds.first()
            val backData = repo.findByGrpId(back)!!
            backData.linkedFaceGrpIds shouldContain front
            repo.findNameByGrpId(back) shouldBe "Insectile Aberration"
        }

        test("token producer resolves to its produced token").config(tags = tag) {
            val repo = TestCardFixtures.repository()
            val source = repo.findGrpIdByName("Resolute Reinforcements")!!
            val sourceData = repo.findByGrpId(source)!!
            sourceData.tokenGrpIds.size shouldBe 1
            val sourceAbilityIds = sourceData.abilityIds.map { it.first }.toSet()
            for (entry in sourceData.tokenGrpIds.entries) {
                sourceAbilityIds shouldContain entry.key
            }
            val tokenGrpId = sourceData.tokenGrpIds.values.first()
            val tokenData = repo.findByGrpId(tokenGrpId)!!
            repo.findNameByGrpId(tokenGrpId) shouldBe "Soldier"
            tokenData.power shouldBe "1"
            tokenData.toughness shouldBe "1"
        }

        test("saga has all chapter abilities and produces tokens").config(tags = tag) {
            val repo = TestCardFixtures.repository()
            val grpId = repo.findGrpIdByName("History of Benalia")!!
            val data = repo.findByGrpId(grpId)!!
            data.abilityIds.shouldHaveSize(3)
            // Both token-producing chapters point to the same Knight token.
            data.tokenGrpIds.values.toSet().shouldHaveSize(1)
            val knightGrpId = data.tokenGrpIds.values.first()
            repo.findNameByGrpId(knightGrpId) shouldBe "Knight"
        }

        test("saga chapters resolve through ZoneMapper positional fallback").config(tags = tag) {
            // YAML fixtures mirror the prod ExposedCardRepository shape: chapter
            // ability grpIds live at the leading positions of `abilityIds` and
            // `chapterAbilityGrpIds` is left empty. The resolver's positional
            // fallback path (`abilityIds[idx-1].first`) returns the right grpId.
            val repo = TestCardFixtures.repository()
            val grpId = repo.findGrpIdByName("History of Benalia")!!
            val data = repo.findByGrpId(grpId)!!
            data.chapterAbilityGrpIds shouldBe emptyList()
            for (chapter in 1..3) {
                val resolved = ZoneMapper.chapterGrpIdFromCardData(data, chapter)
                resolved shouldBe data.abilityIds[chapter - 1].first
            }
        }

        test("modal card registers parent + children").config(tags = tag) {
            val repo = TestCardFixtures.repository()
            val grpId = repo.findGrpIdByName("Cryptic Command")!!
            val modal = repo.lookupModalOptions(grpId)!!
            modal.childGrpIds shouldHaveSize 4
            val data = repo.findByGrpId(grpId)!!
            data.abilityIds.map { it.first } shouldContain modal.parentGrpId
        }

        test("ability slot kinds derive from category").config(tags = tag) {
            val repo = TestCardFixtures.repository()
            val grpId = repo.findGrpIdByName("Cryptic Command")!!
            val data = repo.findByGrpId(grpId)!!
            data.abilityKinds.shouldHaveSize(data.abilityIds.size)
            data.abilityKinds.forEach { it shouldBe SlotKind.Intrinsic }
        }

        test("register(name) loads only the named card and its closure").config(tags = tag) {
            val repo = InMemoryCardRepository()
            TestCardFixtures.register(repo, "Resolute Reinforcements")
            // Source + soldier token = 2.
            repo.findAllGrpIds() shouldHaveSize 2
            repo.findGrpIdByName("Resolute Reinforcements").shouldNotBeNull()
            repo.findGrpIdByName("Soldier").shouldNotBeNull()
            repo.findGrpIdByName("Grizzly Bears") shouldBe null
        }
    })
