package leyline.bridge

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag

class NonInteractiveScopeTest :
    FunSpec({

        tags(UnitTag)

        test("no scope active by default") {
            NonInteractiveScope.active shouldBe null
        }

        test("quiet and bestEffort expose their policy and restore on exit") {
            NonInteractiveScope.quiet {
                NonInteractiveScope.active shouldBe NonInteractiveScope.Policy.QUIET
            }
            NonInteractiveScope.active shouldBe null
            NonInteractiveScope.bestEffort {
                NonInteractiveScope.active shouldBe NonInteractiveScope.Policy.BEST_EFFORT
            }
            NonInteractiveScope.active shouldBe null
        }

        test("nested scope restores the enclosing policy") {
            NonInteractiveScope.bestEffort {
                NonInteractiveScope.quiet {
                    NonInteractiveScope.active shouldBe NonInteractiveScope.Policy.QUIET
                }
                NonInteractiveScope.active shouldBe NonInteractiveScope.Policy.BEST_EFFORT
            }
            NonInteractiveScope.active shouldBe null
        }

        test("scope exits on exception") {
            shouldThrow<IllegalStateException> {
                NonInteractiveScope.quiet { error("boom") }
            }
            NonInteractiveScope.active shouldBe null
        }

        test("scope is thread-local") {
            NonInteractiveScope.quiet {
                var seen: NonInteractiveScope.Policy? = NonInteractiveScope.Policy.QUIET
                val other = Thread { seen = NonInteractiveScope.active }
                other.start()
                other.join()
                seen shouldBe null
            }
        }
    })
