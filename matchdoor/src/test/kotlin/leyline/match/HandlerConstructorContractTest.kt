package leyline.match

import forge.game.Game
import io.kotest.core.spec.style.FunSpec
import leyline.UnitTag
import leyline.bridge.types.ClientAutoPassState
import leyline.bridge.types.SeatId
import leyline.game.bundle.BundleBuilder
import leyline.game.state.GameBridge
import leyline.game.bundle.MessageCounter
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Pins the narrow-interface contract of each handler's constructor.
 *
 * The fakes below implement ONLY the sub-interfaces each handler needs.
 * If a handler's constructor is widened back to SessionOps (or to any
 * sub-interface the handler doesn't truly use), this file stops
 * compiling — that IS the regression signal.
 *
 * The test bodies are intentionally empty — construction is the assertion.
 */
@Suppress("EmptyAssertion") // Compile is the assertion; see class KDoc.
class HandlerConstructorContractTest :
    FunSpec({
        tags(UnitTag)

        // --- Narrow-only fakes --------------------------------------------------

        class SinkOnly : GreMessageSink {
            override fun sendBundledGRE(messages: List<GREToClientMessage>) {}
            override fun sendRealGameState(bridge: GameBridge, revealForSeat: Int?) {}
            override fun sendBundle(result: BundleBuilder.BundleResult) {}
            override fun sendGameOver(reason: ResultReason) {}
            override fun makeGRE(
                type: GREMessageType,
                gsId: Int,
                msgId: Int,
                configure: (GREToClientMessage.Builder) -> Unit,
            ): GREToClientMessage = GREToClientMessage.getDefaultInstance()
        }

        class CountersOnly : SessionCounters {
            override val seatId = SeatId(1)
            override var counter: MessageCounter = MessageCounter()
        }

        class TracerOnly : SessionTracer {
            override fun traceEvent(type: MatchEventType, game: Game, detail: String) {}
        }

        class BundlesOnly : BundleBuilderHolder

        class PacingOnly : Pacing {
            override fun paceDelay(multiplier: Int) {}
        }

        // --- Contract assertions (compile is the assertion) ---------------------

        test("OptionalActionHandler accepts GreMessageSink + SessionCounters only") {
            OptionalActionHandler(
                sink = SinkOnly(),
                counters = CountersOnly(),
            )
        }

        test("TargetingHandler accepts four narrow interfaces") {
            TargetingHandler(
                sink = SinkOnly(),
                counters = CountersOnly(),
                tracer = TracerOnly(),
                bundles = BundlesOnly(),
            )
        }

        test("CombatHandler accepts five narrow interfaces") {
            CombatHandler(
                sink = SinkOnly(),
                counters = CountersOnly(),
                tracer = TracerOnly(),
                bundles = BundlesOnly(),
                pacing = PacingOnly(),
            )
        }

        test("AutoPassEngine accepts five narrow interfaces + handler collaborators") {
            val combat = CombatHandler(SinkOnly(), CountersOnly(), TracerOnly(), BundlesOnly(), PacingOnly())
            val targeting = TargetingHandler(SinkOnly(), CountersOnly(), TracerOnly(), BundlesOnly())
            val optional = OptionalActionHandler(SinkOnly(), CountersOnly())
            AutoPassEngine(
                sink = SinkOnly(),
                counters = CountersOnly(),
                tracer = TracerOnly(),
                bundles = BundlesOnly(),
                pacing = PacingOnly(),
                combatHandler = combat,
                targetingHandler = targeting,
                optionalActionHandler = optional,
                autoPassState = ClientAutoPassState(),
            )
        }

        test("ActionPerformer accepts four narrow interfaces + handler collaborators") {
            val combat = CombatHandler(SinkOnly(), CountersOnly(), TracerOnly(), BundlesOnly(), PacingOnly())
            val targeting = TargetingHandler(SinkOnly(), CountersOnly(), TracerOnly(), BundlesOnly())
            val optional = OptionalActionHandler(SinkOnly(), CountersOnly())
            val engine = AutoPassEngine(
                sink = SinkOnly(),
                counters = CountersOnly(),
                tracer = TracerOnly(),
                bundles = BundlesOnly(),
                pacing = PacingOnly(),
                combatHandler = combat,
                targetingHandler = targeting,
                optionalActionHandler = optional,
                autoPassState = ClientAutoPassState(),
            )
            ActionPerformer(
                sink = SinkOnly(),
                counters = CountersOnly(),
                tracer = TracerOnly(),
                bundles = BundlesOnly(),
                targetingHandler = targeting,
                autoPassEngine = engine,
                autoPassState = ClientAutoPassState(),
            )
        }
    })
