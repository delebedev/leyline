package leyline

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.config.PuzzleDefinition
import leyline.config.WebSettings
import leyline.web.ChallengeCatalog
import leyline.web.ChallengeDefinition
import leyline.web.ChallengeSummary
import leyline.web.DEV_WEB_AUTH_SECRET
import leyline.web.GreStartRequest

class WebMainTest :
    FunSpec({

        tags(UnitTag)

        fun web(
            authSecret: String = "a-valid-32-char-secret-0123456789",
            loginCode: String = "",
            allowFixed: Boolean = false,
        ) = WebSettings(authSecret = authSecret, loginCode = loginCode, allowFixedLoginCode = allowFixed)

        test("auth secret is required for the web head") {
            shouldThrow<IllegalArgumentException> { validateWebHead(web(authSecret = "")) }
                .message shouldBe "web.auth_secret is required for the web head (set LEYLINE_WEB_AUTH_SECRET)"
        }

        test("auth secret must not reuse the dev default") {
            shouldThrow<IllegalArgumentException> { validateWebHead(web(authSecret = DEV_WEB_AUTH_SECRET)) }
                .message shouldBe "web.auth_secret must not use the dev default"
        }

        test("auth secret must be at least 32 characters") {
            shouldThrow<IllegalArgumentException> { validateWebHead(web(authSecret = "too-short")) }
                .message shouldBe "web.auth_secret must be at least 32 characters"
        }

        test("a strong secret validates cleanly") {
            shouldNotThrowAny { validateWebHead(web()) }
        }

        test("fixed login code requires explicit opt-in") {
            shouldThrow<IllegalArgumentException> { validateWebHead(web(loginCode = "123456")) }
                .message shouldBe "web.login_code requires web.allow_fixed_login_code=true"
        }

        test("fixed login code accepts six digits with explicit opt-in") {
            shouldNotThrowAny { validateWebHead(web(loginCode = "123456", allowFixed = true)) }
        }

        test("fixed login code rejects non-six-digit values") {
            shouldThrow<IllegalArgumentException> { validateWebHead(web(loginCode = "dev", allowFixed = true)) }
                .message shouldBe "web.login_code must be a six-digit code"
        }

        test("challenge resolution returns the catalog definition") {
            val definition = PuzzleDefinition("known", "[state]")
            val catalog = ChallengeCatalog(listOf(ChallengeDefinition(ChallengeSummary("known", "Known"), definition)))

            resolveChallenge(GreStartRequest(challengeId = "known"), catalog) shouldBe definition
        }

        test("challenge resolution rejects unknown and mixed launches as bad requests") {
            val catalog =
                ChallengeCatalog(
                    listOf(ChallengeDefinition(ChallengeSummary("known", "Known"), PuzzleDefinition("known", ""))),
                )

            shouldThrow<IllegalArgumentException> {
                resolveChallenge(GreStartRequest(challengeId = "missing"), catalog)
            }
            shouldThrow<IllegalArgumentException> {
                resolveChallenge(GreStartRequest(challengeId = "known", puzzle = "explicit"), catalog)
            }
        }
    })
