package leyline.game.bundle

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.types.ForgeCardId
import leyline.game.InMemoryCardRepository
import leyline.game.state.GameBridge
import wotc.mtgo.gre.external.messaging.Messages.IdType
import wotc.mtgo.gre.external.messaging.Messages.OptionContext
import wotc.mtgo.gre.external.messaging.Messages.SelectionContext
import wotc.mtgo.gre.external.messaging.Messages.SelectionListType
import wotc.mtgo.gre.external.messaging.Messages.StaticList
import java.util.concurrent.CompletableFuture

class RequestBuilderStaticChoiceTest :
    FunSpec({
        tags(UnitTag)

        fun pending(request: PromptRequest) =
            InteractivePromptBridge.PendingPrompt(
                promptId = "static-choice",
                request = request,
                future = CompletableFuture(),
            )

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
                            semantic = PromptSemantic.StaticColorChoice,
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
                            semantic = PromptSemantic.StaticSubtypeChoice,
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
                            semantic = PromptSemantic.StaticParityChoice,
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
    })
