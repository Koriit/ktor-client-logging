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

open class ClientLogging(config: Config) {

    private val LOG = config.logger ?: logger {}

    protected open val fullUrl = config.logFullUrl
    protected open val headers = config.logHeaders
    protected open val body = config.logBody

    open class Config {
        var logger: Logger? = null

        var logFullUrl = false
        var logHeaders = false
        var logBody = false
    }

    protected open suspend fun logRequest(request: HttpRequestBuilder) {
        val log = StringBuilder()
        if (fullUrl) {
            log.append("Sending request: ${request.method.value} ${Url(request.url)}")
        } else {
            log.append("Sending request: ${request.method.value} ${request.url.host}")
        }

        val content = request.body as OutgoingContent

        if (headers) appendHeaders(log, request.headers.entries(), content.headers)
        if (body) appendRequestBody(log, content)

        LOG.info(log.toString())
    }

    protected open suspend fun logResponse(response: HttpResponse) = response.use {
        val log = StringBuilder()
        val duration = response.responseTime.timestamp - response.requestTime.timestamp

        if (fullUrl) {
            log.append("Received response: $duration ms - ${response.status.value} - ${response.call.request.method.value} ${response.call.request.url}")
        } else {
            log.append("Received response: $duration ms - ${response.status.value} - ${response.call.request.method.value} ${response.call.request.url.host}")
        }

        if (headers) appendHeaders(log, response.headers.entries())

        if (body) {
            appendResponseBody(log, response.contentType(), response.content)
        } else {
            response.content.discard()
        }

        LOG.info(log.toString())
    }

    protected open fun appendHeaders(
            log: StringBuilder,
            requestHeaders: Set<Map.Entry<String, List<String>>>,
            contentHeaders: Headers? = null
    ) {
        log.appendln()
        requestHeaders.forEach { (header, values) ->
            log.appendln("$header: ${values.joinToString("; ")}")
        }

        contentHeaders?.forEach { header, values ->
            log.appendln("$header: ${values.joinToString("; ")}")
        }
    }

    protected open suspend fun appendResponseBody(log: StringBuilder, contentType: ContentType?, content: ByteReadChannel) {
        log.appendln()
        log.appendln(content.readText(contentType?.charset() ?: Charsets.UTF_8))
    }

    protected open suspend fun appendRequestBody(log: StringBuilder, content: OutgoingContent) {
        log.appendln()
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
        text?.let { log.appendln(it) }
    }


    protected open fun install(scope: HttpClient) {
        scope.sendPipeline.intercept(HttpSendPipeline.Before) {
            try {
                logRequest(context)
            } catch (e: Throwable) {
                LOG.warn(e.message, e)
            }
        }

        val observer: ResponseHandler = {
            try {
                logResponse(it)
            } catch (e: Throwable) {
                LOG.warn(e.message, e)
            }
        }

        ResponseObserver.install(ResponseObserver(observer), scope)
    }

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

suspend fun ByteReadChannel.readText(charset: Charset): String = readRemaining().readText(charset = charset)
