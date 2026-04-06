package leyline.game

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import leyline.UnitTag

/** Unit tests for [TokenIdentityRegistry]. */
class TokenIdentityRegistryTest :
    FunSpec({

        tags(UnitTag)

        test("register and resolve") {
            val reg = TokenIdentityRegistry()
            reg.register(100, 89236)
            reg.resolve(100) shouldBe 89236
        }

        test("resolve returns null for unregistered") {
            val reg = TokenIdentityRegistry()
            reg.resolve(999).shouldBeNull()
        }

        test("first write wins") {
            val reg = TokenIdentityRegistry()
            reg.register(100, 89236)
            reg.register(100, 99999) // second write ignored
            reg.resolve(100) shouldBe 89236
        }

        test("retire removes entry") {
            val reg = TokenIdentityRegistry()
            reg.register(100, 89236)
            reg.retire(100)
            reg.resolve(100).shouldBeNull()
            reg.size() shouldBe 0
        }

        test("retire is idempotent") {
            val reg = TokenIdentityRegistry()
            reg.register(100, 89236)
            reg.retire(100)
            reg.retire(100) // no error
            reg.size() shouldBe 0
        }
    })
