package leyline.account

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.jdbc.Database

private fun Application.testModule(store: AccountStore, tokens: TokenService) {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    routing {
        accountRoutes(store, tokens, "localhost:30010")
    }
}

class AccountRoutesTest :
    FunSpec({

        tags(UnitTag)

        fun testApp(block: suspend ApplicationTestBuilder.() -> Unit) {
            val dbFile = java.io.File.createTempFile("routes-test", ".db").also { it.deleteOnExit() }
            val db = Database.connect("jdbc:sqlite:${dbFile.absolutePath}", "org.sqlite.JDBC")
            val store = AccountStore(db)
            store.createTables()
            val tokens = TokenService()

            // Seed a test account
            store.create("existing@test.com", "password123", "Existing")

            testApplication {
                application { testModule(store, tokens) }
                block()
            }
        }

        test("login with valid credentials returns 200 + tokens") {
            testApp {
                val resp = client.post("/auth/oauth/token") {
                    setBody("grant_type=password&username=existing%40test.com&password=password123")
                    contentType(ContentType.Application.FormUrlEncoded)
                }
                resp.status shouldBe HttpStatusCode.OK
                val body = resp.bodyAsText()
                body shouldContain "access_token"
                body shouldContain "refresh_token"
                body shouldContain "persona_id"
            }
        }

        test("login with wrong password returns 401") {
            testApp {
                val resp = client.post("/auth/oauth/token") {
                    setBody("grant_type=password&username=existing%40test.com&password=wrong")
                    contentType(ContentType.Application.FormUrlEncoded)
                }
                resp.status shouldBe HttpStatusCode.Unauthorized
                resp.bodyAsText() shouldContain "INVALID ACCOUNT CREDENTIALS"
            }
        }

        test("login with unknown email returns 401") {
            testApp {
                val resp = client.post("/auth/oauth/token") {
                    setBody("grant_type=password&username=nobody%40test.com&password=pass")
                    contentType(ContentType.Application.FormUrlEncoded)
                }
                resp.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("refresh token grant returns new tokens") {
            testApp {
                // First login to get a refresh token
                val loginResp = client.post("/auth/oauth/token") {
                    setBody("grant_type=password&username=existing%40test.com&password=password123")
                    contentType(ContentType.Application.FormUrlEncoded)
                }
                val refreshToken = """"refresh_token":"([^"]+)"""".toRegex()
                    .find(loginResp.bodyAsText())!!.groupValues[1]

                val resp = client.post("/auth/oauth/token") {
                    setBody("grant_type=refresh_token&refresh_token=$refreshToken")
                    contentType(ContentType.Application.FormUrlEncoded)
                }
                resp.status shouldBe HttpStatusCode.OK
                resp.bodyAsText() shouldContain "access_token"
            }
        }

        test("register creates account and returns tokens") {
            testApp {
                val resp = client.post("/accounts/register") {
                    setBody(
                        """{"displayName":"NewPlayer","email":"new@test.com","password":"secret",""" +
                            """"country":"US","dateOfBirth":"1990-01-01","acceptedTC":true}""",
                    )
                    contentType(ContentType.Application.Json)
                }
                resp.status shouldBe HttpStatusCode.OK
                val body = resp.bodyAsText()
                body shouldContain "accountID"
                body shouldContain "tokens"
                body shouldContain "access_token"
                body shouldContain "NewPlayer#"
            }
        }

        test("register with invalid email returns 422") {
            testApp {
                val resp = client.post("/accounts/register") {
                    setBody(
                        """{"displayName":"Test","email":"notanemail","password":"secret",""" +
                            """"country":"US","dateOfBirth":"1990-01-01","acceptedTC":true}""",
                    )
                    contentType(ContentType.Application.Json)
                }
                resp.status shouldBe HttpStatusCode.UnprocessableEntity
                resp.bodyAsText() shouldContain "INVALID EMAIL"
            }
        }

        test("register with short password returns 422") {
            testApp {
                val resp = client.post("/accounts/register") {
                    setBody(
                        """{"displayName":"Test","email":"short@test.com","password":"ab",""" +
                            """"country":"US","dateOfBirth":"1990-01-01","acceptedTC":true}""",
                    )
                    contentType(ContentType.Application.Json)
                }
                resp.status shouldBe HttpStatusCode.UnprocessableEntity
                resp.bodyAsText() shouldContain "PASSWORD TOO SHORT"
            }
        }

        test("register with blank display name returns 422") {
            testApp {
                val resp = client.post("/accounts/register") {
                    setBody(
                        """{"displayName":"","email":"blank@test.com","password":"secret",""" +
                            """"country":"US","dateOfBirth":"1990-01-01","acceptedTC":true}""",
                    )
                    contentType(ContentType.Application.Json)
                }
                resp.status shouldBe HttpStatusCode.UnprocessableEntity
                resp.bodyAsText() shouldContain "INVALID DISPLAY NAME"
            }
        }

        test("register with duplicate email returns 422") {
            testApp {
                val resp = client.post("/accounts/register") {
                    setBody(
                        """{"displayName":"Dup","email":"existing@test.com","password":"secret",""" +
                            """"country":"US","dateOfBirth":"1990-01-01","acceptedTC":true}""",
                    )
                    contentType(ContentType.Application.Json)
                }
                resp.status shouldBe HttpStatusCode.UnprocessableEntity
                resp.bodyAsText() shouldContain "INVALID EMAIL"
            }
        }

        test("profile with valid token returns account data") {
            testApp {
                // Login first
                val loginResp = client.post("/auth/oauth/token") {
                    setBody("grant_type=password&username=existing%40test.com&password=password123")
                    contentType(ContentType.Application.FormUrlEncoded)
                }
                val token = """"access_token":"([^"]+)"""".toRegex()
                    .find(loginResp.bodyAsText())!!.groupValues[1]

                val resp = client.get("/profile") {
                    header("Authorization", "Bearer $token")
                }
                resp.status shouldBe HttpStatusCode.OK
                val body = resp.bodyAsText()
                body shouldContain "accountID"
                body shouldContain "personaID"
                body shouldContain "existing@test.com"
                body shouldContain """"gameID":"arena""""
            }
        }

        test("profile without auth returns 401") {
            testApp {
                val resp = client.get("/profile")
                resp.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("doorbell returns FdURI") {
            testApp {
                val resp = client.post("/api/doorbell/api/v2/ring") {
                    setBody("{}")
                    contentType(ContentType.Application.Json)
                }
                resp.status shouldBe HttpStatusCode.OK
                resp.bodyAsText() shouldContain """"FdURI":"localhost:30010""""
            }
        }

        test("age gate stub returns false") {
            testApp {
                val resp = client.post("/accounts/requires-age-gate") {
                    setBody("""{"Country":"US","DateOfBirth":"1990-01-01"}""")
                    contentType(ContentType.Application.Json)
                }
                resp.status shouldBe HttpStatusCode.OK
                resp.bodyAsText() shouldContain "false"
            }
        }

        test("moderate stub returns 200") {
            testApp {
                val resp = client.post("/accounts/moderate") {
                    setBody("""{"value":"test","name":"Display Name"}""")
                    contentType(ContentType.Application.Json)
                }
                resp.status shouldBe HttpStatusCode.OK
            }
        }

        test("skus stub returns empty items") {
            testApp {
                val resp = client.get("/xsollaconnector/client/skus")
                resp.status shouldBe HttpStatusCode.OK
                resp.bodyAsText() shouldContain """"items":[]"""
            }
        }

        test("unknown path returns 200 with empty JSON") {
            testApp {
                val resp = client.get("/some/unknown/path")
                resp.status shouldBe HttpStatusCode.OK
                resp.bodyAsText() shouldBe "{}"
            }
        }
    })
