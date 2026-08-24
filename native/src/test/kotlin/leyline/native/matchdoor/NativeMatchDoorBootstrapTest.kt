package leyline.native.matchdoor

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.nio.NioIoHandler
import io.netty.handler.ssl.SslContextBuilder
import io.netty.pkitesting.CertificateBuilder
import leyline.config.EngineSettings
import leyline.config.RuntimeMatchConfigRegistry
import leyline.domain.service.MatchCoordinator
import leyline.game.data.CardData
import leyline.game.data.CardRepository
import leyline.match.MatchDebugSink
import leyline.native.NativeTag
import java.io.File

class NativeMatchDoorBootstrapTest :
    FunSpec({
        tags(NativeTag)

        test("native match bootstrap binds an active TCP channel") {
            val bossGroup = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())
            val workerGroup = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())
            val cert =
                CertificateBuilder()
                    .subject("CN=localhost")
                    .addSanDnsName("localhost")
                    .setIsCertificateAuthority(true)
                    .buildSelfSigned()
            val ssl = SslContextBuilder.forServer(cert.keyPair.private, cert.certificate).build()
            val debugSink =
                object : MatchDebugSink {
                    override var sessionProvider: (() -> Any?)? = null
                }

            val channel =
                NativeMatchDoorBootstrap.bind(
                    bossGroup = bossGroup,
                    workerGroup = workerGroup,
                    ssl = ssl,
                    bindAddress = "127.0.0.1",
                    port = 0,
                    engineSettings = EngineSettings(),
                    puzzlesDir = File("puzzles"),
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
