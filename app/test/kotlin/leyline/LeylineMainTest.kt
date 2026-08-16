package leyline

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldContainExactly

class LeylineMainTest :
    FunSpec({
        tags(UnitTag)

        test("does not consume a following option as a missing value") {
            parseArgs(arrayOf("--web-port", "--web-host", "localhost")) shouldContainExactly
                mapOf("--web-host" to "localhost")
        }
    })
