package leyline.game.snapshot

import forge.game.card.Card
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.game.InMemoryCardRepository

class GrpIdResolverTest :
    FunSpec({
        tags(UnitTag)

        test("resolves Forge flavor-name variants by display name") {
            val repo = InMemoryCardRepository()
            repo.register(12345, "The Terminus of Return")

            val card = Card(1, null, null)
            card.name = "The Soul Stone"
            card.currentState.flavorName = "The Terminus of Return"

            GrpIdResolver.resolve(card, repo) shouldBe 12345
        }
    })
