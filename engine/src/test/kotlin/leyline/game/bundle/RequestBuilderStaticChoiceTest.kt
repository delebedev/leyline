package leyline.game.bundle

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptRouteResolver
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.handoff.ResolvedPromptRoute
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.PromptCandidateKind
import leyline.bridge.types.PromptCandidateRefDto
import leyline.game.InMemoryCardRepository
import leyline.game.mapping.PromptIds
import leyline.game.state.GameBridge
import wotc.mtgo.gre.external.messaging.Messages.AllowCancel
import wotc.mtgo.gre.external.messaging.Messages.IdType
import wotc.mtgo.gre.external.messaging.Messages.OptionContext
import wotc.mtgo.gre.external.messaging.Messages.ParameterType
import wotc.mtgo.gre.external.messaging.Messages.SelectNReq
import wotc.mtgo.gre.external.messaging.Messages.SelectionContext
import wotc.mtgo.gre.external.messaging.Messages.SelectionListType
import wotc.mtgo.gre.external.messaging.Messages.StaticList

class RequestBuilderStaticChoiceTest :
    FunSpec({
        tags(UnitTag)

        fun pending(request: PromptRequest) =
            InteractivePromptBridge.PendingPrompt(
                promptId = "static-choice",
                request = request,
            )

        test("static choice routes pin outer prompt ids and choice domains") {
            val color = selectNRoute(PromptSemantic.StaticColorChoice)
            val subtype = selectNRoute(PromptSemantic.StaticSubtypeChoice)
            val parity = selectNRoute(PromptSemantic.StaticParityChoice)
            val selectNReq = SelectNReq.newBuilder().setSourceId(123).build()

            assertSoftly {
                color.staticChoiceOuterPromptId() shouldBe PromptIds.CHOOSE_COLOR
                color.staticChoice?.choiceDomain shouldBe 6
                color.envelope(selectNReq) { error("unused") }.prompt.promptId shouldBe PromptIds.CHOOSE_COLOR
                subtype.staticChoiceOuterPromptId() shouldBe PromptIds.CHOOSE_TYPE
                subtype.staticChoice?.choiceDomain shouldBe 5
                subtype.envelope(selectNReq) { error("unused") }.prompt.promptId shouldBe PromptIds.CHOOSE_TYPE
                parity.staticChoiceOuterPromptId() shouldBe PromptIds.CHOOSE_TYPE
                parity.staticChoice?.choiceDomain shouldBe StaticList.Parities.number
                parity.envelope(selectNReq) { error("unused") }.prompt.promptId shouldBe PromptIds.CHOOSE_TYPE
            }
        }

        test("color choices use the static Colors domain without ids or instance id type") {
            val bridge = GameBridge(cardRepository = InMemoryCardRepository())
            val sourceIid = bridge.getOrAllocInstanceId(ForgeCardId(100)).value
            val req =
                RequestBuilder.buildSelectNReq(
                    pending(
                        PromptRequest(
                            promptType = "choose_colors",
                            message = "Choose a color",
                            options = listOf("White", "Blue", "Black", "Red", "Green"),
                            route = PromptRouteResolver.resolve(PromptSemantic.StaticColorChoice),
                            sourceEntityId = 100,
                            staticList = StaticList.Colors,
                            staticOptionIds = listOf(1, 2, 3, 4, 5),
                        ),
                    ),
                    bridge,
                )

            assertSoftly {
                req.context shouldBe SelectionContext.Resolution_a163
                req.optionContext shouldBe OptionContext.Resolution_a9d7
                req.listType shouldBe SelectionListType.Static
                req.staticList shouldBe StaticList.Colors
                req.idType shouldBe IdType.None_ab2c
                req.idsList shouldBe emptyList()
                req.sourceId shouldBe sourceIid
                req.prompt.parametersList shouldBe emptyList()
            }
        }

        test("subtype choices use StaticSubset ids without instance id type") {
            val bridge = GameBridge(cardRepository = InMemoryCardRepository())
            val sourceIid = bridge.getOrAllocInstanceId(ForgeCardId(100)).value
            val staticIds = listOf(34, 39, 176)
            val req =
                RequestBuilder.buildSelectNReq(
                    pending(
                        PromptRequest(
                            promptType = "choose_type",
                            message = "Choose a creature type",
                            options = listOf("Goblin", "Human", "Kithkin"),
                            route = PromptRouteResolver.resolve(PromptSemantic.StaticSubtypeChoice),
                            sourceEntityId = 100,
                            staticList = StaticList.SubTypes,
                            staticOptionIds = staticIds,
                        ),
                    ),
                    bridge,
                )

            assertSoftly {
                req.context shouldBe SelectionContext.Resolution_a163
                req.optionContext shouldBe OptionContext.Resolution_a9d7
                req.listType shouldBe SelectionListType.StaticSubset
                req.staticList shouldBe StaticList.SubTypes
                req.idType shouldBe IdType.None_ab2c
                req.idsList shouldBe staticIds
                req.sourceId shouldBe sourceIid
                req.prompt.parametersList shouldBe emptyList()
            }
        }

        test("parity choices use the static Parities domain without ids or instance id type") {
            val bridge = GameBridge(cardRepository = InMemoryCardRepository())
            val sourceIid = bridge.getOrAllocInstanceId(ForgeCardId(100)).value
            val req =
                RequestBuilder.buildSelectNReq(
                    pending(
                        PromptRequest(
                            promptType = "confirm",
                            message = "Odd or even",
                            options = listOf("Odd", "Even"),
                            route = PromptRouteResolver.resolve(PromptSemantic.StaticParityChoice),
                            sourceEntityId = 100,
                            staticList = StaticList.Parities,
                            staticOptionIds = listOf(2, 1),
                        ),
                    ),
                    bridge,
                )

            assertSoftly {
                req.context shouldBe SelectionContext.Resolution_a163
                req.optionContext shouldBe OptionContext.Resolution_a9d7
                req.listType shouldBe SelectionListType.Static
                req.staticList shouldBe StaticList.Parities
                req.idType shouldBe IdType.None_ab2c
                req.idsList shouldBe emptyList()
                req.sourceId shouldBe sourceIid
                req.prompt.parametersList shouldBe emptyList()
            }
        }

        test("suspect choices use the Scapegoat prompt route") {
            val bridge = GameBridge(cardRepository = InMemoryCardRepository())
            val sourceIid = bridge.getOrAllocInstanceId(ForgeCardId(100)).value
            val targetIid = bridge.getOrAllocInstanceId(ForgeCardId(200)).value
            val req =
                RequestBuilder.buildSelectNReq(
                    pending(
                        PromptRequest(
                            promptType = "choose_cards",
                            message = "Choose a creature",
                            options = listOf("Target Creature"),
                            min = 1,
                            max = 1,
                            route = PromptRouteResolver.resolve(PromptSemantic.SuspectChoice),
                            candidateRefs =
                                listOf(
                                    PromptCandidateRefDto(
                                        index = 0,
                                        kind = PromptCandidateKind.Card,
                                        entityId = 200,
                                        zone = "Battlefield",
                                    ),
                                ),
                            sourceEntityId = 100,
                        ),
                    ),
                    bridge,
                )
            val envelope = selectNRoute(PromptSemantic.SuspectChoice).envelope(req) { error("unused") }

            assertSoftly {
                req.context shouldBe SelectionContext.Resolution_a163
                req.optionContext shouldBe OptionContext.Resolution_a9d7
                req.listType shouldBe SelectionListType.Dynamic
                req.idType shouldBe IdType.InstanceId_ab2c
                req.idsList shouldBe listOf(targetIid)
                req.sourceId shouldBe sourceIid
                req.prompt.parametersList
                    .single()
                    .type shouldBe ParameterType.PromptId
                req.prompt.parametersList
                    .single()
                    .promptId shouldBe PromptIds.SELECT_N_INNER_PARAMETER
                envelope.prompt.promptId shouldBe PromptIds.SUSPECT_ONE_OF_THOSE_CREATURES
                envelope.prompt.parametersList[0].numberValue shouldBe sourceIid
                envelope.prompt.parametersList[1].numberValue shouldBe 1
                envelope.allowCancel shouldBe AllowCancel.Continue
                envelope.gameStateAugmentation shouldBe SelectNEnvelope.GameStateAugmentation.None
            }
        }
    })

private fun selectNRoute(semantic: PromptSemantic) = (PromptRouteResolver.resolve(semantic) as ResolvedPromptRoute.SelectN).descriptor
