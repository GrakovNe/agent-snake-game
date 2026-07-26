package org.grakovne.snake.agent.web

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.grakovne.snake.agent.core.GameStatus
import org.grakovne.snake.agent.core.SnakeGame
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Broadcast server for the web show: one game loop on the backend, any number of
 * browser viewers. `/` serves the page, `/events` is a Server-Sent-Events stream
 * of game snapshots (~25 fps), shared by all clients — a TV channel, not a session.
 */
class WebShowServer(port: Int, private val fieldWidth: Int, private val fieldHeight: Int) {

    @Volatile
    private var frame: String = "{}"
    private var lastPublishNanos = 0L

    private val clients = ConcurrentHashMap.newKeySet<HttpExchange>()
    private val page: ByteArray =
        javaClass.getResourceAsStream("/webshow.html")!!.readBytes()

    init {
        val server = HttpServer.create(InetSocketAddress(port), 0)
        server.executor = Executors.newCachedThreadPool()

        server.createContext("/") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, page.size.toLong())
            exchange.responseBody.use { it.write(page) }
        }

        server.createContext("/events") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.responseHeaders.add("Cache-Control", "no-cache")
            exchange.sendResponseHeaders(200, 0)
            clients.add(exchange)
            // the exchange stays open; the broadcaster thread writes frames
        }

        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(
            { broadcast() }, 0, 40, TimeUnit.MILLISECONDS,
        )

        server.start()
        println("webshow: http://localhost:$port  (broadcasting to all connected viewers)")
    }

    private fun broadcast() {
        val payload = "data: $frame\n\n".toByteArray()
        for (client in clients) {
            try {
                client.responseBody.write(payload)
                client.responseBody.flush()
            } catch (_: Exception) {
                clients.remove(client)
                runCatching { client.close() }
            }
        }
    }

    /** Called from the game thread; cheap and self-throttling. */
    fun render(game: SnakeGame) {
        val now = System.nanoTime()
        val terminal = game.status != GameStatus.RUNNING
        if (!terminal && now - lastPublishNanos < 30_000_000) return
        lastPublishNanos = now

        val sb = StringBuilder(game.snake.size * 5 + 64)
        sb.append("{\"w\":").append(fieldWidth)
            .append(",\"h\":").append(fieldHeight)
            .append(",\"s\":").append(game.score)
            .append(",\"st\":\"").append(game.status.name).append('"')
            .append(",\"f\":").append(game.food.y * fieldWidth + game.food.x)
            .append(",\"b\":[")
        for (i in game.snake.indices) {
            if (i > 0) sb.append(',')
            val p = game.snake[i]
            sb.append(p.y * fieldWidth + p.x)
        }
        sb.append("]}")
        frame = sb.toString()
    }
}
