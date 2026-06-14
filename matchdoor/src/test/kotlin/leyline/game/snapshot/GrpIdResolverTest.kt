package leyline.game.snapshot

import forge.card.GamePieceType
import forge.game.card.Card
import forge.game.card.CardCloneStates
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.game.InMemoryCardRepository
import leyline.game.state.GameBridge

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

        test("resolves standard token names through token-only lookup") {
            val repo = InMemoryCardRepository()
            repo.register(87484, "Map")

            val card = Card(1, null, null)
            card.name = "Map Token"
            card.setGamePieceType(GamePieceType.TOKEN)

            GrpIdResolver.resolve(card, repo) shouldBe 87484
        }

        test("resolves copy tokens whose copied permanent is also a token") {
            val repo = InMemoryCardRepository()
            repo.register(104161, "Rabbit")
            val source = Card(1, null, null)
            source.name = "Rabbit Token"
            source.setGamePieceType(GamePieceType.TOKEN)
            val copy = Card(2, null, null)
            copy.name = "Rabbit Token"
            copy.setGamePieceType(GamePieceType.TOKEN)
            copy.setCopiedPermanent(source)

            GrpIdResolver.resolve(copy, repo) shouldBe 104161
        }

        test("resolves cloned cards through their clone origin") {
            val repo = InMemoryCardRepository()
            repo.register(12345, "Doomsday Excruciator")
            val source = Card(1, null, null)
            source.name = "Doomsday Excruciator"
            val clone = Card(2, null, null)
            clone.name = "Unsupported Override Name"
            clone.setCloneOrigin(source)

            GrpIdResolver.resolve(clone, repo) shouldBe 12345
        }

        test("falls back for unmapped clone override names") {
            val repo = InMemoryCardRepository()
            val clone = Card(1, null, null)
            clone.name = "Unsupported Override Name"
            clone.cloneStates[1L] = CardCloneStates(clone, null)

            GrpIdResolver.resolve(clone, repo) shouldBe GameBridge.FALLBACK_GRPID
        }

        test("falls back for unmapped face-down original names") {
            val repo = InMemoryCardRepository()
            val card = Card(1, null, null)
            card.name = "Unsupported Face-Down Name"
            card.turnFaceDownNoUpdate()

            GrpIdResolver.resolve(card, repo) shouldBe GameBridge.FALLBACK_GRPID
        }
    })
