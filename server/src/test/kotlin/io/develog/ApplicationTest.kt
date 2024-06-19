package io.develog

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.testing.*
import junit.framework.TestCase.assertFalse
import net.singularity.jetta.server.models.ContextId
import net.singularity.jetta.server.models.ResultDto
import net.singularity.jetta.server.plugins.configureRouting
import net.singularity.jetta.server.services.ReplServiceImpl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApplicationTest {
    private fun ApplicationTestBuilder.setup(): HttpClient {
        application {
            install(ContentNegotiation) {
                json()
            }
            configureRouting(ReplServiceImpl())
        }
        return createClient {
            install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
                json()
            }
        }
    }

    @Test
    fun getContextId() = testApplication {
        val client = setup()
        client.post("/contexts").apply {
            assertEquals(HttpStatusCode.OK, status)
            val str = bodyAsText()
            val contextId = ContextId.fromString(str)
            assertEquals(contextId.toString(), str)
        }
    }

    @Test
    fun eval() = testApplication {
        val client = setup()
        val contextId = client.post("/contexts").let {
            assertEquals(HttpStatusCode.OK, it.status)
            it.bodyAsText()
        }
        client.post("/contexts/$contextId") {
            setBody(
                """
                (: foo (-> Int Int Int))
                (= (foo _x _y) (+ _x _y 1))
                (foo 1 2)
                """.trimIndent().replace('_', '$')
            )
        }.let {
            assertEquals(HttpStatusCode.OK, it.status)
            val result = it.body<ResultDto>()
            assertTrue(result.isSuccess)
            assertEquals("4", result.result)
        }
    }

    @Test
    fun evalWithError() = testApplication {
        val client = setup()
        val contextId = client.post("/contexts").let {
            assertEquals(HttpStatusCode.OK, it.status)
            it.bodyAsText()
        }
        client.post("/contexts/$contextId") {
            setBody(
                """
                (: foo (-> Int Int Int))
                (= (foo _x _y) (+ _x _y 1))
                (foo 1 2)
                """.trimIndent()
            )
        }.let {
            assertEquals(HttpStatusCode.OK, it.status)
            val result = it.body<ResultDto>()
            assertFalse(result.isSuccess)
            assertEquals(2, result.messages.size)
        }
    }
}
