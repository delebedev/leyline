package leyline.tooling.simclient

import leyline.copilot.PromptDecisionAdvisor
import leyline.copilot.PromptDecisionBoard
import leyline.tooling.headless.MatchFlowHarness
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage

/**
 * Simclient compatibility helpers for focused board-policy tests.
 * Prompt-family decisions live in [PromptDecisionAdvisor]; these functions
 * only adapt the harness' current object view into that shared seam.
 */
internal fun chooseBoardAwareSearchIds(
    msg: GREToClientMessage,
    harness: MatchFlowHarness,
): List<Int>? =
    PromptDecisionAdvisor.chooseBoardAwareSearchIds(
        msg,
        PromptDecisionBoard(harness.accumulator.objects.toMap()),
    )

internal fun chooseBoardAwareGroupAwayIds(
    msg: GREToClientMessage,
    harness: MatchFlowHarness,
): List<Int>? =
    PromptDecisionAdvisor.chooseBoardAwareGroupAwayIds(
        msg,
        PromptDecisionBoard(harness.accumulator.objects.toMap()),
    )
