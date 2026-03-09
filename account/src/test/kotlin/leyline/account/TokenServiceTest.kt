package leyline.account

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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

        test("issueTokens returns access and refresh tokens") {
            val pair = service.issueTokens(testAccount)
            pair.accessToken.count { it == '.' } shouldBe 2
            pair.refreshToken.count { it == '.' } shouldBe 2
            pair.expiresIn shouldBe TokenService.ACCESS_EXPIRY_SECONDS
        }

        test("access token has correct claims") {
            val pair = service.issueTokens(testAccount)
            val payload = decodePayload(pair.accessToken)
            payload shouldContain """"sub":"TEST-PERSONA-ID""""
            payload shouldContain """"iss":"TEST-ACCOUNT-ID""""
            payload shouldContain """"wotc-acct":"TEST-ACCOUNT-ID""""
            payload shouldContain """"wotc-name":"TestPlayer#12345""""
            payload shouldContain """"wotc-domn":"wizards""""
            payload shouldContain """"wotc-game":"arena""""
            payload shouldContain """"wotc-rols":["MDNALPHA"]"""
            payload shouldContain """"wotc-scps":["first-party"]"""
        }

        test("refresh token has sub claim") {
            val pair = service.issueTokens(testAccount)
            val payload = decodePayload(pair.refreshToken)
            payload shouldContain """"sub":"TEST-PERSONA-ID""""
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

        test("access token has wotc-ttyp=access") {
            val pair = service.issueTokens(testAccount)
            val payload = decodePayload(pair.accessToken)
            payload shouldContain """"wotc-ttyp":"access""""
        }

        test("refresh token has wotc-ttyp=refresh") {
            val pair = service.issueTokens(testAccount)
            val payload = decodePayload(pair.refreshToken)
            payload shouldContain """"wotc-ttyp":"refresh""""
        }

        test("validateAccessToken returns persona ID for access token") {
            val pair = service.issueTokens(testAccount)
            service.validateAccessToken(pair.accessToken) shouldBe "TEST-PERSONA-ID"
        }

        test("validateAccessToken rejects refresh token") {
            val pair = service.issueTokens(testAccount)
            service.validateAccessToken(pair.refreshToken).shouldBeNull()
        }

        test("validateRefreshToken rejects access token") {
            val pair = service.issueTokens(testAccount)
            service.validateRefreshToken(pair.accessToken).shouldBeNull()
        }

        test("custom roles appear in token") {
            val debugService = TokenService(roles = TokenService.DEBUG_ROLES)
            val pair = debugService.issueTokens(testAccount)
            val payload = decodePayload(pair.accessToken)
            payload shouldContain "MDNALPHA"
            payload shouldContain "MTGA_DEBUG"
        }
    })

private fun decodePayload(jwt: String): String {
    val parts = jwt.split(".")
    return Base64.getUrlDecoder().decode(parts[1]).toString(Charsets.UTF_8)
}
