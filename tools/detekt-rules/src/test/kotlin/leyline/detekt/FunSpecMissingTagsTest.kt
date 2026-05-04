package leyline.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.test.lint
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize

class FunSpecMissingTagsTest : FunSpec({

    val rule = FunSpecMissingTags(Config.empty)

    test("flags FunSpec with no lane tag") {
        val code = """
            open class FunSpec(body: FunSpec.() -> Unit = {}) { fun test(name: String, body: () -> Unit) {} }
            class MyTest : FunSpec({
                test("something") { }
            })
        """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    test("passes when tags() is called at top of init lambda") {
        val code = """
            open class FunSpec(body: FunSpec.() -> Unit = {}) {
                fun test(name: String, body: () -> Unit) {}
                fun tags(vararg t: Any) {}
            }
            object UnitTag
            class MyTest : FunSpec({
                tags(UnitTag)
                test("something") { }
            })
        """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    test("passes when a semantic tag is added alongside one lane tag") {
        val code = """
            open class FunSpec(body: FunSpec.() -> Unit = {}) {
                fun test(name: String, body: () -> Unit) {}
                fun tags(vararg t: Any) {}
            }
            object UnitTag
            object WireTag
            class MyTest : FunSpec({
                tags(UnitTag, WireTag)
                test("something") { }
            })
        """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    test("flags when one Spec declares two lane tags") {
        val code = """
            open class FunSpec(body: FunSpec.() -> Unit = {}) {
                fun test(name: String, body: () -> Unit) {}
                fun tags(vararg t: Any) {}
            }
            object BoardTag
            object IntegrationTag
            class MyTest : FunSpec({
                tags(BoardTag, IntegrationTag)
                test("something") { }
            })
        """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    test("passes when tags() is called deep inside the lambda") {
        val code = """
            open class FunSpec(body: FunSpec.() -> Unit = {}) {
                fun test(name: String, body: () -> Unit) {}
                fun tags(vararg t: Any) {}
                fun beforeSpec(body: () -> Unit) {}
            }
            object UnitTag
            class MyTest : FunSpec({
                beforeSpec { tags(UnitTag) }
                test("something") { }
            })
        """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    test("flags StringSpec with no tags") {
        val code = """
            open class StringSpec(body: StringSpec.() -> Unit = {}) {
                operator fun String.invoke(body: () -> Unit) {}
            }
            class MyStringTest : StringSpec({
                "some scenario" { }
            })
        """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    test("passes on non-Spec class without tags") {
        val code = """
            open class NotASpec { }
            class MyService : NotASpec()
        """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    test("flags abstract base Spec missing tags") {
        val code = """
            open class FunSpec(body: FunSpec.() -> Unit = {}) { }
            abstract class ConformanceBase : FunSpec({ })
        """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    test("passes on abstract base that calls tags() from init block") {
        val code = """
            open class FunSpec {
                fun tags(vararg t: Any) {}
                fun afterEach(body: () -> Unit) {}
            }
            object IntegrationTag
            abstract class SessionTest(body: SessionTest.() -> Unit) : FunSpec() {
                init {
                    tags(IntegrationTag)
                    afterEach { }
                    body()
                }
            }
        """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    test("passes when per-test .config(tags = setOf(...)) is used") {
        val code = """
            open class FunSpec(body: FunSpec.() -> Unit = {}) {
                fun test(name: String): TestBuilder = TestBuilder()
            }
            class TestBuilder {
                fun config(tags: Set<Any>, body: () -> Unit) {}
            }
            object BoardTag
            class MyTest : FunSpec({
                test("scenario").config(tags = setOf(BoardTag)) { }
            })
        """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    test("flags when per-test config mixes lane tags") {
        val code = """
            open class FunSpec(body: FunSpec.() -> Unit = {}) {
                fun test(name: String): TestBuilder = TestBuilder()
            }
            class TestBuilder {
                fun config(tags: Set<Any>, body: () -> Unit) {}
            }
            object BoardTag
            object IntegrationTag
            class MyTest : FunSpec({
                test("board").config(tags = setOf(BoardTag)) { }
                test("session").config(tags = setOf(IntegrationTag)) { }
            })
        """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    test("flags when one file mixes direct Spec classes from different lanes") {
        val code = """
            open class FunSpec(body: FunSpec.() -> Unit = {}) {
                fun test(name: String, body: () -> Unit) {}
                fun tags(vararg t: Any) {}
            }
            object BoardTag
            object UnitTag
            class BoardShapeTest : FunSpec({
                tags(BoardTag)
                test("board") { }
            })
            class PureHelperTest : FunSpec({
                tags(UnitTag)
                test("unit") { }
            })
        """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    test("flags when .config is used without tags= argument") {
        val code = """
            open class FunSpec(body: FunSpec.() -> Unit = {}) {
                fun test(name: String): TestBuilder = TestBuilder()
            }
            class TestBuilder {
                fun config(enabled: Boolean, body: () -> Unit) {}
            }
            class MyTest : FunSpec({
                test("scenario").config(enabled = true) { }
            })
        """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    test("class extending a non-Kotest-Spec base is not flagged") {
        val code = """
            open class FunSpec { fun tags(vararg t: Any) {} }
            object IntegrationTag
            abstract class SessionTest(body: SessionTest.() -> Unit) : FunSpec() {
                init { tags(IntegrationTag); body() }
            }
            class FooSessionTest : SessionTest({ })
        """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    test("flags when a helper function named 'tags' has no lane tag") {
        val code = """
            open class FunSpec(body: FunSpec.() -> Unit = {}) { fun test(name: String, body: () -> Unit) {} }
            fun tags(x: Any) {}
            class MyTest : FunSpec({
                tags("arbitrary")
                test("something") { }
            })
        """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }
})
