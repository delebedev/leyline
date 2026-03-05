package leyline.infra

import io.netty.bootstrap.Bootstrap
import io.netty.buffer.ByteBuf
import io.netty.channel.*
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import org.slf4j.Logger
import org.slf4j.LoggerFactory

// ---------------------------------------------------------------------------
// Proxy handlers: relay raw bytes while logging client frames.
// Simple auto-read on both sides — no manual flow control.
// ---------------------------------------------------------------------------

/**
 * Client-side proxy handler. On connect, opens TLS upstream and relays
 * bytes bidirectionally. Logs client frame headers for debugging.
 */
class ProxyFrontHandler(
    private val workerGroup: EventLoopGroup,
    private val remoteHost: String,
    private val remotePort: Int,
    private val door: String = "FD",
    private val captureSink: CaptureSink,
) : ChannelInboundHandlerAdapter() {

    private val log = LoggerFactory.getLogger(ProxyFrontHandler::class.java)

    @Volatile private var outboundChannel: Channel? = null
    private val pendingWrites = mutableListOf<Any>()

    override fun channelActive(ctx: ChannelHandlerContext) {
        val inbound = ctx.channel()
        log.info("Proxy [{}]: client from {}, connecting upstream {}:{}", door, inbound.remoteAddress(), remoteHost, remotePort)

        val upstreamSsl = SslContextBuilder.forClient()
            .trustManager(InsecureTrustManagerFactory.INSTANCE)
            .build()

        val b = Bootstrap()
            .group(workerGroup)
            .channel(NioSocketChannel::class.java)
            .handler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    ch.pipeline().addLast("ssl", upstreamSsl.newHandler(ch.alloc(), remoteHost, remotePort))
                    ch.pipeline().addLast("relay", RelayHandler(inbound, "S→C", door, captureSink))
                }
            })

        b.connect(remoteHost, remotePort).addListener(
            ChannelFutureListener { future ->
                if (future.isSuccess) {
                    outboundChannel = future.channel()
                    log.info("Proxy [{}]: upstream connected to {}:{}", door, remoteHost, remotePort)
                    synchronized(pendingWrites) {
                        for (msg in pendingWrites) {
                            future.channel().writeAndFlush(msg)
                        }
                        pendingWrites.clear()
                    }
                } else {
                    log.error("Proxy [{}]: upstream connect failed: {}", door, future.cause().message)
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
            synchronized(pendingWrites) { pendingWrites.add(msg) }
        }
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        log.info("Proxy [{}]: client disconnected", door)
        outboundChannel?.close()
        if (door == "MD") captureSink.flushMdFrames()
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        log.error("Proxy [{}]: {}", door, cause.message)
        ctx.close()
    }

    private fun logFrame(dir: String, buf: ByteBuf) = logClientFrame(log, "$door:$dir", buf, captureSink)
}

/** Relays bytes from one channel to another, logging frame headers + payloads. */
class RelayHandler(
    private val relayTarget: Channel,
    private val direction: String,
    private val door: String = "FD",
    private val captureSink: CaptureSink,
) : ChannelInboundHandlerAdapter() {

    private val log = LoggerFactory.getLogger(RelayHandler::class.java)

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (msg is ByteBuf) logClientFrame(log, "$door:$direction", msg, captureSink)
        if (relayTarget.isActive) {
            relayTarget.writeAndFlush(msg)
        } else {
            (msg as? ByteBuf)?.release()
        }
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        log.info("Proxy [{}]: {} channel closed", door, direction)
        if (relayTarget.isActive) relayTarget.close()
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        log.error("Proxy [{}] {}: {}", door, direction, cause.message)
        ctx.close()
    }
}

/** Log client frame header fields for proxy debugging. */
internal fun logClientFrame(log: Logger, dir: String, buf: ByteBuf, captureSink: CaptureSink) {
    if (buf.readableBytes() < 6) return
    val idx = buf.readerIndex()
    val ft = buf.getByte(idx + 1)
    val pl = buf.getIntLE(idx + 2)
    val tn = frameTypeName(ft)
    log.trace("  {} type={} payload={} total={}", dir, tn, pl, buf.readableBytes())
    captureSink.ingestChunk(dir, buf)
}
