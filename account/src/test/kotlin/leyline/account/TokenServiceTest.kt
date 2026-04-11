package leyline.account

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.util.Base64

class TokenServiceTest :
    FunSpec({

        tags(UnitTag)

        val service = TokenService()

        val testAccount = Account(
            accountId = "TEST-ACCOUNT-ID",
            personaId = "TEST-PERSONA-ID",
            email = "test@example.com",
            displayName = "TestPlayer#12345",
            country = "US",
            dob = "1990-01-01",
            createdAt = "2026-01-01T00:00:00Z",
        )

        test("issueTokens returns arena-shaped JWT access and refresh tokens") {
            val pair = service.issueTokens(testAccount)
            pair.accessToken shouldContain "."
            pair.refreshToken shouldContain "."
            pair.expiresIn shouldBe TokenService.ACCESS_EXPIRY_SECONDS
        }

        test("access token keeps only standard JWT claims") {
            val pair = service.issueTokens(testAccount)
            val payload = decodePayload(pair.accessToken)
            payload shouldContain "\"sub\":\"TEST-PERSONA-ID\""
            payload shouldContain "\"iss\":\"TEST-ACCOUNT-ID\""
            payload.shouldNotContain("MTGA_DEBUG")
            payload.shouldNotContain("WotC_ACCESS")
            payload.shouldNotContain("MTGA_FeatureToggle")
            payload.shouldNotContain("wotc-acct")
            payload.shouldNotContain("wotc-rols")
            payload.shouldNotContain("wotc-ttyp")
            payload.shouldNotContain("wotc-prms")
            payload.shouldNotContain("wotc-sgts")
            payload.shouldNotContain("wotc-socl")
            payload.shouldNotContain("wotc-cnst")
            payload.shouldNotContain("wotc-name")
            payload.shouldNotContain("wotc-domn")
            payload.shouldNotContain("wotc-game")
            payload.shouldNotContain("wotc-flgs")
            payload.shouldNotContain("wotc-scps")
            payload.shouldNotContain("wotc-pdgr")
        }

        test("validateRefreshToken returns persona ID for valid token") {
            val pair = service.issueTokens(testAccount)
            val personaId = service.validateRefreshToken(pair.refreshToken)
            personaId shouldBe "TEST-PERSONA-ID"
        }

        test("validateRefreshToken returns null for garbage") {
            service.validateRefreshToken("not.a.jwt").shouldBeNull()
            service.validateRefreshToken("").shouldBeNull()
            service.validateRefreshToken("abc").shouldBeNull()
        }

        test("validateAccessToken returns persona ID for access token") {
            val pair = service.issueTokens(testAccount)
            service.validateAccessToken(pair.accessToken) shouldBe "TEST-PERSONA-ID"
        }

        test("refresh validation matches access validation for issued tokens") {
            val pair = service.issueTokens(testAccount)
            service.validateRefreshToken(pair.accessToken) shouldBe "TEST-PERSONA-ID"
        }
    })

private fun decodePayload(jwt: String): String =
    Base64.getUrlDecoder().decode(jwt.split(".")[1]).toString(Charsets.UTF_8)
