package leyline.account

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jetbrains.exposed.v1.jdbc.Database

class AccountStoreTest :
    FunSpec({

        tags(UnitTag)

        fun freshStore(): AccountStore {
            val dbFile = java.io.File.createTempFile("account-test", ".db").also { it.deleteOnExit() }
            val db = Database.connect("jdbc:sqlite:${dbFile.absolutePath}", "org.sqlite.JDBC")
            val store = AccountStore(db)
            store.createTables()
            return store
        }

        test("create account and find by email") {
            val store = freshStore()
            val account = store.create("test@example.com", "secret123", "TestPlayer")
            account.email shouldBe "test@example.com"
            account.displayName shouldContain "TestPlayer#"
            account.accountId.length shouldBe 26
            account.personaId.length shouldBe 26

            val found = store.findByEmail("test@example.com")
            found.shouldNotBeNull()
            found.accountId shouldBe account.accountId
        }

        test("email lookup is case-insensitive") {
            val store = freshStore()
            store.create("Test@Example.COM", "pass", "Player")
            store.findByEmail("test@example.com").shouldNotBeNull()
            store.findByEmail("TEST@EXAMPLE.COM").shouldNotBeNull()
        }

        test("find by persona ID") {
            val store = freshStore()
            val account = store.create("a@b.com", "pass", "P")
            store.findByPersonaId(account.personaId).shouldNotBeNull()
            store.findByPersonaId("nonexistent").shouldBeNull()
        }

        test("find by account ID") {
            val store = freshStore()
            val account = store.create("a@b.com", "pass", "P")
            store.findByAccountId(account.accountId).shouldNotBeNull()
            store.findByAccountId("nonexistent").shouldBeNull()
        }

        test("authenticate with correct password") {
            val store = freshStore()
            store.create("user@test.com", "correct-password", "User")
            val result = store.authenticate("user@test.com", "correct-password")
            result.shouldNotBeNull()
            result.email shouldBe "user@test.com"
        }

        test("authenticate with wrong password returns null") {
            val store = freshStore()
            store.create("user@test.com", "correct-password", "User")
            store.authenticate("user@test.com", "wrong-password").shouldBeNull()
        }

        test("authenticate with unknown email returns null") {
            val store = freshStore()
            store.authenticate("nobody@test.com", "pass").shouldBeNull()
        }

        test("duplicate email throws") {
            val store = freshStore()
            store.create("dup@test.com", "pass", "One")
            val ex = runCatching { store.create("dup@test.com", "pass", "Two") }
            ex.isFailure.shouldBeTrue()
        }

        test("isEmpty on fresh DB") {
            val store = freshStore()
            store.isEmpty().shouldBeTrue()
            store.create("a@b.com", "pass", "P")
            store.isEmpty().shouldBeFalse()
        }

        test("seed inserts account with fixed IDs") {
            val store = freshStore()
            val seeded = store.seed(
                accountId = "fixed-account-id",
                personaId = "fixed-persona-id",
                email = "dev@local",
                displayName = "DevPlayer#00001",
                password = "dev",
            )
            seeded.shouldBeTrue()
            val account = store.findByEmail("dev@local")
            account.shouldNotBeNull()
            account.accountId shouldBe "fixed-account-id"
            account.personaId shouldBe "fixed-persona-id"
        }

        test("seed skips if email already exists") {
            val store = freshStore()
            store.seed("id1", "pid1", "dev@local", "Dev#1", "pass")
            val secondSeed = store.seed("id2", "pid2", "dev@local", "Dev#2", "pass")
            secondSeed.shouldBeFalse()
            store.findByEmail("dev@local")!!.accountId shouldBe "id1"
        }

        test("display name gets discriminator") {
            val store = freshStore()
            val account = store.create("a@b.com", "pass", "Player")
            account.displayName shouldContain "#"
            val parts = account.displayName.split("#")
            parts.size shouldBe 2
            parts[1].length shouldBe 5
        }
    })
