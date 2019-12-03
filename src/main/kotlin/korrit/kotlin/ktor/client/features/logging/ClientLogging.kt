package korrit.kotlin.ktor.client.features.logging

import io.ktor.client.HttpClient
import io.ktor.client.features.HttpClientFeature
import io.ktor.client.features.observer.ResponseHandler
import io.ktor.client.features.observer.ResponseObserver
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.HttpSendPipeline
import io.ktor.client.response.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.Url
import io.ktor.http.charset
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.util.AttributeKey
import koriit.kotlin.slf4j.logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.io.ByteChannel
import kotlinx.coroutines.io.ByteReadChannel
import kotlinx.coroutines.io.close
import kotlinx.coroutines.io.discard
import kotlinx.coroutines.io.readRemaining
import kotlinx.coroutines.launch
import kotlinx.io.charsets.Charset
import kotlinx.io.core.readText
import org.slf4j.Logger

/**
 * Logging client feature. Allows logging performance, requests and responses.
 */
open class ClientLogging(config: Config) {

    private val log = config.logger ?: logger {}

    protected open val fullUrl = config.logFullUrl
    protected open val headers = config.logHeaders
    protected open val body = config.logBody

    /**
     * Feature configuration.
     */
    open class Config {
        /**
         * Custom logger instance.
         */
        var logger: Logger? = null

        /**
         * Whether to log full request/response urls.
         *
         * WARN: url queries may contain sensitive data.
         */
        var logFullUrl = false

        /**
         * Whether to log request/response headers.
         *
         * WARN: headers may contain sensitive data.
         */
        var logHeaders = false

        /**
         * Whether to log request/response payloads.
         *
         * WARN: payloads may contain sensitive data.
         */
        var logBody = false
    }

    protected open suspend fun logRequest(request: HttpRequestBuilder) {
        log.info(StringBuilder().apply {
            if (fullUrl) {
                append("Sending request: ${request.method.value} ${Url(request.url)}")
            } else {
                append("Sending request: ${request.method.value} ${request.url.host}")
            }

            val content = request.body as OutgoingContent

            if (headers) appendHeaders(request.headers.entries(), content.headers)
            if (body) appendRequestBody(content)
        }.toString())
    }

    protected open suspend fun logResponse(response: HttpResponse) = response.use {
        log.info(StringBuilder().apply {
            val duration = response.responseTime.timestamp - response.requestTime.timestamp

            if (fullUrl) {
                append("Received response: $duration ms - ${response.status.value} - ${response.call.request.method.value} ${response.call.request.url}")
            } else {
                append("Received response: $duration ms - ${response.status.value} - ${response.call.request.method.value} ${response.call.request.url.host}")
            }

            if (headers) appendHeaders(response.headers.entries())

            if (body) {
                appendResponseBody(response.contentType(), response.content)
            } else {
                response.content.discard()
            }
        }.toString())
    }

    protected open fun StringBuilder.appendHeaders(
        requestHeaders: Set<Map.Entry<String, List<String>>>,
        contentHeaders: Headers? = null
    ) {
        appendln()
        requestHeaders.forEach { (header, values) ->
            appendln("$header: ${values.joinToString("; ")}")
        }

        contentHeaders?.forEach { header, values ->
            appendln("$header: ${values.joinToString("; ")}")
        }
    }

    protected open suspend fun StringBuilder.appendResponseBody(contentType: ContentType?, content: ByteReadChannel) {
        appendln()
        appendln(content.readText(contentType?.charset() ?: Charsets.UTF_8))
    }

    protected open suspend fun StringBuilder.appendRequestBody(content: OutgoingContent) {
        appendln()
        val charset = content.contentType?.charset() ?: Charsets.UTF_8

        val text = when (content) {
            is OutgoingContent.WriteChannelContent -> {
                val textChannel = ByteChannel()
                GlobalScope.launch(Dispatchers.Unconfined) {
                    content.writeTo(textChannel)
                    textChannel.close()
                }
                textChannel.readText(charset)
            }
            is OutgoingContent.ReadChannelContent -> {
                content.readFrom().readText(charset)
            }
            is OutgoingContent.ByteArrayContent -> kotlinx.io.core.String(content.bytes(), charset = charset)
            else -> null
        }
        text?.let { appendln(it) }
    }

    /**
     * Feature installation.
     */
    protected open fun install(scope: HttpClient) {
        scope.sendPipeline.intercept(HttpSendPipeline.Before) {
            @Suppress("TooGenericExceptionCaught") // intended
            try {
                logRequest(context)
            } catch (e: Throwable) {
                log.warn(e.message, e)
            }
        }

        val observer: ResponseHandler = {
            @Suppress("TooGenericExceptionCaught") // intended
            try {
                logResponse(it)
            } catch (e: Throwable) {
                log.warn(e.message, e)
            }
        }

        ResponseObserver.install(ResponseObserver(observer), scope)
    }

    /**
     * Feature installation object.
     */
    companion object : HttpClientFeature<Config, ClientLogging> {
        override val key: AttributeKey<ClientLogging> = AttributeKey("ClientLogging")

        override fun prepare(block: Config.() -> Unit): ClientLogging {
            val config = Config().apply(block)
            return ClientLogging(config)
        }

        override fun install(feature: ClientLogging, scope: HttpClient) {
            feature.install(scope)
        }
    }
}

/**
 * Read all remaining text in the channel.
 */
suspend fun ByteReadChannel.readText(charset: Charset): String = readRemaining().readText(charset = charset)
