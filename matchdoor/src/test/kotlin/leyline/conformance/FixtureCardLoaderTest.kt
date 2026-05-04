package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.game.InMemoryCardRepository

/**
 * Closure-walk coverage for [FixtureCardLoader]: registering a card by
 * name brings its alternate faces (Adventure, DFC) and produced tokens
 * along automatically via fixture `linkedFaces` / `tokens` lists.
 */
class FixtureCardLoaderTest :
    FunSpec({

        tags(UnitTag)

        val base = BoardTestBase()
        beforeSpec { base.initCardDatabase() }

        test("registers adventure secondary face alongside primary") {
            val repo = InMemoryCardRepository()
            FixtureCardLoader.ensureCardRegistered(repo, "Ratcatcher Trainee")

            repo.findGrpIdByName("Ratcatcher Trainee") shouldBe 86845
            repo.findGrpIdByName("Pest Problem") shouldBe 86846
        }

        test("registers DFC backside face alongside primary") {
            val repo = InMemoryCardRepository()
            FixtureCardLoader.ensureCardRegistered(repo, "Delver of Secrets")

            repo.findGrpIdByName("Delver of Secrets") shouldBe 78378
            repo.findGrpIdByName("Insectile Aberration") shouldBe 78379
        }

        // Split-card closure coverage is deferred — the client's `A /// B` naming
        // disagrees with Forge's `A // B`, and the fixture inventory has no
        // client split card. TODO(card-fixtures): pick one, emit a fixture,
        // add the assertion here.

        test("plain card has no alternate faces registered") {
            val repo = InMemoryCardRepository()
            FixtureCardLoader.ensureCardRegistered(repo, "Grizzly Bears")

            repo.findGrpIdByName("Grizzly Bears") shouldBe 79334
            repo.registeredCount shouldBe 1
        }

        test("registers transform DFC backside — Concealing Curtains / Revealing Eye") {
            val repo = InMemoryCardRepository()
            FixtureCardLoader.ensureCardRegistered(repo, "Concealing Curtains")

            repo.findGrpIdByName("Concealing Curtains") shouldBe 78895
            repo.findGrpIdByName("Revealing Eye") shouldBe 78896
        }

        test("returns 0 for synthetic engine names absent from both Forge and fixtures") {
            val repo = InMemoryCardRepository()
            // Puzzle Goal is an internal Forge construct without a card-rules row.
            val grpId = FixtureCardLoader.ensureCardRegistered(repo, "Puzzle Goal")
            grpId shouldBe 0
        }

        test("idempotent — repeated registration returns same grpId") {
            val repo = InMemoryCardRepository()
            val first = FixtureCardLoader.ensureCardRegistered(repo, "Grizzly Bears")
            val second = FixtureCardLoader.ensureCardRegistered(repo, "Grizzly Bears")
            first shouldBe second
        }
    })
