package leyline.game

import forge.game.card.Card
import forge.model.FModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.conformance.ConformanceTestBase

/**
 * Tests for [PuzzleCardRegistrar] multi-face registration.
 *
 * Verifies that alternate faces (adventure, DFC back, split halves)
 * are registered alongside the primary face — both from client DB
 * and via synthetic fallback.
 */
class PuzzleCardRegistrarTest :
    FunSpec({

        tags(UnitTag)

        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }

        test("registers adventure secondary face alongside primary") {
            val repo = InMemoryCardRepository()
            val registrar = PuzzleCardRegistrar(repo)

            val card = loadCard("Ratcatcher Trainee")
            registrar.ensureCardRegistered(card)

            repo.findGrpIdByName("Ratcatcher Trainee").shouldNotBeNull()
            // Adventure secondary face should also be registered
            repo.findGrpIdByName("Pest Problem").shouldNotBeNull()
        }

        test("registers DFC backside face alongside primary") {
            val repo = InMemoryCardRepository()
            val registrar = PuzzleCardRegistrar(repo)

            val card = loadCard("Delver of Secrets")
            registrar.ensureCardRegistered(card)

            repo.findGrpIdByName("Delver of Secrets").shouldNotBeNull()
            repo.findGrpIdByName("Insectile Aberration").shouldNotBeNull()
        }

        test("registers split card halves") {
            val repo = InMemoryCardRepository()
            val registrar = PuzzleCardRegistrar(repo)

            val card = loadCard("Fire // Ice")
            registrar.ensureCardRegistered(card)

            // At least one of the halves should be registered
            val fire = repo.findGrpIdByName("Fire")
            val ice = repo.findGrpIdByName("Ice")
            // Split cards: primary name is "Fire" (LeftSplit), "Ice" is RightSplit
            fire.shouldNotBeNull()
            ice.shouldNotBeNull()
        }

        test("does not register FaceDown as alternate face") {
            val repo = InMemoryCardRepository()
            val registrar = PuzzleCardRegistrar(repo)

            // Any card with only Original state — FaceDown shouldn't be created
            val card = loadCard("Grizzly Bears")
            registrar.ensureCardRegistered(card)

            repo.findGrpIdByName("Grizzly Bears").shouldNotBeNull()
            // "Morph" or any face-down name should not exist
            repo.registeredCount shouldBe 1
        }

        test("idempotent — repeated registration returns same grpId") {
            val repo = InMemoryCardRepository()
            val registrar = PuzzleCardRegistrar(repo)

            val card = loadCard("Grizzly Bears")
            val first = registrar.ensureCardRegistered(card)
            val second = registrar.ensureCardRegistered(card)

            first shouldBe second
        }
    })

private fun loadCard(name: String): Card {
    val db = FModel.getMagicDb()?.commonCards
        ?: error("Forge card DB not loaded")
    val paperCard = db.getCard(name)
        ?: error("Card '$name' not found in Forge DB")
    return Card.fromPaperCard(paperCard, null)
}
