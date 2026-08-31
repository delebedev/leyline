package leyline.infra

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class ManagementServerTest :
    FunSpec({
        tags(UnitTag)

        test("health-check failure returns 500 and retains the throwable") {
            val context = LoggerFactory.getILoggerFactory() as LoggerContext
            val logger = context.getLogger(ManagementServer::class.java) as Logger
            val appender =
                ListAppender<ILoggingEvent>().apply {
                    this.context = context
                    start()
                }
            val priorLevel = logger.level
            val priorAdditive = logger.isAdditive
            logger.level = Level.ERROR
            logger.isAdditive = false
            logger.addAppender(appender)
            val server = ManagementServer("127.0.0.1", 0) { throw IllegalStateException("health unavailable") }

            try {
                val port = server.start()
                val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/health")).GET().build()
                val response =
                    HttpClient.newHttpClient().send(
                        request,
                        HttpResponse.BodyHandlers.discarding(),
                    )

                val event = appender.list.single()
                val fields = event.keyValuePairs.associate { it.key to it.value }
                assertSoftly {
                    response.statusCode() shouldBe 500
                    event.level shouldBe Level.ERROR
                    event.formattedMessage shouldBe "Management health check failed"
                    fields["event"] shouldBe "server.management_health_failed"
                    fields["subsystem"] shouldBe "management"
                    fields["request"] shouldBe "health"
                    event.throwableProxy.shouldNotBeNull().className shouldBe IllegalStateException::class.java.name
                }
            } finally {
                server.stop()
                logger.detachAppender(appender)
                logger.level = priorLevel
                logger.isAdditive = priorAdditive
                appender.stop()
            }
        }
    })
