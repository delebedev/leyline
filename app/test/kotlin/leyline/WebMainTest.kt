package leyline

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class WebMainTest :
    FunSpec({
        test("fixed login code is disabled when env is absent or blank") {
            resolveFixedLoginCode(emptyMap()).shouldBeNull()
            resolveFixedLoginCode(mapOf("LEYLINE_WEB_LOGIN_CODE" to "")).shouldBeNull()
        }

        test("fixed login code requires explicit opt-in") {
            shouldThrow<IllegalArgumentException> {
                resolveFixedLoginCode(mapOf("LEYLINE_WEB_LOGIN_CODE" to "123456"))
            }.message shouldBe "LEYLINE_WEB_LOGIN_CODE requires LEYLINE_ALLOW_FIXED_LOGIN_CODE=true"
        }

        test("fixed login code accepts six digits with explicit opt-in") {
            resolveFixedLoginCode(
                mapOf(
                    "LEYLINE_WEB_LOGIN_CODE" to "123456",
                    "LEYLINE_ALLOW_FIXED_LOGIN_CODE" to "true",
                ),
            ) shouldBe "123456"
        }

        test("fixed login code rejects non-six-digit values") {
            shouldThrow<IllegalArgumentException> {
                resolveFixedLoginCode(
                    mapOf(
                        "LEYLINE_WEB_LOGIN_CODE" to "dev",
                        "LEYLINE_ALLOW_FIXED_LOGIN_CODE" to "true",
                    ),
                )
            }.message shouldBe "LEYLINE_WEB_LOGIN_CODE must be a six-digit code"
        }
    })
