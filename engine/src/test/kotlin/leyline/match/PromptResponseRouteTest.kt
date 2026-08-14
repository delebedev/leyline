package leyline.match

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.handoff.PromptRouteResolver
import leyline.bridge.handoff.PromptSemantic

class PromptResponseRouteTest :
    FunSpec({
        tags(UnitTag)

        val representativeRoutes =
            mapOf(
                PromptSemantic.ModalChoice to PromptRouteResolver.resolve(PromptSemantic.ModalChoice),
                PromptSemantic.SelectNResolution to PromptRouteResolver.resolve(PromptSemantic.SelectNResolution),
                PromptSemantic.RevealChoose to PromptRouteResolver.resolve(PromptSemantic.RevealChoose),
                PromptSemantic.SelectNDiscard to PromptRouteResolver.resolve(PromptSemantic.SelectNDiscard),
                PromptSemantic.SelectNCostSacrifice to PromptRouteResolver.resolve(PromptSemantic.SelectNCostSacrifice),
                PromptSemantic.Search to PromptRouteResolver.resolve(PromptSemantic.Search),
                PromptSemantic.OrderForBottom to PromptRouteResolver.resolve(PromptSemantic.OrderForBottom),
                PromptSemantic.Generic to PromptRouteResolver.resolve(PromptSemantic.Generic, hasCandidateRefs = true),
                PromptSemantic.TargetSelection to PromptRouteResolver.resolve(PromptSemantic.TargetSelection),
            )
        val acceptedRoutes =
            mapOf(
                PromptResponseKind.ModalChoice to setOf(representativeRoutes.getValue(PromptSemantic.ModalChoice)),
                PromptResponseKind.SelectN to
                    setOf(
                        representativeRoutes.getValue(PromptSemantic.SelectNResolution),
                        representativeRoutes.getValue(PromptSemantic.RevealChoose),
                    ),
                PromptResponseKind.EffectCost to
                    setOf(
                        representativeRoutes.getValue(PromptSemantic.SelectNResolution),
                        representativeRoutes.getValue(PromptSemantic.Generic),
                    ),
                PromptResponseKind.Search to setOf(representativeRoutes.getValue(PromptSemantic.Search)),
                PromptResponseKind.Targeting to
                    setOf(
                        representativeRoutes.getValue(PromptSemantic.Generic),
                        representativeRoutes.getValue(PromptSemantic.TargetSelection),
                    ),
            )

        test("each client response family accepts its bound route") {
            acceptedRoutes.forEach { (response, routes) ->
                routes.forEach { route -> route.accepts(response) shouldBe true }
            }
        }

        test("each client response family rejects every other bound route") {
            acceptedRoutes.forEach { (response, accepted) ->
                representativeRoutes.values
                    .toSet()
                    .filterNot { it in accepted }
                    .forEach { route -> route.accepts(response) shouldBe false }
            }
        }

        test("auto-resolve route rejects every response family") {
            listOf(PromptRouteResolver.resolve(PromptSemantic.Generic, hasCandidateRefs = false)).forEach { route ->
                PromptResponseKind.entries.forEach { response -> route.accepts(response) shouldBe false }
            }
        }
    })
