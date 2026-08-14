package leyline.match

import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PayCostsPromptRoute
import leyline.game.bundle.buildOneShot

/** Session presentation for the residual one-shot PayCosts routes. */
internal class PayCostsInteractionHandler(
    private val sink: GreMessageSink,
    private val counters: SessionCounters,
    private val bundles: BundleBuilderHolder,
    private val ctx: SessionContext,
) {
    fun sendPayCostsReq(
        pendingPrompt: InteractivePromptBridge.PendingPrompt,
        route: PayCostsPromptRoute,
    ) {
        check(route.manaSourcePayment == null) { "Iterative mana-source payments are coordinator-owned" }
        val (req, prompt) = route.buildOneShot(pendingPrompt, ctx.bridge)
        val result = bundles.bundleBuilder.payCostsBundle(ctx.game, counters.counter, req, prompt)
        Tap.outboundTemplate("PayCostsReq(${route.templateLabel}) seat=${counters.seatId}")
        sink.sendBundledGRE(result.messages)
    }
}
