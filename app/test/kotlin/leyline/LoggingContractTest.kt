package leyline

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.PatternLayout
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import ch.qos.logback.core.rolling.RollingFileAppender
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import leyline.UnitTag
import org.slf4j.LoggerFactory
import java.io.File

class LoggingContractTest :
    FunSpec({
        tags(UnitTag)

        test("key-values are visible in the plain-text event layout") {
            val context = LoggerFactory.getILoggerFactory() as LoggerContext
            val logger = context.getLogger("leyline.logging.contract-test")
            val appender =
                ListAppender<ILoggingEvent>().apply {
                    this.context = context
                    start()
                }
            val priorLevel = logger.level
            val priorAdditive = logger.isAdditive
            logger.level = Level.INFO
            logger.isAdditive = false
            logger.addAppender(appender)
            try {
                logger
                    .atInfo()
                    .addKeyValue("event", "test.rendered")
                    .addKeyValue("match_id", "m42")
                    .log("Test event")

                val event = appender.list.single()
                val layout =
                    PatternLayout().apply {
                        this.context = context
                        pattern = "%msg %kvp"
                        start()
                    }
                assertSoftly {
                    layout.doLayout(event) shouldContain "Test event"
                    layout.doLayout(event) shouldContain "event=\"test.rendered\""
                    layout.doLayout(event) shouldContain "match_id=\"m42\""
                    event.keyValuePairs.map { it.key } shouldBe listOf("event", "match_id")
                }
            } finally {
                logger.detachAppender(appender)
                logger.level = priorLevel
                logger.isAdditive = priorAdditive
                appender.stop()
            }
        }

        test("owned failures retain the throwable while exposing event context") {
            val context = LoggerFactory.getILoggerFactory() as LoggerContext
            val logger = context.getLogger("leyline.logging.contract-failure-test")
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
            try {
                val failure = IllegalStateException("synthetic failure")
                logger
                    .atError()
                    .setCause(failure)
                    .addKeyValue("event", "test.failed")
                    .addKeyValue("error_type", failure::class.simpleName)
                    .log("Owned operation failed")

                val event = appender.list.single()
                assertSoftly {
                    event.level shouldBe Level.ERROR
                    event.formattedMessage shouldBe "Owned operation failed"
                    event.keyValuePairs.map { it.key } shouldContain "event"
                    event.throwableProxy.shouldNotBeNull().className shouldBe IllegalStateException::class.java.name
                }
            } finally {
                logger.detachAppender(appender)
                logger.level = priorLevel
                logger.isAdditive = priorAdditive
                appender.stop()
            }
        }

        test("runtime console and rolling-file patterns render key-values") {
            val config = File("app/main/resources/logback.xml").readText()
            config.lines().filter { it.contains("<pattern>") }.map { it.trim() } shouldBe
                listOf(
                    "<pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg %kvp%n</pattern>",
                    "<pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg %kvp%n</pattern>",
                )
            config shouldContain "<appender name=\"FILE\" class=\"ch.qos.logback.core.rolling.RollingFileAppender\">"
        }

        test("rolling-file appender honors a custom artifacts root") {
            val property = "LEYLINE_LOG_DIR"
            val previous = System.getProperty(property)
            val customRoot =
                kotlin.io.path
                    .createTempDirectory("leyline-log-contract")
                    .toFile()
            val context = LoggerContext()
            try {
                System.setProperty(property, customRoot.absolutePath)
                JoranConfigurator().apply {
                    this.context = context
                    doConfigure(File("app/main/resources/logback.xml"))
                }

                val appender =
                    context
                        .getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
                        .getAppender("FILE") as RollingFileAppender<*>
                appender.file shouldBe File(customRoot, "leyline.log").absolutePath
            } finally {
                context.stop()
                if (previous == null) {
                    System.clearProperty(property)
                } else {
                    System.setProperty(property, previous)
                }
            }
        }
    })
