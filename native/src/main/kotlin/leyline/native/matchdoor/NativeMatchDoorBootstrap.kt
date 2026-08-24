package leyline.native.matchdoor

import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelInitializer
import io.netty.channel.EventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.protobuf.ProtobufDecoder
import io.netty.handler.codec.protobuf.ProtobufEncoder
import io.netty.handler.ssl.SslContext
import leyline.config.EngineSettings
import leyline.config.RuntimeMatchConfigRegistry
import leyline.domain.service.MatchCoordinator
import leyline.game.data.CardRepository
import leyline.match.MatchConnection
import leyline.match.MatchDebugSink
import leyline.match.MatchRegistry
import leyline.native.protocol.ClientFrameDecoder
import leyline.native.protocol.ClientHeaderPrepender
import leyline.native.protocol.ClientHeaderStripper
import wotc.mtgo.gre.external.messaging.Messages.ClientToMatchServiceMessage
import java.io.File

object NativeMatchDoorBootstrap {
    @Suppress("LongParameterList")
    fun bind(
        bossGroup: EventLoopGroup,
        workerGroup: EventLoopGroup,
        ssl: SslContext,
        bindAddress: String,
        port: Int,
        engineSettings: EngineSettings,
        puzzlesDir: File,
        coordinator: MatchCoordinator,
        cardRepository: CardRepository,
        debugSink: MatchDebugSink,
        puzzlePath: () -> String?,
        runtimeMatchConfigs: RuntimeMatchConfigRegistry,
        aiDeckNameOverride: () -> String? = { null },
    ): Channel {
        val registry = MatchRegistry()
        return ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel::class.java)
            .childHandler(
                object : ChannelInitializer<SocketChannel>() {
                    override fun initChannel(ch: SocketChannel) {
                        ch.pipeline().addLast("ssl", ssl.newHandler(ch.alloc()))
                        ch.pipeline().addLast("frameDecoder", ClientFrameDecoder())
                        ch.pipeline().addLast("headerStripper", ClientHeaderStripper())
                        ch.pipeline().addLast("protobufDecoder", ProtobufDecoder(ClientToMatchServiceMessage.getDefaultInstance()))
                        ch.pipeline().addLast("headerPrepender", ClientHeaderPrepender())
                        ch.pipeline().addLast("protobufEncoder", ProtobufEncoder())
                        ch.pipeline().addLast(
                            "handler",
                            NativeMatchConnectionHandler(
                                { output ->
                                    MatchConnection(
                                        registry = registry,
                                        output = output,
                                        engineSettings = engineSettings,
                                        puzzlesDir = puzzlesDir,
                                        coordinator = coordinator,
                                        cardRepository = cardRepository,
                                        debugSink = debugSink,
                                        puzzlePath = puzzlePath,
                                        runtimeMatchConfigs = runtimeMatchConfigs,
                                        aiDeckNameOverride = aiDeckNameOverride,
                                    )
                                },
                            ),
                        )
                    }
                },
            ).bind(bindAddress, port)
            .sync()
            .channel()
    }
}
