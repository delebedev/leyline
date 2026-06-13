package leyline.game.bundle

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.types.ForgeCardId
import leyline.game.InMemoryCardRepository
import leyline.game.mapping.FrameIdResolver
import leyline.game.state.GameBridge
import java.util.concurrent.CompletableFuture

class PromptSourceResolverTest :
    FunSpec({
        tags(UnitTag)

        fun pending(request: PromptRequest) =
            InteractivePromptBridge.PendingPrompt(
                promptId = "source-test",
                request = request,
                future = CompletableFuture(),
            )

        test("card-source prompt resolves to source card iid") {
            val bridge = GameBridge(cardRepository = InMemoryCardRepository())
            val cardIid = bridge.getOrAllocInstanceId(ForgeCardId(100)).value

            val source =
                PromptSourceResolver.resolve(
                    pending(PromptRequest(promptType = "select", message = "Choose", options = emptyList(), sourceEntityId = 100)),
                    bridge,
                )

            source.sourceInstanceId shouldBe cardIid
            source.sourceCardInstanceId shouldBe cardIid
        }

        test("triggered ability prompt resolves source iid to ability surrogate") {
            val bridge = GameBridge(cardRepository = InMemoryCardRepository())
            val cardIid = bridge.getOrAllocInstanceId(ForgeCardId(100)).value
            val abilityIid = bridge.getOrAllocInstanceId(FrameIdResolver.triggerStackAbilityForgeId(77)).value

            val source =
                PromptSourceResolver.resolve(
                    pending(
                        PromptRequest(
                            promptType = "select",
                            message = "Choose",
                            options = emptyList(),
                            sourceEntityId = 100,
                            isTriggeredAbility = true,
                            forgeAbilityId = 77,
                        ),
                    ),
                    bridge,
                )

            source.sourceInstanceId shouldBe abilityIid
            source.sourceCardInstanceId shouldBe cardIid
        }
    })
