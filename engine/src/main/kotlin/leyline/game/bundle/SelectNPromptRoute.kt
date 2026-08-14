package leyline.game.bundle

import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.SelectNEnvelopeKind
import leyline.bridge.handoff.SelectNInnerPrompt
import leyline.bridge.handoff.SelectNPromptRoute
import leyline.bridge.handoff.StaticChoiceKind
import leyline.game.mapping.PromptIds
import leyline.game.state.GameBridge
import wotc.mtgo.gre.external.messaging.Messages.Prompt
import wotc.mtgo.gre.external.messaging.Messages.SelectNReq

internal fun SelectNPromptRoute.envelope(
    req: SelectNReq,
    learnPromptId: () -> Int,
): SelectNEnvelope =
    when (envelopeKind) {
        SelectNEnvelopeKind.Default -> SelectNEnvelope.default(req)
        SelectNEnvelopeKind.LegendRule -> SelectNEnvelope.legendRule(req)
        SelectNEnvelopeKind.RevealChoose -> SelectNEnvelope.revealChoose(req)
        SelectNEnvelopeKind.Resolution -> SelectNEnvelope.resolution(req)
        SelectNEnvelopeKind.ManifestDread -> SelectNEnvelope.manifestDread(req)
        SelectNEnvelopeKind.LibraryPutback -> SelectNEnvelope.libraryPutback(req)
        SelectNEnvelopeKind.LearnLesson -> SelectNEnvelope.learnLesson(req, learnPromptId())
        SelectNEnvelopeKind.StaticChoice -> SelectNEnvelope.staticChoice(req, staticChoiceOuterPromptId())
    }

internal fun SelectNPromptRoute.staticChoiceOuterPromptId(): Int =
    when (checkNotNull(staticChoice) { "StaticChoice route requires static-choice policy" }.kind) {
        StaticChoiceKind.Color -> PromptIds.CHOOSE_COLOR
        StaticChoiceKind.Subtype,
        StaticChoiceKind.Parity,
        -> PromptIds.CHOOSE_TYPE
    }

internal fun SelectNPromptRoute.configureInnerPrompt(
    builder: SelectNReq.Builder,
    prompt: InteractivePromptBridge.PendingPrompt,
    bridge: GameBridge,
) {
    when (innerPrompt) {
        SelectNInnerPrompt.StaticChoice -> {
            builder.setSourceIdIfPresent(prompt, bridge)
            builder.setPrompt(Prompt.newBuilder())
        }
        SelectNInnerPrompt.LegendRule -> {
            builder.setPrompt(Prompt.newBuilder())
            builder.setSourceId(PromptIds.SELECT_N_LEGEND_RULE_SOURCE)
        }
        SelectNInnerPrompt.GenericSelectN -> {
            builder.setSourceIdIfPresent(prompt, bridge)
            builder.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.SELECT_N))
        }
        SelectNInnerPrompt.SelectNInnerParameter -> {
            builder.setSourceIdIfPresent(prompt, bridge)
            builder.setSelectNInnerPrompt(PromptIds.SELECT_N_INNER_PARAMETER)
        }
        SelectNInnerPrompt.ManifestDreadInnerParameter -> {
            builder.setSourceIdIfPresent(prompt, bridge)
            builder.setSelectNInnerPrompt(PromptIds.MANIFEST_DREAD_INNER_PARAMETER)
        }
        SelectNInnerPrompt.LearnInnerParameter -> {
            builder.setSourceIdIfPresent(prompt, bridge)
            builder.setSelectNInnerPrompt(PromptIds.SELECT_N_LEARN_INNER_PARAMETER)
        }
    }
}
