package leyline.game.state

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.UnitTag

class GrantedAbilityIdsTest :
    FunSpec({
        tags(UnitTag)

        test("active grants retain identity when a sibling disappears") {
            val ids = GrantedAbilityIds()
            val first = GrantedAbilityIds.Key(1, 2, 3)
            val second = GrantedAbilityIds.Key(4, 5, 6)
            val firstId = ids.idFor(first)
            val secondId = ids.idFor(second)
            firstId shouldNotBe secondId
            ids.retain(setOf(second))
            ids.idFor(second) shouldBe secondId
            ids.idFor(first) shouldNotBe firstId
        }

        test("recipient scopes do not encode another recipient's grant history") {
            val first = GrantedAbilityIds()
            val second = GrantedAbilityIds()
            val key = GrantedAbilityIds.Key(1, 2, 3)
            first.idFor(key) shouldBe second.idFor(key)
            first.retain(emptySet())
            first.idFor(key) shouldNotBe second.idFor(key)
            GrantedAbilityIds().idFor(key) shouldBe GrantedAbilityIds.FIRST_GRANTED_ID
        }
    })
