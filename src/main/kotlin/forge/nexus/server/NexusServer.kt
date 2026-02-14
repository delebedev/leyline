package forge.nexus.server

import forge.nexus.protocol.ArenaFrameDecoder
import forge.nexus.protocol.ArenaHeaderPrepender
import forge.nexus.protocol.ArenaHeaderStripper
import io.netty.bootstrap.Bootstrap
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBuf
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.protobuf.ProtobufDecoder
import io.netty.handler.codec.protobuf.ProtobufEncoder
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import io.netty.handler.ssl.util.SelfSignedCertificate
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.ClientToMatchServiceMessage
import java.io.File

/**
 * Arena-compatible TLS TCP server.
 *
 * Two modes:
 * - **Stub** (default): responds with fake auth/game state for smoke testing
 * - **Proxy**: relays to real Arena servers, decodes + logs frames, validates our codec
 *
 * Both doors use the same 6-byte header framing (see [ArenaFrameDecoder]).
 *
 * Architecture doc: forge-nexus/docs/bridge-architecture.md
 * Wire format doc:  forge-nexus/docs/wire-format.md
 */
class NexusServer(
    private val frontDoorPort: Int = 30010,
    private val matchDoorPort: Int = 30003,
    /** Front Door cert+key (PEM). Falls back to self-signed if null. */
    private val frontDoorCert: File? = null,
    private val frontDoorKey: File? = null,
    /** Match Door cert+key. Falls back to Front Door cert, then self-signed. */
    private val matchDoorCert: File? = null,
    private val matchDoorKey: File? = null,
    /** Proxy mode: if set, relay to these upstream IPs instead of stubbing. */
    private val upstreamFrontDoor: String? = null,
    private val upstreamMatchDoor: String? = null,
    /** Replay mode: if set, replay recorded payloads from this directory. */
    private val replayDir: File? = null,
) {
    private val log = LoggerFactory.getLogger(NexusServer::class.java)

    private val bossGroup = NioEventLoopGroup(1)
    private val workerGroup = NioEventLoopGroup()

    private var frontDoorChannel: Channel? = null
    private var matchDoorChannel: Channel? = null

    val isProxy get() = upstreamFrontDoor != null && replayDir == null

    /** Hybrid: proxy FD to real servers, stub MD with custom game state. */
    val isHybrid get() = upstreamFrontDoor != null && upstreamMatchDoor == null && replayDir == null

    /** Replay: proxy FD to real servers, replay recorded bytes on MD. */
    val isReplay get() = replayDir != null

    fun start() {
        val fdSsl = buildSslContext(frontDoorCert, frontDoorKey, "FrontDoor")
        val mdSsl = buildSslContext(
            matchDoorCert ?: frontDoorCert,
            matchDoorKey ?: frontDoorKey,
            "MatchDoor",
        )

        when {
            isReplay -> startReplay(fdSsl, mdSsl)
            isHybrid -> startHybrid(fdSsl, mdSsl)
            isProxy -> startProxy(fdSsl, mdSsl)
            else -> startStub(fdSsl, mdSsl)
        }
    }

    private fun buildSslContext(cert: File?, key: File?, name: String): SslContext = if (cert != null && key != null) {
        log.info("{}: loading TLS cert={} key={}", name, cert, key)
        SslContextBuilder.forServer(cert, key).build()
    } else {
        log.info("{}: using self-signed TLS certificate", name)
        val ssc = SelfSignedCertificate()
        SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build()
    }

    private fun startStub(fdSsl: SslContext, mdSsl: SslContext) {
        frontDoorChannel = bindServer(fdSsl, frontDoorPort, "FrontDoor") { ch ->
            ch.pipeline().addLast("frameDecoder", ArenaFrameDecoder())
            ch.pipeline().addLast("handler", FrontDoorStub())
        }
        log.info("Arena Front Door (stub) listening on :{}", frontDoorPort)

        matchDoorChannel = bindServer(mdSsl, matchDoorPort, "MatchDoor") { ch ->
            ch.pipeline().addLast("frameDecoder", ArenaFrameDecoder())
            ch.pipeline().addLast("headerStripper", ArenaHeaderStripper())
            ch.pipeline().addLast("protobufDecoder", ProtobufDecoder(ClientToMatchServiceMessage.getDefaultInstance()))
            ch.pipeline().addLast("headerPrepender", ArenaHeaderPrepender())
            ch.pipeline().addLast("protobufEncoder", ProtobufEncoder())
            ch.pipeline().addLast("handler", MatchHandler())
        }
        log.info("Arena Match Door (stub) listening on :{}", matchDoorPort)
    }

    private fun startReplay(fdSsl: SslContext, mdSsl: SslContext) {
        val dir = replayDir!!
        require(dir.isDirectory) { "Replay dir does not exist: $dir" }

        // Front Door: proxy to real servers (need real lobby to trigger match)
        val fdHost = upstreamFrontDoor
        if (fdHost != null) {
            frontDoorChannel = bindServer(fdSsl, frontDoorPort, "FrontDoor-Proxy") { ch ->
                ch.pipeline().addLast("proxy", ArenaProxyFrontHandler(workerGroup, fdHost, frontDoorPort))
            }
            log.info("Arena Front Door (proxy → {}:{}) listening on :{}", fdHost, frontDoorPort, frontDoorPort)
        } else {
            frontDoorChannel = bindServer(fdSsl, frontDoorPort, "FrontDoor") { ch ->
                ch.pipeline().addLast("frameDecoder", ArenaFrameDecoder())
                ch.pipeline().addLast("handler", FrontDoorStub())
            }
            log.info("Arena Front Door (stub) listening on :{}", frontDoorPort)
        }

        // Match Door: replay recorded payloads
        matchDoorChannel = bindServer(mdSsl, matchDoorPort, "MatchDoor-Replay") { ch ->
            ch.pipeline().addLast("frameDecoder", ArenaFrameDecoder())
            ch.pipeline().addLast("headerStripper", ArenaHeaderStripper())
            ch.pipeline().addLast("protobufDecoder", ProtobufDecoder(ClientToMatchServiceMessage.getDefaultInstance()))
            ch.pipeline().addLast("headerPrepender", ArenaHeaderPrepender())
            // No protobufEncoder — replay handler sends raw bytes that go through headerPrepender
            ch.pipeline().addLast("handler", ReplayHandler(dir))
        }
        log.info("Arena Match Door (replay from {}) listening on :{}", dir, matchDoorPort)
    }

    private fun startHybrid(fdSsl: SslContext, mdSsl: SslContext) {
        val fdHost = upstreamFrontDoor!!

        frontDoorChannel = bindServer(fdSsl, frontDoorPort, "FrontDoor-Proxy") { ch ->
            ch.pipeline().addLast("proxy", ArenaProxyFrontHandler(workerGroup, fdHost, frontDoorPort))
        }
        log.info("Arena Front Door (proxy → {}:{}) listening on :{}", fdHost, frontDoorPort, frontDoorPort)

        matchDoorChannel = bindServer(mdSsl, matchDoorPort, "MatchDoor") { ch ->
            ch.pipeline().addLast("frameDecoder", ArenaFrameDecoder())
            ch.pipeline().addLast("headerStripper", ArenaHeaderStripper())
            ch.pipeline().addLast("protobufDecoder", ProtobufDecoder(ClientToMatchServiceMessage.getDefaultInstance()))
            ch.pipeline().addLast("headerPrepender", ArenaHeaderPrepender())
            ch.pipeline().addLast("protobufEncoder", ProtobufEncoder())
            ch.pipeline().addLast("handler", MatchHandler())
        }
        log.info("Arena Match Door (stub) listening on :{}", matchDoorPort)
    }

    private fun startProxy(fdSsl: SslContext, mdSsl: SslContext) {
        val fdHost = upstreamFrontDoor!!
        val mdHost = upstreamMatchDoor ?: fdHost

        frontDoorChannel = bindServer(fdSsl, frontDoorPort, "FrontDoor-Proxy") { ch ->
            ch.pipeline().addLast("proxy", ArenaProxyFrontHandler(workerGroup, fdHost, frontDoorPort))
        }
        log.info("Arena Front Door (proxy → {}:{}) listening on :{}", fdHost, frontDoorPort, frontDoorPort)

        matchDoorChannel = bindServer(mdSsl, matchDoorPort, "MatchDoor-Proxy") { ch ->
            ch.pipeline().addLast("proxy", ArenaProxyFrontHandler(workerGroup, mdHost, matchDoorPort))
        }
        log.info("Arena Match Door (proxy → {}:{}) listening on :{}", mdHost, matchDoorPort, matchDoorPort)
    }

    fun stop() {
        log.info("Shutting down Arena server")
        frontDoorChannel?.close()?.sync()
        matchDoorChannel?.close()?.sync()
        workerGroup.shutdownGracefully()
        bossGroup.shutdownGracefully()
    }

    private fun bindServer(
        sslCtx: SslContext,
        port: Int,
        name: String,
        initChannel: (SocketChannel) -> Unit,
    ): Channel {
        val bootstrap = ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel::class.java)
            .childHandler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    ch.pipeline().addFirst("ssl", sslCtx.newHandler(ch.alloc()))
                    initChannel(ch)
                }
            })
            .option(ChannelOption.SO_BACKLOG, 128)
            .childOption(ChannelOption.SO_KEEPALIVE, true)

        return bootstrap.bind(port).sync().channel()
    }
}

// ---------------------------------------------------------------------------
// Proxy handlers: relay raw bytes while logging Arena frames.
// Simple auto-read on both sides — no manual flow control.
// ---------------------------------------------------------------------------

/**
 * Client-side proxy handler. On connect, opens TLS upstream and relays
 * bytes bidirectionally. Logs Arena frame headers for debugging.
 */
class ArenaProxyFrontHandler(
    private val workerGroup: EventLoopGroup,
    private val remoteHost: String,
    private val remotePort: Int,
) : ChannelInboundHandlerAdapter() {

    private val log = LoggerFactory.getLogger(ArenaProxyFrontHandler::class.java)

    @Volatile private var outboundChannel: Channel? = null
    private val pendingWrites = mutableListOf<Any>()

    override fun channelActive(ctx: ChannelHandlerContext) {
        val inbound = ctx.channel()
        log.info("Proxy: client from {}, connecting upstream {}:{}", inbound.remoteAddress(), remoteHost, remotePort)

        val upstreamSsl = SslContextBuilder.forClient()
            .trustManager(InsecureTrustManagerFactory.INSTANCE)
            .build()

        val b = Bootstrap()
            .group(workerGroup)
            .channel(NioSocketChannel::class.java)
            .handler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    ch.pipeline().addLast("ssl", upstreamSsl.newHandler(ch.alloc(), remoteHost, remotePort))
                    ch.pipeline().addLast("relay", RelayHandler(inbound, "S→C"))
                }
            })

        b.connect(remoteHost, remotePort).addListener(
            ChannelFutureListener { future ->
                if (future.isSuccess) {
                    outboundChannel = future.channel()
                    log.info("Proxy: upstream connected to {}:{}", remoteHost, remotePort)
                    // Flush any data that arrived before upstream was ready
                    synchronized(pendingWrites) {
                        for (msg in pendingWrites) {
                            future.channel().writeAndFlush(msg)
                        }
                        pendingWrites.clear()
                    }
                } else {
                    log.error("Proxy: upstream connect failed: {}", future.cause().message)
                    inbound.close()
                }
            },
        )
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (msg is ByteBuf) logFrame("C→S", msg)
        val out = outboundChannel
        if (out != null && out.isActive) {
            out.writeAndFlush(msg)
        } else {
            // Buffer until upstream is ready
            synchronized(pendingWrites) { pendingWrites.add(msg) }
        }
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        log.info("Proxy: client disconnected")
        outboundChannel?.close()
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        log.error("Proxy front: {}", cause.message)
        ctx.close()
    }

    private fun logFrame(dir: String, buf: ByteBuf) = logArenaFrame(log, dir, buf)
}

/** Relays bytes from one channel to another, logging frame headers + payloads. */
class RelayHandler(
    private val relayTarget: Channel,
    private val direction: String,
) : ChannelInboundHandlerAdapter() {

    private val log = LoggerFactory.getLogger(RelayHandler::class.java)

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (msg is ByteBuf) logArenaFrame(log, direction, msg)
        if (relayTarget.isActive) {
            relayTarget.writeAndFlush(msg)
        } else {
            (msg as? ByteBuf)?.release()
        }
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        log.info("Proxy: {} channel closed", direction)
        if (relayTarget.isActive) relayTarget.close()
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        log.error("Proxy {}: {}", direction, cause.message)
        ctx.close()
    }
}

/** Log Arena frame header fields for proxy debugging. */
private fun logArenaFrame(log: Logger, dir: String, buf: ByteBuf) {
    if (buf.readableBytes() < 6) return
    val idx = buf.readerIndex()
    val ft = buf.getByte(idx + 1)
    val pl = buf.getIntLE(idx + 2)
    val tn = frameTypeName(ft)
    log.info("  {} type={} payload={} total={}", dir, tn, pl, buf.readableBytes())
    dumpPayload(dir, buf)
}

/** Dump payload bytes: extract printable text (JSON) from data frames. */
private fun dumpPayload(dir: String, buf: ByteBuf) {
    val log = LoggerFactory.getLogger("ArenaPayloadDump")
    if (buf.readableBytes() <= 6) return
    val idx = buf.readerIndex()
    val ft = buf.getByte(idx + 1)
    // Only dump data frames, not control frames
    if (ft == ArenaFrameDecoder.Companion.TYPE_CTRL_INIT || ft == ArenaFrameDecoder.Companion.TYPE_CTRL_ACK) return
    val payloadStart = idx + 6
    val payloadLen = buf.readableBytes() - 6
    if (payloadLen <= 0) return
    val bytes = ByteArray(payloadLen)
    buf.getBytes(payloadStart, bytes)
    // Extract JSON substrings from the protobuf payload
    val text = bytes.toString(Charsets.UTF_8)
    val jsons = mutableListOf<String>()
    var i = 0
    while (i < text.length) {
        if (text[i] == '{') {
            var depth = 0
            for (j in i until text.length) {
                when (text[j]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            jsons.add(text.substring(i, j + 1))
                            i = j
                            break
                        }
                    }
                }
            }
        }
        i++
    }
    for (json in jsons) {
        log.info("  {} JSON: {}", dir, json.take(500))
    }
    // Also write raw payload to sequential dump files
    val dumpDir = File("/tmp/arena-capture/payloads")
    dumpDir.mkdirs()
    val seq = System.nanoTime()
    val file = File(dumpDir, "${dir.replace("→", "-")}_${frameTypeName(ft)}_$seq.bin")
    file.writeBytes(bytes)
}

private fun frameTypeName(ft: Byte) = when (ft) {
    ArenaFrameDecoder.Companion.TYPE_CTRL_INIT -> "CTRL_INIT"
    ArenaFrameDecoder.Companion.TYPE_CTRL_ACK -> "CTRL_ACK"
    ArenaFrameDecoder.Companion.TYPE_DATA_FD -> "DATA"
    ArenaFrameDecoder.Companion.TYPE_DATA_MATCH -> "MATCH_DATA"
    else -> "0x${String.format("%02x", ft)}"
}
