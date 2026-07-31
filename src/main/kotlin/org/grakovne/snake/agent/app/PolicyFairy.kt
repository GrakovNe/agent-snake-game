package org.grakovne.snake.agent.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.grakovne.snake.agent.core.Direction
import org.grakovne.snake.agent.core.GameConfig
import org.grakovne.snake.agent.core.GameStatus
import org.grakovne.snake.agent.core.GameView
import org.grakovne.snake.agent.core.Position
import org.grakovne.snake.agent.core.SnakeGame
import org.grakovne.snake.agent.strategy.Strategies
import java.io.File
import java.util.Locale
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The policy fairy: the move-side twin of the food fairy. The food fairy kept
 * the bot and re-rolled the dice; this one keeps the dice and re-rolls the bot.
 *
 * Search: replay a lost game's recorded moves up to tick t on the SAME engine
 * seed (an identical prefix consumes identical spawn draws, so the dice stream
 * is shared), then hand control to a fresh strategy instance with a different
 * policy RNG. Exponential back-off from the death finds some winning variant;
 * forward refinement pushes its divergence tick as late as possible. The result
 * per game is the latest known open move d: the game played a, a winning
 * timeline plays b, and no probed variant wins diverging after d.
 *
 * Gate (pre-registered): does b's advantage survive a dice re-roll, i.e. is
 * (S_d, prefer b over a) a trainable label or an echo of one lucky spawn
 * stream? For each pair play M CRN-paired continuations per arm: spawn prefix
 * scripted from the original game, fresh spawn RNG after, (dice seed, policy
 * seed) shared across arms; plus a placebo arm c = any other legal move.
 * Trainable iff mean P(win|b) − P(win|a) >= +3.0 pp AND paired t >= 1.70
 * (one-sided p < 0.05); declared before the first gate run.
 *
 * ./gradlew policyfairy -Psize=30 -Pseeds=100 -Pmode=both
 */

private const val SALT = -0x61C8864680B583EBL

private class Recorded(val moves: List<Direction>, val spawns: List<Position>, val status: GameStatus)

private data class PairSpec(
    val botSeed: Long,
    val divergence: Int,
    val deathStep: Int,
    val scoreAtD: Int,
    val orig: Direction,
    val alt: Direction,
)

private class VariantOutcome(val status: GameStatus, val divergence: Int, val altMove: Direction?)

private class GateRow(val spec: PairSpec, val winsA: Int, val winsB: Int, val winsC: Int, val hasPlacebo: Boolean)

fun main() {
    val size = intProp("size", 30)
    val seeds = intProp("seeds", 100)
    val seedFrom = longProp("seedFrom", 700_000)
    val strategyName = prop("strategy", "sweep")
    val variants = intProp("variants", 6)
    val maxBack = intProp("maxBack", 131_072)
    val gateReplays = intProp("gateReplays", 64)
    val mode = prop("mode", "both") // search | gate | both
    val out = prop("out", "data/policy-fairy-$size.txt")
    val input = prop("in", "data/policy-fairy-$size.txt")
    val parallelism = intProp("parallelism", Runtime.getRuntime().availableProcessors())

    val area = size * size

    fun gameSeed(botSeed: Long) = botSeed * 31 // fairy salt-0 scheme: the original dice stream

    fun record(botSeed: Long): Recorded {
        val game = SnakeGame(GameConfig(width = size, height = size, seed = gameSeed(botSeed)))
        val strategy = Strategies.create(strategyName, Random(botSeed))
        val moves = ArrayList<Direction>(area * 8)
        while (game.status == GameStatus.RUNNING) {
            val move = strategy.nextMove(game)
            moves.add(move)
            game.step(move)
        }
        return Recorded(moves, game.spawnLog.toList(), game.status)
    }

    fun feedPrefix(game: SnakeGame, moves: List<Direction>, ticks: Int): Boolean {
        for (i in 0 until ticks) {
            if (game.step(moves[i]) != GameStatus.RUNNING) return false
        }
        return true
    }

    /** Fixed dice stream, fresh policy from tick [t]. */
    fun runVariant(botSeed: Long, moves: List<Direction>, t: Int, policySeed: Long): VariantOutcome? {
        val game = SnakeGame(GameConfig(width = size, height = size, seed = gameSeed(botSeed)))
        if (!feedPrefix(game, moves, t)) return null
        val strategy = Strategies.create(strategyName, Random(policySeed))
        var idx = t
        var divergence = -1
        var altMove: Direction? = null
        while (game.status == GameStatus.RUNNING) {
            val move = strategy.nextMove(game)
            if (divergence < 0 && (idx >= moves.size || move != moves[idx])) {
                divergence = idx
                altMove = move
            }
            game.step(move)
            idx++
        }
        return VariantOutcome(game.status, divergence, altMove)
    }

    fun scoreAt(botSeed: Long, moves: List<Direction>, tick: Int): Int {
        val game = SnakeGame(GameConfig(width = size, height = size, seed = gameSeed(botSeed)))
        feedPrefix(game, moves, tick)
        return game.score
    }

    /** null = original game won; PairSpec = last open move found; "closed" = no win in budget. */
    fun searchGame(botSeed: Long): Pair<String, PairSpec?> {
        val recorded = record(botSeed)
        if (recorded.status == GameStatus.WON) return "won" to null
        val deathStep = recorded.moves.size
        var bestDivergence = -1
        var bestAlt: Direction? = null
        var salt = 0L

        fun probe(t: Int): Boolean {
            var improved = false
            repeat(variants) {
                salt++
                val outcome = runVariant(botSeed, recorded.moves, t, botSeed * 1_000_003L + salt * SALT)
                if (outcome != null && outcome.status == GameStatus.WON && outcome.divergence > bestDivergence) {
                    bestDivergence = outcome.divergence
                    bestAlt = outcome.altMove
                    improved = true
                }
            }
            return improved
        }

        var backOff = 1
        while (backOff <= maxBack) {
            val t = deathStep - backOff
            if (t < 1) break
            if (probe(t)) break
            backOff = backOff shl 1
        }
        if (bestDivergence < 0) return "closed" to null

        // push the divergence as late as possible: probe right after the current best
        var failures = 0
        var rounds = 0
        while (failures < 2 && rounds < 12) {
            val t = bestDivergence + 1
            if (t >= deathStep) break
            rounds++
            if (probe(t)) failures = 0 else failures++
        }

        val spec = PairSpec(
            botSeed = botSeed,
            divergence = bestDivergence,
            deathStep = deathStep,
            scoreAtD = scoreAt(botSeed, recorded.moves, bestDivergence),
            orig = recorded.moves[bestDivergence],
            alt = requireNotNull(bestAlt),
        )
        return "open" to spec
    }

    fun placeboMove(game: SnakeGame, a: Direction, b: Direction): Direction? {
        val head = game.head
        val tail = game.snake.last()
        return Direction.entries.firstOrNull { dir ->
            if (dir == a || dir == b) return@firstOrNull false
            val target = head + dir
            if (target.x !in 0 until size || target.y !in 0 until size) return@firstOrNull false
            !game.isOccupied(target) || (target == tail && target != game.food)
        }
    }

    /** One CRN replay of one arm: scripted spawn prefix, fresh dice + policy after. */
    fun runArm(spec: PairSpec, recorded: Recorded, prefixSpawns: Int, forced: Direction, replay: Int): Boolean {
        val diceSeed = gameSeed(spec.botSeed) + (replay + 1) * SALT
        val game = SnakeGame(
            GameConfig(width = size, height = size, seed = diceSeed),
            spawnScript = recorded.spawns.take(prefixSpawns),
        )
        check(feedPrefix(game, recorded.moves, spec.divergence)) { "prefix died during gate replay" }
        if (game.step(forced) == GameStatus.RUNNING) {
            val strategy = Strategies.create(strategyName, Random(spec.botSeed * 7_919L + replay))
            while (game.status == GameStatus.RUNNING) {
                game.step(strategy.nextMove(game))
            }
        }
        return game.status == GameStatus.WON
    }

    fun gatePair(spec: PairSpec): GateRow {
        val recorded = record(spec.botSeed)
        val probe = SnakeGame(GameConfig(width = size, height = size, seed = gameSeed(spec.botSeed)))
        check(feedPrefix(probe, recorded.moves, spec.divergence)) { "prefix died during gate probe" }
        val prefixSpawns = probe.spawnLog.size
        val placebo = placeboMove(probe, spec.orig, spec.alt)
        var winsA = 0
        var winsB = 0
        var winsC = 0
        for (replay in 0 until gateReplays) {
            if (runArm(spec, recorded, prefixSpawns, spec.orig, replay)) winsA++
            if (runArm(spec, recorded, prefixSpawns, spec.alt, replay)) winsB++
            if (placebo != null && runArm(spec, recorded, prefixSpawns, placebo, replay)) winsC++
        }
        return GateRow(spec, winsA, winsB, winsC, placebo != null)
    }

    fun signTestP(deltas: List<Double>): Double {
        val plus = deltas.count { it > 0 }
        val minus = deltas.count { it < 0 }
        val n = plus + minus
        if (n == 0) return 1.0
        // one-sided exact binomial: P(X >= plus | n, 0.5)
        var p = 0.0
        for (k in plus..n) {
            var logC = 0.0
            for (i in 1..k) logC += Math.log((n - k + i).toDouble() / i)
            p += Math.exp(logC - n * Math.log(2.0))
        }
        return p
    }

    val pairsFile = File(if (mode == "gate") input else out)

    var pairs: List<PairSpec> = emptyList()

    if (mode == "search" || mode == "both") {
        println("policyfairy search: strategy=$strategyName field=${size}x$size seeds=$seeds variants=$variants maxBack=$maxBack")
        val started = System.nanoTime()
        val results = runBlocking {
            (0 until seeds).map { i ->
                async(Dispatchers.Default.limitedParallelism(parallelism)) {
                    searchGame(seedFrom + i)
                }
            }.awaitAll()
        }
        val elapsed = (System.nanoTime() - started) / 1e9
        val won = results.count { it.first == "won" }
        val closed = results.count { it.first == "closed" }
        pairs = results.mapNotNull { it.second }
        val fills = pairs.map { it.scoreAtD * 100.0 / area }.sorted()
        val gaps = pairs.map { it.deathStep - it.divergence }.sorted()
        println(
            ("search: %d seeds, %d original wins, %d closed within budget, %d pairs  time %.1fs\n" +
                "fill@d p50=%.1f%% p10=%.1f%% p90=%.1f%%  steps-to-death p50=%d max=%d").format(
                Locale.ROOT, seeds, won, closed, pairs.size, elapsed,
                fills.getOrElse(fills.size / 2) { 0.0 },
                fills.getOrElse(fills.size / 10) { 0.0 },
                fills.getOrElse(fills.size * 9 / 10) { 0.0 },
                gaps.getOrElse(gaps.size / 2) { 0 },
                gaps.lastOrNull() ?: 0,
            )
        )
        pairsFile.parentFile?.mkdirs()
        pairsFile.writeText(
            pairs.joinToString("") { s ->
                "$strategyName ${s.botSeed} ${s.divergence} ${s.deathStep} ${s.scoreAtD} ${s.orig} ${s.alt}\n"
            }
        )
        println("wrote ${pairs.size} pairs to ${pairsFile.path}")
    }

    if (mode == "gate") {
        pairs = pairsFile.readLines().filter { it.isNotBlank() }.map { line ->
            val f = line.trim().split(" ")
            require(f[0] == strategyName) { "pairs file is for strategy ${f[0]}, run with -Pstrategy=${f[0]}" }
            PairSpec(f[1].toLong(), f[2].toInt(), f[3].toInt(), f[4].toInt(), Direction.valueOf(f[5]), Direction.valueOf(f[6]))
        }
        println("loaded ${pairs.size} pairs from ${pairsFile.path}")
    }

    if (mode == "gate" || mode == "both") {
        if (pairs.isEmpty()) {
            println("no pairs to gate")
            return
        }
        println("policyfairy gate: ${pairs.size} pairs x $gateReplays CRN replays x 3 arms")
        val started = System.nanoTime()
        val rows = runBlocking {
            pairs.map { spec ->
                async(Dispatchers.Default.limitedParallelism(parallelism)) { gatePair(spec) }
            }.awaitAll()
        }.sortedBy { it.spec.botSeed }
        val elapsed = (System.nanoTime() - started) / 1e9

        println("seed     d      D      fill%  a->b        P(a)   P(b)   P(c)   d(b-a)pp d(c-a)pp")
        for (row in rows) {
            val m = gateReplays.toDouble()
            println(
                "%-8d %-6d %-6d %-6.1f %-5s>%-5s %5.1f%% %5.1f%% %6s %+8.1f %8s".format(
                    Locale.ROOT,
                    row.spec.botSeed, row.spec.divergence, row.spec.deathStep, row.spec.scoreAtD * 100.0 / area,
                    row.spec.orig, row.spec.alt,
                    row.winsA * 100 / m, row.winsB * 100 / m,
                    if (row.hasPlacebo) "%5.1f%%".format(Locale.ROOT, row.winsC * 100 / m) else "-",
                    (row.winsB - row.winsA) * 100 / m,
                    if (row.hasPlacebo) "%+.1f".format(Locale.ROOT, (row.winsC - row.winsA) * 100 / m) else "-",
                )
            )
        }

        val m = gateReplays.toDouble()
        val deltasB = rows.map { (it.winsB - it.winsA) / m }
        val deltasC = rows.filter { it.hasPlacebo }.map { (it.winsC - it.winsA) / m }
        val meanB = deltasB.average()
        val sdB = sqrt(deltasB.sumOf { (it - meanB) * (it - meanB) } / (deltasB.size - 1).coerceAtLeast(1))
        val tB = if (sdB > 0) meanB / (sdB / sqrt(deltasB.size.toDouble())) else 0.0
        val meanC = if (deltasC.isNotEmpty()) deltasC.average() else Double.NaN
        val pooledA = rows.sumOf { it.winsA } / (rows.size * m)
        val pooledB = rows.sumOf { it.winsB } / (rows.size * m)

        println(
            ("\ngate: %d pairs, time %.1fs\n" +
                "pooled P(win): a=%.1f%% b=%.1f%%\n" +
                "mean d(b-a) = %+.2f pp, paired t = %.2f, sign test one-sided p = %.4f\n" +
                "placebo mean d(c-a) = %+.2f pp (%d pairs)").format(
                Locale.ROOT, rows.size, elapsed,
                pooledA * 100, pooledB * 100,
                meanB * 100, tB, signTestP(deltasB),
                meanC * 100, deltasC.size,
            )
        )
        val passed = meanB * 100 >= 3.0 && tB >= 1.70
        println("PRE-REGISTERED GATE (mean >= +3.0 pp AND t >= 1.70): " + if (passed) "PASSED — label is trainable" else "REFUTED — advantage does not survive the dice re-roll")
    }
}
