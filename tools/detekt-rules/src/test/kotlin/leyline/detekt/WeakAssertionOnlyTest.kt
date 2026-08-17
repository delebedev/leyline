package leyline.detekt

import dev.detekt.api.Config
import dev.detekt.test.lint
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize

class WeakAssertionOnlyTest : FunSpec({
    val rule = WeakAssertionOnly(Config.empty)

    test("flags test with only shouldNotBeNull") {
        val code = """
            fun <T> T.shouldNotBeNull(): T = this!!
            fun test(name: String, body: () -> Unit) = body()
            val t = test("only shape") { "x".shouldNotBeNull() }
        """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    test("flags session with only shouldNotBeNull") {
        val code = """
            fun <T> T.shouldNotBeNull(): T = this!!
            fun session(name: String, body: () -> Unit) = body()
            val t = session("only shape") { "x".shouldNotBeNull() }
        """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    test("flags test with shouldNotBeEmpty only") {
        val code = """
            fun <T> List<T>.shouldNotBeEmpty() = this
            fun test(name: String, body: () -> Unit) = body()
            val t = test("only shape") { listOf(1).shouldNotBeEmpty() }
        """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    test("passes when shouldBe present alongside weak") {
        val code = """
            fun <T> T.shouldNotBeNull(): T = this!!
            infix fun <T> T.shouldBe(other: T) = Unit
            fun test(name: String, body: () -> Unit) = body()
            val t = test("real check") {
                val x = "a".shouldNotBeNull()
                x shouldBe "a"
            }
        """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    test("passes when shouldHaveSize present") {
        val code = """
            fun <T> List<T>.shouldHaveSize(n: Int) = Unit
            fun <T> List<T>.shouldNotBeEmpty() = this
            fun test(name: String, body: () -> Unit) = body()
            val t = test("sized check") {
                val l = listOf(1, 2)
                l.shouldNotBeEmpty()
                l.shouldHaveSize(2)
            }
        """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    test("passes on empty body (EmptyAssertion handles)") {
        val code = """
            fun test(name: String, body: () -> Unit) = body()
            val t = test("nothing here") {}
        """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    test("passes on shouldBeGreaterThan (strong, typed comparison)") {
        val code = """
            infix fun <T : Comparable<T>> T.shouldBeGreaterThan(other: T) = Unit
            fun test(name: String, body: () -> Unit) = body()
            val t = test("range check") { 5 shouldBeGreaterThan 3 }
        """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    test("flags shouldBeTrue-only test") {
        val code = """
            fun Boolean.shouldBeTrue() = Unit
            fun test(name: String, body: () -> Unit) = body()
            val t = test("truthy only") { (1 > 0).shouldBeTrue() }
        """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    test("passes on Kotest should matcher infix") {
        val code = """
            class Matcher<T>
            fun beInHandOf(player: String): Matcher<String> = Matcher()
            infix fun <T> T.should(matcher: Matcher<T>) = Unit
            fun Boolean.shouldBeTrue() = Unit
            fun assertSoftly(body: () -> Unit) = body()
            fun test(name: String, body: () -> Unit) = body()
            val t = test("domain matcher") {
                true.shouldBeTrue()
                assertSoftly {
                    "Grizzly Bears" should beInHandOf("human")
                }
            }
        """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    test("passes on Kotest shouldNot matcher infix") {
        val code = """
            class Matcher<T>
            fun beInGraveyardOf(player: String): Matcher<String> = Matcher()
            infix fun <T> T.shouldNot(matcher: Matcher<T>) = Unit
            fun Boolean.shouldBeTrue() = Unit
            fun assertSoftly(body: () -> Unit) = body()
            fun test(name: String, body: () -> Unit) = body()
            val t = test("negated domain matcher") {
                true.shouldBeTrue()
                assertSoftly {
                    "Shock" shouldNot beInGraveyardOf("human")
                }
            }
        """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    test("passes on matcher-only test") {
        val code = """
            class Matcher<T>
            fun beInHandOf(player: String): Matcher<String> = Matcher()
            fun beInGraveyardOf(player: String): Matcher<String> = Matcher()
            infix fun <T> T.should(matcher: Matcher<T>) = Unit
            infix fun <T> T.shouldNot(matcher: Matcher<T>) = Unit
            fun assertSoftly(body: () -> Unit) = body()
            fun test(name: String, body: () -> Unit) = body()
            val t = test("matcher-only") {
                assertSoftly {
                    "Grizzly Bears" should beInHandOf("human")
                    "Shock" shouldNot beInGraveyardOf("human")
                }
            }
        """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    test("does not treat bare should as strong") {
        val code = """
            infix fun String.should(rule: String) = Unit
            fun Boolean.shouldBeTrue() = Unit
            fun test(name: String, body: () -> Unit) = body()
            val t = test("bare should") {
                true.shouldBeTrue()
                "classes" should "depend on adapters"
            }
        """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    test("ignores ArchUnit fluent builder without check") {
        val code = """
            class RuleBuilder {
                fun should(): RuleBuilder = this
                fun dependOnClassesThat(): RuleBuilder = this
            }
            fun noClasses(): RuleBuilder = RuleBuilder()
            fun test(name: String, body: () -> Unit) = body()
            val t = test("archunit builder") {
                noClasses()
                    .should()
                    .dependOnClassesThat()
            }
        """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    test("flags bare check() alongside a weak matcher") {
        // Kotlin's `check(Boolean)` reports "Check failed." and prints neither
        // side, so it does not make a weak-matcher test strong.
        val code = """
            fun <T> T.shouldNotBeNull(): T = this
            fun test(name: String, body: () -> Unit) = body()
            val t = test("bare check") {
                val actual = listOf(1)
                actual.shouldNotBeNull()
                check(actual.size > 0)
            }
        """.trimIndent()
        rule.lint(code).shouldHaveSingleFinding(
            messageContains = "only weak matchers",
        )
    }

    test("leaves a body whose only call is a bare check to EmptyAssertion") {
        val code = """
            fun test(name: String, body: () -> Unit) = body()
            val t = test("bare check") {
                val actual = 1
                check(actual > 0)
            }
        """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    test("passes on ArchUnit fluent builder with check") {
        val code = """
            class RuleBuilder {
                fun should(): RuleBuilder = this
                fun dependOnClassesThat(): RuleBuilder = this
                fun check(classes: String) = Unit
            }
            fun noClasses(): RuleBuilder = RuleBuilder()
            fun test(name: String, body: () -> Unit) = body()
            val t = test("archunit check") {
                noClasses()
                    .should()
                    .dependOnClassesThat()
                    .check("classes")
            }
        """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }
})
