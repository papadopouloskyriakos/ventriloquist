package net.nuclearlighters.ventriloquist.http

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * A minimal HTTP/1.1 server on a plain [ServerSocket]: one request per connection, a small
 * fixed worker pool and a bounded queue (excess connections are closed), read timeouts on
 * every socket. [handler] does the routing and must not throw.
 */
class HttpServer(
    private val port: Int,
    private val handler: (HttpRequest) -> HttpResponse,
    private val log: (String) -> Unit = {},
) {
    private var socket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val pool = ThreadPoolExecutor(
        WORKERS, WORKERS, 30, TimeUnit.SECONDS, ArrayBlockingQueue(QUEUE),
        { r -> Thread(r, "ventriloquist-http").apply { isDaemon = true } },
    )

    @Volatile
    private var running = false

    val localPort: Int get() = socket?.localPort ?: -1

    @Synchronized
    fun start() {
        if (running) return
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(port))
        socket = s
        running = true
        acceptThread = Thread({ acceptLoop(s) }, "ventriloquist-http-accept").apply {
            isDaemon = true
            start()
        }
        log("control endpoint listening on port ${s.localPort}")
    }

    @Synchronized
    fun stop() {
        running = false
        try {
            socket?.close()
        } catch (e: IOException) {
            // closing anyway
        }
        socket = null
        pool.shutdownNow()
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running) {
            val client = try {
                server.accept()
            } catch (e: SocketException) {
                break // closed by stop()
            } catch (e: IOException) {
                log("accept failed: ${e.message}")
                continue
            }
            try {
                pool.execute { serve(client) }
            } catch (e: RejectedExecutionException) {
                closeQuietly(client)
            }
        }
    }

    private fun serve(client: Socket) {
        client.use { s ->
            try {
                s.soTimeout = READ_TIMEOUT_MS
                val input = BufferedInputStream(s.getInputStream())
                val output = BufferedOutputStream(s.getOutputStream())
                val response = try {
                    val request = HttpParser.parse(input) ?: return
                    handler(request)
                } catch (e: HttpError) {
                    HttpResponse(e.status, errorBody(e.message ?: "bad request"))
                }
                response.writeTo(output)
            } catch (e: IOException) {
                log("connection from ${s.inetAddress?.hostAddress}: ${e.message}")
            }
        }
    }

    private fun closeQuietly(s: Socket) {
        try {
            s.close()
        } catch (e: IOException) {
            // nothing to do
        }
    }

    companion object {
        const val WORKERS = 4
        const val QUEUE = 8
        const val READ_TIMEOUT_MS = 15_000

        fun errorBody(message: String): String =
            net.nuclearlighters.ventriloquist.util.Json.write(mapOf("error" to message))
    }
}
