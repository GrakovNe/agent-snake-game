package org.grakovne.snake.agent.web

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.grakovne.snake.agent.core.GameResult
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
class WebShowServer(
    port: Int,
    private val fieldWidth: Int,
    private val fieldHeight: Int,
    private val strategyName: String = "",
    private val adminToken: String? = null,
) {

    @Volatile
    private var frame: String = "{}"
    private var lastPublishNanos = 0L

    private val clients = ConcurrentHashMap.newKeySet<HttpExchange>()
    private val page: ByteArray =
        javaClass.getResourceAsStream("/webshow.html")!!.readBytes()
    private val adminPage: ByteArray =
        javaClass.getResourceAsStream("/admin.html")!!.readBytes()

    private val startedAt = System.currentTimeMillis()
    private val finished = ArrayDeque<Triple<Long, GameResult, Long>>() // round, result, durationMs
    @Volatile private var currentRound = 0L
    @Volatile private var currentSeed = 0L
    @Volatile private var lastSteps = 0
    @Volatile private var lastStepsNanos = System.nanoTime()
    @Volatile private var stepsPerSec = 0.0
    @Volatile private var currentSteps = 0

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

        server.createContext("/admin") { exchange ->
            val query = exchange.requestURI.query ?: ""
            if (adminToken != null && !query.contains("token=$adminToken")) {
                exchange.sendResponseHeaders(403, -1)
                exchange.close()
                return@createContext
            }
            if (exchange.requestURI.path.endsWith("/stats")) {
                val body = statsJson().toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            } else {
                exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
                exchange.sendResponseHeaders(200, adminPage.size.toLong())
                exchange.responseBody.use { it.write(adminPage) }
            }
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

    /** Number of connected viewers — lets the game loop idle when nobody watches. */
    fun viewers(): Int = clients.size

    /** Called from the game thread; cheap and self-throttling. */
    fun render(game: SnakeGame) {
        if (clients.isEmpty()) return   // nobody watching: skip frame building entirely
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
        currentSteps = game.steps

        val elapsed = now - lastStepsNanos
        if (elapsed > 500_000_000) {
            stepsPerSec = (game.steps - lastSteps) * 1e9 / elapsed
            lastSteps = game.steps
            lastStepsNanos = now
        }
    }

    fun newGame(round: Long, seed: Long) {
        currentRound = round
        currentSeed = seed
        lastSteps = 0
        lastStepsNanos = System.nanoTime()
    }

    fun gameFinished(round: Long, result: GameResult, durationMs: Long) {
        synchronized(finished) {
            finished.addLast(Triple(round, result, durationMs))
            while (finished.size > 200) finished.removeFirst()
        }
    }

    private fun statsJson(): String {
        val area = fieldWidth * fieldHeight
        val snapshot = synchronized(finished) { finished.toList() }
        val scores = snapshot.map { it.second.score }
        val target = area - 1
        val deaths = snapshot.mapNotNull { it.second.deathReason?.name }
            .groupingBy { it }.eachCount()
        val sb = StringBuilder(4096)
        sb.append("{\"uptimeSec\":").append((System.currentTimeMillis() - startedAt) / 1000)
            .append(",\"viewers\":").append(clients.size)
            .append(",\"size\":\"").append(fieldWidth).append('x').append(fieldHeight).append('"')
            .append(",\"strategy\":\"").append(strategyName).append('"')
            .append(",\"round\":").append(currentRound)
            .append(",\"seed\":").append(currentSeed)
            .append(",\"stepsPerSec\":").append("%.0f".format(java.util.Locale.ROOT, stepsPerSec))
            .append(",\"currentSteps\":").append(currentSteps)
            .append(",\"games\":").append(snapshot.size)
            .append(",\"wins\":").append(snapshot.count { it.second.status == GameStatus.WON })
            .append(",\"onTarget\":").append(scores.count { it >= target })
            .append(",\"meanScore\":").append(
                if (scores.isEmpty()) 0 else "%.1f".format(java.util.Locale.ROOT, scores.average())
            )
            .append(",\"maxScore\":").append(scores.maxOrNull() ?: 0)
            .append(",\"deaths\":{")
        deaths.entries.forEachIndexed { i, (k, v) ->
            if (i > 0) sb.append(',')
            sb.append('"').append(k).append("\":").append(v)
        }
        sb.append("},\"recent\":[")
        snapshot.takeLast(25).asReversed().forEachIndexed { i, (round, r, dur) ->
            if (i > 0) sb.append(',')
            sb.append("{\"round\":").append(round)
                .append(",\"seed\":").append(r.seed)
                .append(",\"score\":").append(r.score)
                .append(",\"steps\":").append(r.steps)
                .append(",\"status\":\"").append(r.status.name).append('"')
                .append(",\"reason\":\"").append(r.deathReason?.name ?: "").append('"')
                .append(",\"durMs\":").append(dur)
                .append('}')
        }
        sb.append("]}")
        return sb.toString()
    }
}
