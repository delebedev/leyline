package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
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

        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }

        test("registers adventure secondary face alongside primary") {
            val repo = InMemoryCardRepository()
            FixtureCardLoader.ensureCardRegistered(repo, "Ratcatcher Trainee")

            repo.findGrpIdByName("Ratcatcher Trainee").shouldNotBeNull()
            repo.findGrpIdByName("Pest Problem").shouldNotBeNull()
        }

        test("registers DFC backside face alongside primary") {
            val repo = InMemoryCardRepository()
            FixtureCardLoader.ensureCardRegistered(repo, "Delver of Secrets")

            repo.findGrpIdByName("Delver of Secrets").shouldNotBeNull()
            repo.findGrpIdByName("Insectile Aberration").shouldNotBeNull()
        }

        // Split-card closure coverage is deferred — Arena's `A /// B` naming
        // disagrees with Forge's `A // B`, and the fixture inventory has no
        // Arena split card. TODO(card-fixtures): pick one, emit a fixture,
        // add the assertion here.

        test("plain card has no alternate faces registered") {
            val repo = InMemoryCardRepository()
            FixtureCardLoader.ensureCardRegistered(repo, "Grizzly Bears")

            repo.findGrpIdByName("Grizzly Bears").shouldNotBeNull()
            repo.registeredCount shouldBe 1
        }

        test("registers transform DFC backside — Concealing Curtains / Revealing Eye") {
            val repo = InMemoryCardRepository()
            FixtureCardLoader.ensureCardRegistered(repo, "Concealing Curtains")

            repo.findGrpIdByName("Concealing Curtains").shouldNotBeNull()
            repo.findGrpIdByName("Revealing Eye").shouldNotBeNull()
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
