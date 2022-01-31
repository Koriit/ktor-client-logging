package com.korrit.kotlin.ktor.client.features.logging

import com.koriit.kotlin.slf4j.logger
import io.ktor.client.HttpClient
import io.ktor.client.features.HttpClientFeature
import io.ktor.client.features.observer.ResponseHandler
import io.ktor.client.features.observer.ResponseObserver
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.HttpSendPipeline
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.Url
import io.ktor.http.charset
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.util.AttributeKey
import io.ktor.util.pipeline.PipelineContext
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.core.readText
import io.ktor.utils.io.discard
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

        // TODO add filtering
    }

    protected open suspend fun logRequest(pipeline: PipelineContext<Any, HttpRequestBuilder>): Any {
        if (body) {
            return logRequestWithBody(pipeline)
        }

        // Since we are not logging request body we can log immediately and continue pipeline normally
        log.info(
            StringBuilder().apply {
                appendRequest(pipeline.context)
            }.toString()
        )

        return pipeline.subject
    }

    /**
     * To log request payload we need to duplicate request stream
     * which is why this function returns a new pipeline subject to proceed with.
     */
    protected suspend fun logRequestWithBody(pipeline: PipelineContext<Any, HttpRequestBuilder>): Any {
        val request = pipeline.context
        val content = request.body as OutgoingContent
        val charset = content.contentType?.charset() ?: Charsets.UTF_8

        // logging a request body is harder than logging a response body because
        // there is no public api for observing request body stream
        val (observer, observed) = pipeline.observe()
        // launch a coroutine that will eventually log the request once it is fully written
        pipeline.launch(Dispatchers.Unconfined) {
            val requestBody = observer.readRemaining().readText(charset = charset)

            log.info(
                StringBuilder().apply {
                    appendRequest(request)
                    // empty line before body as in HTTP request
                    appendLine()
                    append(requestBody)
                    // new line after body because in the log there might be additional info after "log message"
                    // and we don't want it to be mixed with logged body
                    appendLine()
                }.toString()
            )
        }

        return observed
    }

    protected open suspend fun logResponse(response: HttpResponse) {
        log.info(
            StringBuilder().apply {
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
            }.toString()
        )
    }

    protected open fun StringBuilder.appendHeaders(
        requestHeaders: Set<Map.Entry<String, List<String>>>,
        contentHeaders: Headers? = null
    ) {
        appendLine()
        requestHeaders.forEach { (header, values) ->
            appendLine("$header: ${values.joinToString("; ")}")
        }

        contentHeaders?.forEach { header, values ->
            appendLine("$header: ${values.joinToString("; ")}")
        }
    }

    protected open fun StringBuilder.appendRequest(request: HttpRequestBuilder) {
        if (fullUrl) {
            append("Sending request: ${request.method.value} ${Url(request.url)}")
        } else {
            append("Sending request: ${request.method.value} ${request.url.host}")
        }

        val content = request.body as OutgoingContent

        if (headers) {
            appendHeaders(request.headers.entries(), content.headers)
        }
    }

    protected open suspend fun StringBuilder.appendResponseBody(contentType: ContentType?, content: ByteReadChannel) {
        appendLine()
        // new line after body because in the log there might be additional info after "log message"
        appendLine(content.readRemaining().readText(charset = contentType?.charset() ?: Charsets.UTF_8))
    }

    /**
     * Feature installation.
     */
    protected open fun install(scope: HttpClient) {
        scope.sendPipeline.intercept(HttpSendPipeline.Monitoring) {
            @Suppress("TooGenericExceptionCaught") // intended
            try {
                proceedWith(logRequest(this))
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
