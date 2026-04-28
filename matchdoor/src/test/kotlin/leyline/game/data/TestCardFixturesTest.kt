package leyline.game.data

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.UnitTag

/**
 * Self-contained: no Arena SQLite required, no Forge classes loaded. Asserts
 * the per-card YAML fixtures parse into the right shape (slim ⇔ rules null,
 * full ⇔ rules non-null) and that the byName/byGrpId indices form a
 * coherent closure graph.
 *
 * Closure-walk + register coverage lives in `FixtureCardLoaderTest`.
 */
class TestCardFixturesTest :
    FunSpec({
        val tag = setOf(UnitTag)

        test("slim fixture: identity-only").config(tags = tag) {
            val f = TestCardFixtures.findFixture("Grizzly Bears").shouldNotBeNull()
            assertSoftly(f) {
                it.rules.shouldBeNull()
                it.identity.grpId shouldBe 79334
                it.identity.expansionCode shouldBe "J21"
                it.identity.abilities shouldHaveSize 0
                it.identity.tokens.size shouldBe 0
                it.identity.linkedFaces shouldHaveSize 0
                it.identity.isToken shouldBe false
                it.identity.isPrimaryCard shouldBe true
            }
        }

        test("slim fixture: DFC linked faces are bidirectional").config(tags = tag) {
            val front = TestCardFixtures.findFixture("Delver of Secrets").shouldNotBeNull()
            front.rules.shouldBeNull()
            front.identity.linkedFaces shouldHaveSize 1
            val backGrpId = front.identity.linkedFaces.first()
            val back = TestCardFixtures.findFixtureByGrpId(backGrpId).shouldNotBeNull()
            assertSoftly(back) {
                it.identity.name shouldBe "Insectile Aberration"
                it.identity.linkedFaces shouldContain front.identity.grpId
                it.identity.isPrimaryCard shouldBe false
            }
        }

        test("slim fixture: token producer references a full token").config(tags = tag) {
            val producer = TestCardFixtures.findFixture("Resolute Reinforcements").shouldNotBeNull()
            producer.rules.shouldBeNull()
            producer.identity.tokens.size shouldBe 1
            val (sourceAbilityId, tokenGrpId) =
                producer.identity.tokens.entries
                    .first()
            producer.identity.abilities.map { it.id } shouldContain sourceAbilityId
            val token = TestCardFixtures.findFixtureByGrpId(tokenGrpId).shouldNotBeNull()
            val rules = token.rules.shouldNotBeNull()
            assertSoftly {
                token.identity.name shouldBe "Soldier"
                rules.power shouldBe "1"
                rules.toughness shouldBe "1"
                token.identity.isToken shouldBe true
            }
        }

        test("saga: 3 chapter abilities, no chapterAbilityGrpIds field needed").config(tags = tag) {
            val f = TestCardFixtures.findFixture("History of Benalia").shouldNotBeNull()
            assertSoftly {
                f.rules.shouldBeNull()
                f.identity.abilities shouldHaveSize 3
                f.identity.tokens.values
                    .toSet()
                    .shouldHaveSize(1)
            }
        }

        test("modal card: parent ability has 4 children").config(tags = tag) {
            val f = TestCardFixtures.findFixture("Cryptic Command").shouldNotBeNull()
            val parent =
                f.identity.abilities
                    .firstOrNull { it.modalChildren.isNotEmpty() }
                    .shouldNotBeNull()
            parent.modalChildren shouldHaveSize 4
        }

        test("token alias: 'Soldier Token' resolves the same fixture as 'Soldier'").config(tags = tag) {
            val byBareName = TestCardFixtures.findFixture("Soldier").shouldNotBeNull()
            val byTokenSuffix = TestCardFixtures.findFixture("Soldier Token").shouldNotBeNull()
            byTokenSuffix.identity.grpId shouldBe byBareName.identity.grpId
        }

        test("missing card returns null, not error").config(tags = tag) {
            TestCardFixtures.findFixture("This Card Does Not Exist").shouldBeNull()
        }
    })
