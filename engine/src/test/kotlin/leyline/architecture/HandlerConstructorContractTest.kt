package leyline.architecture

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import leyline.UnitTag
import leyline.match.ActionPerformer
import leyline.match.AutoPassEngine
import leyline.match.CombatHandler
import leyline.match.DeferredCastCostInteractionHandler
import leyline.match.MatchSession
import leyline.match.MulliganHandler
import leyline.match.NumericInputHandler
import leyline.match.OptionalActionHandler
import leyline.match.PuzzleHandler
import leyline.match.SessionOps
import leyline.match.TargetingHandler
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.primaryConstructor

/**
 * Pins the handler-constructor contract documented on [SessionOps]: match-layer
 * handlers depend on the narrow role interfaces, never the whole-surface
 * [SessionOps] or the concrete [MatchSession].
 *
 * Walks each handler's primary-constructor parameter types via Kotlin
 * reflection — including the generic argument of lambda-provider parameters
 * (e.g. `() -> X?`), where a raw bytecode check would see only the erased
 * `Function0` type — and fails if [SessionOps] or [MatchSession] appears
 * anywhere in that shape.
 */
class HandlerConstructorContractTest :
    FunSpec({

        tags(UnitTag)

        val forbidden = setOf(SessionOps::class, MatchSession::class)

        fun KType.containsForbiddenType(): Boolean {
            if ((classifier as? KClass<*>) in forbidden) return true
            return arguments.any { it.type?.containsForbiddenType() == true }
        }

        val handlerClasses: List<KClass<*>> =
            listOf(
                TargetingHandler::class,
                CombatHandler::class,
                AutoPassEngine::class,
                OptionalActionHandler::class,
                NumericInputHandler::class,
                DeferredCastCostInteractionHandler::class,
                ActionPerformer::class,
                PuzzleHandler::class,
                MulliganHandler::class,
            )

        handlerClasses.forEach { handlerClass ->
            test("${handlerClass.simpleName} constructor does not depend on SessionOps or MatchSession") {
                val ctor =
                    handlerClass.primaryConstructor
                        ?: error("${handlerClass.simpleName} has no primary constructor")
                val offenders =
                    ctor.parameters.filter { it.type.containsForbiddenType() }.map { it.name }
                offenders.shouldBeEmpty()
            }
        }
    })
