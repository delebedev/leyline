package leyline.game.mapping

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.UnitTag
import leyline.game.InMemoryCardRepository
import leyline.game.state.GameBridge

class FrameIdResolverTest :
    FunSpec({
        tags(UnitTag)

        fun stubBridge(): GameBridge = GameBridge(cardRepository = InMemoryCardRepository())

        test("triggerStackAbilityIid mints distinct iids for distinct SA ids") {
            val bridge = stubBridge()
            val resolver = FrameIdResolver(bridge)
            val iidA = resolver.triggerStackAbilityIid(forgeAbilityId = 42)
            val iidB = resolver.triggerStackAbilityIid(forgeAbilityId = 43)
            iidA shouldNotBe iidB
        }

        test("triggerStackAbilityIid returns same iid for same SA id (idempotent allocation)") {
            val bridge = stubBridge()
            val resolver = FrameIdResolver(bridge)
            val first = resolver.triggerStackAbilityIid(forgeAbilityId = 42)
            val second = resolver.triggerStackAbilityIid(forgeAbilityId = 42)
            first shouldBe second
        }

        test("triggerStackAbilityIid throws on non-positive SA id") {
            val resolver = FrameIdResolver(stubBridge())
            shouldThrow<IllegalStateException> { resolver.triggerStackAbilityIid(forgeAbilityId = 0) }
            shouldThrow<IllegalStateException> { resolver.triggerStackAbilityIid(forgeAbilityId = -1) }
        }
    })
