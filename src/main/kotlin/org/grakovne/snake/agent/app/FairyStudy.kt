package org.grakovne.snake.agent.app

import org.grakovne.snake.agent.core.GameConfig
import org.grakovne.snake.agent.core.GameStatus
import org.grakovne.snake.agent.core.Position
import org.grakovne.snake.agent.core.SnakeGame
import org.grakovne.snake.agent.strategy.Strategies
import org.grakovne.snake.agent.strategy.search.BoardSearch
import java.io.File
import java.util.Locale
import kotlin.random.Random

/**
 * Paired counterfactual study over fairy scripts: for every winning script the
 * same botSeed is replayed twice — honestly (the control, which loses) and under
 * the fairy spawn script (which wins). The two games share the bot, the board
 * and the spawn prefix; they differ only in late luck. Compares board structure
 * at fill thresholds and reports where the winning timeline branched off.
 *
 * ./gradlew fairystudy -Pin=data/fairy-30.txt -Psize=30
 */
fun main() {
    val size = intProp("size", 30)
    val inPath = prop("in", "data/fairy-$size.txt")
    val thresholds = intArrayOf(90, 93, 95, 97, 99)

    val area = size * size
    data class Snap(val iso: Int, val comps: Int, val undig: Int)
    data class Run(val snaps: Map<Int, Snap>, val game: SnakeGame)

    fun play(strategyName: String, botSeed: Long, script: List<Position>?): Run {
        val game = SnakeGame(
            GameConfig(width = size, height = size, seed = botSeed * 31),
            spawnScript = script,
        )
        val bot = Strategies.create(strategyName, Random(botSeed))
        val board = BoardSearch(size, size)
        val snaps = HashMap<Int, Snap>()
        var next = 0
        while (game.status == GameStatus.RUNNING) {
            game.step(bot.nextMove(game))
            if (next < thresholds.size && 100 * game.score >= thresholds[next] * area) {
                board.load(game)
                snaps[thresholds[next]] = Snap(
                    iso = board.deadFreeCells(),
                    comps = board.freeComponents(),
                    undig = board.undigestibleHolesNow(2),
                )
                next++
            }
        }
        return Run(snaps, game)
    }

    val lines = File(inPath).readLines().mapNotNull { line ->
        val parts = line.trim().split(" ")
        if (parts.size != 3) return@mapNotNull null
        Triple(parts[0], parts[1].toLong(), parts[2].split(",").map {
            val v = it.toInt()
            Position(v % size, v / size)
        })
    }
    println("fairystudy: ${lines.size} winning scripts from $inPath, field ${size}x$size")

    val winSnaps = HashMap<Int, MutableList<Snap>>()
    val loseSnaps = HashMap<Int, MutableList<Snap>>()
    val divergenceFills = ArrayList<Double>()
    var controls = 0
    var controlLost = 0

    for ((name, botSeed, script) in lines) {
        val win = play(name, botSeed, script)
        val lose = play(name, botSeed, null)
        if (win.game.status != GameStatus.WON) continue
        controls++
        if (lose.game.status == GameStatus.WON) continue   // control won honestly: no contrast
        controlLost++
        for (t in thresholds) {
            win.snaps[t]?.let { winSnaps.getOrPut(t) { ArrayList() }.add(it) }
            lose.snaps[t]?.let { loseSnaps.getOrPut(t) { ArrayList() }.add(it) }
        }
        val a = win.game.spawnLog
        val b = lose.game.spawnLog
        var i = 0
        while (i < minOf(a.size, b.size) && a[i] == b[i]) i++
        // fill level at the divergence spawn: body ~ initial + i
        divergenceFills.add(100.0 * (3 + i) / area)
    }

    println("pairs with contrast (win vs honest loss): $controlLost/$controls")
    println()
    println(
        "%-6s %14s %14s %16s".format(Locale.ROOT, "fill", "iso win/lose", "comps win/lose", "undig win/lose")
    )
    for (t in thresholds) {
        val w = winSnaps[t].orEmpty()
        val l = loseSnaps[t].orEmpty()
        if (w.isEmpty() || l.isEmpty()) continue
        fun avg(xs: List<Snap>, f: (Snap) -> Int) = xs.map(f).average()
        println(
            "%-6s %6.2f / %-6.2f %6.2f / %-6.2f %7.2f / %-7.2f".format(
                Locale.ROOT, "$t%",
                avg(w) { it.iso }, avg(l) { it.iso },
                avg(w) { it.comps }, avg(l) { it.comps },
                avg(w) { it.undig }, avg(l) { it.undig },
            )
        )
    }
    println()
    val sorted = divergenceFills.sorted()
    if (sorted.isNotEmpty()) {
        println(
            "divergence fill %%: p10=%.1f p50=%.1f p90=%.1f (share >=95%%: %.0f%%)".format(
                Locale.ROOT,
                sorted[sorted.size / 10],
                sorted[sorted.size / 2],
                sorted[sorted.size * 9 / 10],
                100.0 * sorted.count { it >= 95.0 } / sorted.size,
            )
        )
    }
}
