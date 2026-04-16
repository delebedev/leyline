package leyline.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.test.lint
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize

class TrivialKDocTest : FunSpec({

    val rule = TrivialKDoc(Config.empty)

    test("fails on @return-only KDoc") {
        val code = """
            /**
             * @return the value
             */
            fun lookup(id: Int): Int = id
        """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    test("fails on @param+@return only KDoc") {
        val code = """
            /**
             * @param id the id
             * @return the value
             */
            fun lookup(id: Int): Int = id
        """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    test("passes when prose line exists before tags") {
        val code = """
            /**
             * Looks up the value for the given id.
             *
             * @return the value
             */
            fun lookup(id: Int): Int = id
        """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    test("passes when only prose and no tags") {
        val code = """
            /**
             * Looks up the value for the given id.
             */
            fun lookup(id: Int): Int = id
        """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    test("passes when no KDoc at all") {
        val code = "fun lookup(id: Int): Int = id"
        rule.lint(code).shouldBeEmpty()
    }

    test("fails on empty KDoc") {
        val code = """
            /**
             */
            fun lookup(id: Int): Int = id
        """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    test("fails on @throws-only KDoc") {
        val code = """
            /**
             * @throws IllegalArgumentException if id is negative
             */
            fun lookup(id: Int): Int = id
        """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    test("flags trivial KDoc on class") {
        val code = """
            /**
             * @property name the name
             */
            class Foo(val name: String)
        """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    test("passes on property-heavy class KDoc with prose") {
        val code = """
            /**
             * Represents a card in a player's hand, with its current face-up state.
             *
             * @property name human-readable card name
             */
            class Foo(val name: String)
        """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }
})
