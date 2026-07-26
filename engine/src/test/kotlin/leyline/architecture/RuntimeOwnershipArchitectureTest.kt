package leyline.architecture

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.game.state.GameBridge
import leyline.match.ConnectionState
import leyline.match.MatchSession
import java.lang.reflect.Modifier

class RuntimeOwnershipArchitectureTest :
    FunSpec({
        tags(UnitTag)

        test("GameBridge lifecycle resources live behind one sealed runtime mode") {
            GameBridge::class.nestedClasses.single { it.simpleName == "ActiveGame" }.isSealed shouldBe true

            val fields = GameBridge::class.java.declaredFields.map { it.name }
            fields.filter {
                it in
                    setOf(
                        "game",
                        "humanController",
                        "eventCollector",
                        "phaseStopProfile",
                        "loopController",
                    )
            } shouldBe emptyList()
            fields shouldContain "activeGame"
        }

        test("MatchSession handler implementations are reachable only through the owner") {
            MatchSession::class.java.declaredFields
                .filter { field ->
                    field.type.simpleName in
                        setOf(
                            "ActionPerformer",
                            "AutoPassEngine",
                            "CombatHandler",
                            "NumericInputHandler",
                            "OptionalActionHandler",
                            "TargetingHandler",
                        )
                }.onEach { field -> Modifier.isPrivate(field.modifiers) shouldBe true }
                .map { it.type.simpleName }
                .shouldContainExactlyInAnyOrder(
                    "ActionPerformer",
                    "AutoPassEngine",
                    "CombatHandler",
                    "NumericInputHandler",
                    "OptionalActionHandler",
                    "TargetingHandler",
                )

            ConnectionState::class.java.declaredFields
                .map { it.name } shouldNotContain "sessionLock"
        }
    })
