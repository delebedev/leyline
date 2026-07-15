package leyline.match

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.handoff.PromptRouteResolver
import leyline.bridge.handoff.PromptSemantic

class PromptResponseRouteTest :
    FunSpec({
        tags(UnitTag)

        val acceptedRoutes =
            mapOf(
                PromptResponseKind.Group to PromptRouteResolver.resolve(PromptSemantic.GroupingSurveil),
                PromptResponseKind.ModalChoice to PromptRouteResolver.resolve(PromptSemantic.ModalChoice),
                PromptResponseKind.SelectN to PromptRouteResolver.resolve(PromptSemantic.SelectNResolution),
                PromptResponseKind.PayCosts to PromptRouteResolver.resolve(PromptSemantic.SelectNCostSacrifice),
                PromptResponseKind.Search to PromptRouteResolver.resolve(PromptSemantic.Search),
                PromptResponseKind.Order to PromptRouteResolver.resolve(PromptSemantic.OrderForBottom),
                PromptResponseKind.Targeting to PromptRouteResolver.resolve(PromptSemantic.Generic, hasCandidateRefs = true),
            )

        test("each client response family accepts its bound route") {
            acceptedRoutes.forEach { (response, route) ->
                route.accepts(response) shouldBe true
            }
        }

        test("each client response family rejects every other bound route") {
            acceptedRoutes.forEach { (response, acceptedRoute) ->
                acceptedRoutes.values
                    .filterNot { it == acceptedRoute }
                    .forEach { route -> route.accepts(response) shouldBe false }
            }
        }

        test("auto-resolve route rejects every response family") {
            listOf(PromptRouteResolver.resolve(PromptSemantic.Generic, hasCandidateRefs = false)).forEach { route ->
                PromptResponseKind.entries.forEach { response -> route.accepts(response) shouldBe false }
            }
        }
    })
