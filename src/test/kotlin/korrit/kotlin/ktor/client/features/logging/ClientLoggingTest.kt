package korrit.kotlin.ktor.client.features.logging

import com.koriit.kotlin.slf4j.logger
import com.koriit.kotlin.slf4j.mdc.correlation.correlateThread
import io.ktor.client.HttpClient
import io.ktor.client.content.LocalFileContent
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.content.TextContent
import io.ktor.http.ContentType
import io.ktor.http.content.OutputStreamContent
import io.ktor.http.headersOf
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.io.File

internal class ClientLoggingTest {

    init {
        correlateThread()
    }

    private val log = spyk(logger {})

    private val client = HttpClient(MockEngine) {
        install(ClientLogging) {
            logFullUrl = true
            logHeaders = true
            logBody = true
            logger = log
        }

        engine {
            addHandler { request ->
                val headers = headersOf(
                    "My-Header" to listOf("My-Value")
                )
                val body = request.body
                if (body is TextContent) {
                    respond(body.bytes(), headers = headers)
                } else {
                    respond("OK", headers = headers)
                }
            }
        }
    }

    @Test
    fun `Should log no-content requests and responses`() {
        runBlocking {
            client.get<String>("/")
        }
        verify(exactly = 2) { log.info(any()) }
    }

    @Test
    fun `Should log text requests and responses`() {
        runBlocking {
            client.post<String>("/") {
                body = """{ "field": 1337 }"""
            }
        }
        verify(exactly = 2) { log.info(any()) }
    }

    @Test
    fun `Should log reader-channel requests and responses`() {
        runBlocking {
            client.post<String>("/") {
                body = LocalFileContent(File(javaClass.getResource("/sample_body.json").toURI()))
            }
        }
        verify(exactly = 2) { log.info(any()) }
    }

    @Test
    fun `Should log writer-channel requests and responses`() {
        runBlocking {
            client.post<String>("/") {
                body = OutputStreamContent(
                    body = {
                        @Suppress("BlockingMethodInNonBlockingContext")
                        write(javaClass.getResourceAsStream("/sample_body.json").readBytes())
                    },
                    contentType = ContentType.Any
                )
            }
        }
        verify(exactly = 2) { log.info(any()) }
    }

    @Test
    fun `Should log body, full url and headers`() {
        runBlocking {
            client.post<String>("/api?queryParam=true") {
                headers.append("My-Header", "My-Request-Value")
                body = "SOME_BODY"
            }
        }
        val payloads = mutableListOf<String>()

        verify(exactly = 2) {
            log.info(
                withArg {
                    payloads.add(it)
                }
            )
        }
        val request = payloads[0]
        Assertions.assertTrue(request.contains("POST"))
        Assertions.assertTrue(request.contains("/api?queryParam=true"))
        Assertions.assertTrue(request.contains("My-Header"))
        Assertions.assertTrue(request.contains("My-Request-Value"))
        Assertions.assertTrue(request.contains("SOME_BODY"))

        val response = payloads[1]
        Assertions.assertTrue(response.contains("200"))
        Assertions.assertTrue(response.contains("My-Header"))
        Assertions.assertTrue(response.contains("My-Value"))
        Assertions.assertTrue(response.contains("SOME_BODY"))
    }

    @Test
    fun `Should log body and return body`() {
        val responseBody = runBlocking {
            client.post<String>("/") {
                body = "SOME_BODY"
            }
        }
        val payloads = mutableListOf<String>()

        verify(exactly = 2) {
            log.info(
                withArg {
                    payloads.add(it)
                }
            )
        }

        Assertions.assertEquals("SOME_BODY", responseBody)

        val request = payloads[0]
        Assertions.assertTrue(request.contains("POST"))
        Assertions.assertTrue(request.contains("SOME_BODY"))

        val response = payloads[1]
        Assertions.assertTrue(response.contains("200"))
        Assertions.assertTrue(response.contains("SOME_BODY"))
    }

    // TODO add more tests as in server logging
}
