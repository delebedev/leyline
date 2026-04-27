package leyline.game.data

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import leyline.UnitTag
import leyline.game.InMemoryCardRepository

/**
 * Self-contained: no Arena SQLite required, no Forge classes loaded. Asserts
 * the per-card YAML fixtures parse into the right shape (Slim vs Full) and
 * that Full fixtures hydrate a [CardRepository] correctly.
 *
 * Slim fixtures are exercised end-to-end by tests in the conformance package
 * which thread Forge through. Here we only verify identity round-trip.
 */
class TestCardFixturesTest :
    FunSpec({
        val tag = setOf(UnitTag)

        test("slim fixture: identity-only").config(tags = tag) {
            val f = TestCardFixtures.findFixture("Grizzly Bears").shouldNotBeNull()
            f.shouldBeInstanceOf<TestCardFixtures.Fixture.Slim>()
            f.identity.grpId shouldBe 79334
            f.identity.expansionCode shouldBe "J21"
            f.identity.abilities shouldHaveSize 0
            f.identity.tokens.size shouldBe 0
            f.identity.linkedFaces shouldHaveSize 0
            f.identity.isToken shouldBe false
            f.identity.isPrimaryCard shouldBe true
        }

        test("slim fixture: DFC linked faces are bidirectional").config(tags = tag) {
            val front = TestCardFixtures.findFixture("Delver of Secrets").shouldNotBeNull()
            front.shouldBeInstanceOf<TestCardFixtures.Fixture.Slim>()
            front.identity.linkedFaces shouldHaveSize 1
            val backGrpId = front.identity.linkedFaces.first()
            val back = TestCardFixtures.findFixtureByGrpId(backGrpId).shouldNotBeNull()
            back.identity.name shouldBe "Insectile Aberration"
            back.identity.linkedFaces shouldContain front.identity.grpId
            back.identity.isPrimaryCard shouldBe false
        }

        test("slim fixture: token producer references a Full token").config(tags = tag) {
            val producer = TestCardFixtures.findFixture("Resolute Reinforcements").shouldNotBeNull()
            producer.shouldBeInstanceOf<TestCardFixtures.Fixture.Slim>()
            producer.identity.tokens.size shouldBe 1
            val (sourceAbilityId, tokenGrpId) = producer.identity.tokens.entries.first()
            producer.identity.abilities.map { it.id } shouldContain sourceAbilityId
            val token = TestCardFixtures.findFixtureByGrpId(tokenGrpId).shouldNotBeNull()
            token.shouldBeInstanceOf<TestCardFixtures.Fixture.Full>()
            token.identity.name shouldBe "Soldier"
            token.rules.power shouldBe "1"
            token.rules.toughness shouldBe "1"
            token.identity.isToken shouldBe true
        }

        test("slim fixture: saga has chapter abilities, no chapterAbilityGrpIds").config(tags = tag) {
            val f = TestCardFixtures.findFixture("History of Benalia").shouldNotBeNull()
            f.shouldBeInstanceOf<TestCardFixtures.Fixture.Slim>()
            f.identity.abilities shouldHaveSize 3
            f.identity.tokens.values.toSet().shouldHaveSize(1)
        }

        test("slim fixture: modal card has parent + children").config(tags = tag) {
            val f = TestCardFixtures.findFixture("Cryptic Command").shouldNotBeNull()
            val parent = f.identity.abilities.firstOrNull { it.modalChildren.isNotEmpty() }
                .shouldNotBeNull()
            parent.modalChildren shouldHaveSize 4
        }

        test("registerFull fails on slim closure").config(tags = tag) {
            val repo = InMemoryCardRepository()
            try {
                TestCardFixtures.registerFull(repo, "Grizzly Bears")
                error("expected registerFull to fail on slim fixture")
            } catch (e: IllegalStateException) {
                e.message.shouldNotBeNull()
            }
        }

        test("registerFull works on token + closure (full fixtures)").config(tags = tag) {
            // Soldier is full; registering it directly should work.
            val repo = InMemoryCardRepository()
            TestCardFixtures.registerFull(repo, "Soldier")
            val grpId = repo.findGrpIdByName("Soldier").shouldNotBeNull()
            val data = repo.findByGrpId(grpId).shouldNotBeNull()
            data.power shouldBe "1"
            data.toughness shouldBe "1"
        }

        test("registerAllFull skips slim, registers full").config(tags = tag) {
            val repo = InMemoryCardRepository()
            TestCardFixtures.registerAllFull(repo)
            // We have several Full fixtures (tokens). Slim ones should not be present.
            repo.findGrpIdByName("Grizzly Bears") shouldBe null
            repo.findGrpIdByName("Soldier").shouldNotBeNull()
        }
    })
