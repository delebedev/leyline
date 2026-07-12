package leyline.native.matchdoor

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.util.SelfSignedCertificate
import leyline.config.MatchConfig
import leyline.config.RuntimeMatchConfigRegistry
import leyline.domain.service.MatchCoordinator
import leyline.game.data.CardData
import leyline.game.data.CardRepository
import leyline.match.MatchDebugSink
import leyline.native.NativeTag

class NativeMatchDoorBootstrapTest :
    FunSpec({
        tags(NativeTag)

        test("native match bootstrap binds an active TCP channel") {
            val bossGroup = NioEventLoopGroup(1)
            val workerGroup = NioEventLoopGroup(1)
            val cert = SelfSignedCertificate()
            val ssl = SslContextBuilder.forServer(cert.certificate(), cert.privateKey()).build()
            val debugSink =
                object : MatchDebugSink {
                    override var sessionProvider: (() -> Any?)? = null
                }

            val channel =
                NativeMatchDoorBootstrap.bind(
                    bossGroup = bossGroup,
                    workerGroup = workerGroup,
                    ssl = ssl,
                    port = 0,
                    matchConfig = MatchConfig(),
                    coordinator = MatchCoordinator.NOOP,
                    cardRepository = EmptyCardRepository,
                    debugSink = debugSink,
                    puzzlePath = { null },
                    runtimeMatchConfigs = RuntimeMatchConfigRegistry(),
                )

            try {
                channel.isActive.shouldBeTrue()
            } finally {
                channel.close().sync()
                bossGroup.shutdownGracefully().sync()
                workerGroup.shutdownGracefully().sync()
            }
        }
    })

private object EmptyCardRepository : CardRepository {
    override fun findByGrpId(grpId: Int): CardData? = null

    override fun findNameByGrpId(grpId: Int): String? = null

    override fun findGrpIdByName(name: String): Int? = null

    override fun findAllGrpIds(): List<Int> = emptyList()
}
