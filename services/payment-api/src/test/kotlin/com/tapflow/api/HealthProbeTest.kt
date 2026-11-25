package com.tapflow.api

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HealthProbeTest {
    @Test
    fun `probe accepts ready 200 and rejects unavailable and redirect responses`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val code = exchange.requestURI.path.removePrefix("/").toInt()
            exchange.sendResponseHeaders(code, -1)
            exchange.close()
        }
        server.start()
        try {
            for (code in listOf(200, 302, 503)) assertEquals(
                code == 200,
                HealthProbe.healthy(URI("http://127.0.0.1:${server.address.port}/$code")),
            )
        } finally {
            server.stop(0)
        }
    }
}
