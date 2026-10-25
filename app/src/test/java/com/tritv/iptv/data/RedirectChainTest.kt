package com.tritv.iptv.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class RedirectChainTest {

    // A bare HTTP server on a plain socket: the Android unit-test classpath has no HTTP server.
    private lateinit var server: ServerSocket
    private lateinit var base: String

    private val redirects = mapOf(
        "/live/ad.m3u8" to "/applive/mkt.m3u8?app=1",
        "/applive/mkt.m3u8?app=1" to "/appadhw/cctv.m3u8",
        "/appadhw/cctv.m3u8" to "/applive/cctv.m3u8?u=1",
        "/live/ok.m3u8" to "/applive/cctv.m3u8?u=1",
    )

    @Before
    fun start() {
        server = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
        base = "http://127.0.0.1:${server.localPort}"
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use { s ->
                    val reader = s.getInputStream().bufferedReader()
                    val path = reader.readLine()?.split(' ')?.getOrNull(1) ?: return@use
                    while (reader.readLine()?.isNotEmpty() == true) Unit
                    val to = redirects[path]
                    val response = when {
                        to != null -> "HTTP/1.1 302 Found\r\nLocation: $to\r\nContent-Length: 0\r\n\r\n"
                        path.startsWith("/applive/cctv.m3u8") -> "HTTP/1.1 200 OK\r\nContent-Length: 8\r\n\r\n#EXTM3U\n"
                        else -> "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n"
                    }
                    s.getOutputStream().apply { write(response.toByteArray()); flush() }
                }
            }
        }
    }

    @After
    fun stop() = server.close()

    private fun resolve(path: String) = RedirectChain.resolve(base + path, "test", null, 2_000)

    @Test
    fun advertHopInTheMiddleIsCaughtThoughTheEndLooksNormal() {
        assertEquals(RedirectChain.Result.Ad, resolve("/live/ad.m3u8"))
    }

    @Test
    fun normalChainEndsAtTheStream() {
        assertEquals(RedirectChain.Result.Final("$base/applive/cctv.m3u8?u=1"), resolve("/live/ok.m3u8"))
    }

    @Test
    fun directAndBrokenAddresses() {
        assertEquals(RedirectChain.Result.Direct, resolve("/applive/cctv.m3u8"))
        assertEquals(RedirectChain.Result.Failed, resolve("/gone"))
    }
}
