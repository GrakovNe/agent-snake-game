package org.grakovne.snake.agent.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.grakovne.snake.agent.core.GameConfig
import org.grakovne.snake.agent.core.GameStatus
import org.grakovne.snake.agent.core.Position
import org.grakovne.snake.agent.core.SnakeGame
import org.grakovne.snake.agent.strategy.Strategies
import java.io.File
import java.util.Locale
import kotlin.random.Random

/**
 * The benevolent food fairy: searches for a spawn script under which the HONEST
 * bot wins the full board. The bot is untouched — it plays its real game; only
 * its luck is loaded. The engine is deterministic, so a (botSeed, spawnScript)
 * pair replays bit-for-bit on any machine.
 *
 * Search: play honestly; on death, rewind — keep a prefix of the spawn history
 * and re-luck the continuation with a fresh spawn RNG, backing off further on
 * repeated failure, always continuing from the best game seen. Every attempt is
 * a full deterministic replay (bot state cannot be snapshotted, games can).
 *
 * ./gradlew fairy -Psize=30 -Pseeds=20 -Prewinds=60 -Pout=data/fairy-30.txt
 */
fun main() {
    val size = intProp("size", 30)
    val seeds = intProp("seeds", 20)
    val seedFrom = longProp("seedFrom", 500_000)
    val rewinds = intProp("rewinds", 60)
    val strategyName = prop("strategy", "sweep")
    val out = prop("out", "")
    val parallelism = intProp("parallelism", Runtime.getRuntime().availableProcessors())

    val area = size * size
    println("fairy: strategy=$strategyName field=${size}x$size seeds=$seeds rewinds=$rewinds")

    data class Attempt(val game: SnakeGame, val replays: Int)

    fun play(botSeed: Long, script: List<Position>?, salt: Long): SnakeGame {
        val game = SnakeGame(
            GameConfig(width = size, height = size, seed = botSeed * 31 + salt * -0x61C8864680B583EBL),
            spawnScript = script,
        )
        val strategy = Strategies.create(strategyName, Random(botSeed))
        while (game.status == GameStatus.RUNNING) {
            game.step(strategy.nextMove(game))
        }
        return game
    }

    fun search(botSeed: Long): Attempt? {
        var best = play(botSeed, null, 0L)
        var replays = 1
        if (best.status == GameStatus.WON) return Attempt(best, replays)
        var salt = 0L
        var attempt = 0
        while (replays < rewinds) {
            salt++
            attempt++
            // back off deeper on consecutive failures: drop 1, 2, 4, ... last spawns
            val backOff = 1 shl minOf(attempt % 7, 6)
            val prefix = best.spawnLog.dropLast(minOf(backOff, best.spawnLog.size / 2))
            val game = play(botSeed, prefix, salt)
            replays++
            if (game.status == GameStatus.WON) return Attempt(game, replays)
            if (game.score > best.score) {
                best = game
                attempt = 0
            }
        }
        return null
    }

    val started = System.nanoTime()
    val results = runBlocking {
        (0 until seeds).map { i ->
            async(Dispatchers.Default.limitedParallelism(parallelism)) {
                val botSeed = seedFrom + i
                val found = search(botSeed)
                botSeed to found
            }
        }.awaitAll()
    }
    val elapsed = (System.nanoTime() - started) / 1e9

    val wins = results.mapNotNull { (seed, a) -> a?.let { seed to it } }
    val replayCounts = wins.map { it.second.replays }.sorted()
    println(
        "success: ${wins.size}/$seeds  time %.1fs  replays/win p50=%d p90=%d max=%d".format(
            Locale.ROOT,
            elapsed,
            replayCounts.getOrElse(replayCounts.size / 2) { 0 },
            replayCounts.getOrElse(replayCounts.size * 9 / 10) { 0 },
            replayCounts.lastOrNull() ?: 0,
        )
    )

    // closed-loop check: every script must replay to WON with a fresh bot
    var verified = 0
    for ((seed, attemptResult) in wins) {
        val replay = SnakeGame(
            GameConfig(width = size, height = size, seed = 0),
            spawnScript = attemptResult.game.spawnLog.toList(),
        )
        val bot = Strategies.create(strategyName, Random(seed))
        while (replay.status == GameStatus.RUNNING) {
            replay.step(bot.nextMove(replay))
        }
        if (replay.status == GameStatus.WON) verified++
    }
    println("verified replays: $verified/${wins.size}")

    if (out.isNotEmpty() && wins.isNotEmpty()) {
        File(out).parentFile?.mkdirs()
        File(out).appendText(
            wins.joinToString("") { (seed, attemptResult) ->
                val cells = attemptResult.game.spawnLog.joinToString(",") { p -> (p.y * size + p.x).toString() }
                "$strategyName $seed $cells\n"
            }
        )
        println("appended ${wins.size} scripts to $out")
    }
}
