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
import org.grakovne.snake.agent.strategy.search.EndgameSolver
import java.io.File
import java.util.Locale
import kotlin.random.Random

/**
 * Empirical falsification test of the central honesty conjecture
 * "Solved => anchored coverage" (docs/RESEARCH.md, honesty formalization v1).
 *
 * Mine endgame states from honest games at every eat with g free cells in
 * [gMin, gMax]; classify each with the exact endgame solver (CERT = certified
 * win, value 1.0 on an exact search; LOSS = proven loss; UNCERT = budget bust
 * or intermediate value) and compute the exact anchored coverage: the maximum
 * number of free cells coverable by one simple path over free cells starting
 * next to the head (bitmask DP, exact for g <= ~20).
 *
 * Coverage comes in two strengths. Anchored (path starts next to the current
 * head) proved too strict on the pilot: a carousel snake may rotate before it
 * starts eating, so the true anchor is any rotation phase. The falsification
 * criterion therefore uses the UNANCHORED max simple path over free cells —
 * the weakest necessary form; the anchored number is reported for context.
 *
 * Solver target is score >= area-1 (one cell may be left), so the conjecture
 * predicts CERT => gapU <= 1 where gapU = g - unanchored coverage. A single
 * CERT state with gapU >= 2 falsifies the conjecture; LOSS states showing
 * gapU >= 2 in a substantial fraction would show coverage also separates.
 *
 * ./gradlew covermine -Psize=30 -Pgames=200
 */

private class MinedState(
    val seed: Long,
    val eatIndex: Int,
    val body: List<Position>,
    val food: Position,
    val finalScore: Int,
)

private class Row(
    val seed: Long,
    val g: Int,
    val cls: String,
    val value: Double,
    val coverU: Int,
    val coverA: Int,
    val resid: Int,
    val wCount: Int,
    val finalScore: Int,
    val body: List<Position>,
    val food: Position,
)

fun main() {
    val size = intProp("size", 30)
    val games = intProp("games", 200)
    val seedFrom = longProp("seedFrom", 800_000)
    val strategyName = prop("strategy", "sweep")
    val gMin = intProp("gMin", 4)
    val gMax = intProp("gMax", 14)
    val solverBudget = intProp("solverBudget", 150_000)
    val out = prop("out", "data/covermine-$size.txt")
    val parallelism = intProp("parallelism", Runtime.getRuntime().availableProcessors())

    val area = size * size
    println("covermine: strategy=$strategyName field=${size}x$size games=$games g=[$gMin..$gMax] budget=$solverBudget")

    /**
     * Exact max free cells coverable by one simple path over free cells, with
     * start restricted to [anchors] (bitset over free-cell ids) or unrestricted
     * when anchors == -1. Bitmask DP over (visited set, last); [reachable] keeps
     * every achievable (visited set, endpoints) pair for the composite check.
     */
    class CoverageDp(val best: Int, val reachable: IntArray)

    fun coverageDp(freeCells: List<Int>, adjacency: IntArray, anchors: Int): CoverageDp {
        val g = freeCells.size
        if (g == 0 || anchors == 0) return CoverageDp(0, IntArray(1))
        val reachable = IntArray(1 shl g)
        val seedMask = if (anchors == -1) (1 shl g) - 1 else anchors
        var i = seedMask
        while (i != 0) {
            val a = Integer.numberOfTrailingZeros(i)
            reachable[1 shl a] = reachable[1 shl a] or (1 shl a)
            i = i and (i - 1)
        }
        var best = 1
        for (mask in 1 until (1 shl g)) {
            var lasts = reachable[mask]
            if (lasts == 0) continue
            val count = Integer.bitCount(mask)
            if (count > best) best = count
            while (lasts != 0) {
                val last = Integer.numberOfTrailingZeros(lasts)
                lasts = lasts and (lasts - 1)
                var next = adjacency[last] and mask.inv()
                while (next != 0) {
                    val nb = Integer.numberOfTrailingZeros(next)
                    next = next and (next - 1)
                    val nm = mask or (1 shl nb)
                    reachable[nm] = reachable[nm] or (1 shl nb)
                }
            }
        }
        return CoverageDp(best, reachable)
    }

    /**
     * Lemma-2 window digestibility per free cell, frozen-loop approximation:
     * wall position p = ticks until the tail vacates the cell (L-1-indexFromHead),
     * window W(p) = [p, p+g) mod L; a hole is digestible iff some ordered wall
     * pair (i, j) has W(p_i) intersecting W(p_j - 2). Returns a bitset over
     * free-cell ids.
     */
    fun digestibleMask(freeCells: List<Int>, body: List<Position>): Int {
        val bodyLength = body.size
        val g = freeCells.size
        val posOf = HashMap<Int, Int>(bodyLength * 2)
        body.forEachIndexed { idx, p -> posOf[p.y * size + p.x] = bodyLength - 1 - idx }
        var mask = 0
        freeCells.forEachIndexed { i, c ->
            val x = c % size
            val y = c / size
            val walls = ArrayList<Int>(4)
            if (x > 0) posOf[c - 1]?.let { walls.add(it) }
            if (x < size - 1) posOf[c + 1]?.let { walls.add(it) }
            if (y > 0) posOf[c - size]?.let { walls.add(it) }
            if (y < size - 1) posOf[c + size]?.let { walls.add(it) }
            var ok = false
            outer@ for (a in walls) {
                for (b in walls) {
                    if (a == b) continue
                    val s1 = a
                    val s2 = ((b - 2) % bodyLength + bodyLength) % bodyLength
                    val d1 = ((s1 - s2) % bodyLength + bodyLength) % bodyLength
                    val d2 = ((s2 - s1) % bodyLength + bodyLength) % bodyLength
                    if (d1 < g || d2 < g) {
                        ok = true
                        break@outer
                    }
                }
            }
            if (ok) mask = mask or (1 shl i)
        }
        return mask
    }

    fun freeGraph(occupied: BooleanArray): Pair<List<Int>, IntArray> {
        val freeCells = ArrayList<Int>(gMax + 1)
        for (c in occupied.indices) if (!occupied[c]) freeCells.add(c)
        val idOf = HashMap<Int, Int>(freeCells.size * 2)
        freeCells.forEachIndexed { i, c -> idOf[c] = i }
        val adjacency = IntArray(freeCells.size)
        for (i in freeCells.indices) {
            val c = freeCells[i]
            val x = c % size
            val y = c / size
            var mask = 0
            if (x > 0) idOf[c - 1]?.let { mask = mask or (1 shl it) }
            if (x < size - 1) idOf[c + 1]?.let { mask = mask or (1 shl it) }
            if (y > 0) idOf[c - size]?.let { mask = mask or (1 shl it) }
            if (y < size - 1) idOf[c + size]?.let { mask = mask or (1 shl it) }
            adjacency[i] = mask
        }
        return freeCells to adjacency
    }

    fun headAnchors(freeCells: List<Int>, head: Position): Int {
        var anchors = 0
        freeCells.forEachIndexed { i, c ->
            val p = Position(c % size, c / size)
            if (p.manhattanTo(head) == 1) anchors = anchors or (1 shl i)
        }
        return anchors
    }

    fun mineGame(seed: Long): List<MinedState> {
        val game = SnakeGame(GameConfig(width = size, height = size, seed = seed * 31))
        val strategy = Strategies.create(strategyName, Random(seed))
        val snapshots = ArrayList<MinedState>(gMax - gMin + 1)
        var eatIndex = 0
        while (game.status == GameStatus.RUNNING) {
            val before = game.score
            game.step(strategy.nextMove(game))
            if (game.score > before) {
                eatIndex++
                val free = area - game.score
                if (free in gMin..gMax && game.status == GameStatus.RUNNING) {
                    snapshots.add(MinedState(seed, eatIndex, game.snake.toList(), game.food, 0))
                }
            }
        }
        val finalScore = game.score
        return snapshots.map { MinedState(it.seed, it.eatIndex, it.body, it.food, finalScore) }
    }

    fun classify(state: MinedState, solver: EndgameSolver): Row {
        val plan = solver.solve(state.body, state.food)
        val cls = when {
            plan != null && plan.value >= 1.0 - 1e-9 && solver.lastExact -> "CERT"
            plan == null && solver.lastExact -> "LOSS"
            else -> "UNCERT"
        }
        val occupied = BooleanArray(area)
        for (p in state.body) occupied[p.y * size + p.x] = true
        val (freeCells, adjacency) = freeGraph(occupied)
        val unanchored = coverageDp(freeCells, adjacency, anchors = -1)
        val anchored = coverageDp(freeCells, adjacency, headAnchors(freeCells, state.body.first()))
        val digestible = digestibleMask(freeCells, state.body)
        // composite residual: min over achievable paths (incl. the empty one) of
        // cells that are neither on the path nor window-digestible
        val full = (1 shl freeCells.size) - 1
        var resid = Integer.bitCount(full and digestible.inv())
        for (mask in 1..full) {
            if (unanchored.reachable[mask] == 0) continue
            val r = Integer.bitCount(full and mask.inv() and digestible.inv())
            if (r < resid) resid = r
        }
        return Row(
            state.seed, area - state.body.size, cls, plan?.value ?: 0.0,
            unanchored.best, anchored.best, resid, Integer.bitCount(digestible),
            state.finalScore, state.body, state.food,
        )
    }

    fun render(row: Row): String {
        val grid = Array(size) { CharArray(size) { '.' } }
        for (p in row.body) grid[p.y][p.x] = '#'
        grid[row.food.y][row.food.x] = 'F'
        val head = row.body.first()
        grid[head.y][head.x] = 'H'
        val tail = row.body.last()
        grid[tail.y][tail.x] = 'T'
        return grid.joinToString("\n") { String(it) }
    }

    val started = System.nanoTime()
    val rows = runBlocking {
        (0 until games).map { i ->
            async(Dispatchers.Default.limitedParallelism(parallelism)) {
                val states = mineGame(seedFrom + i)
                if (states.isEmpty()) return@async emptyList<Row>()
                val solver = EndgameSolver(
                    size, size,
                    starvationLimit = size * size * 2,
                    nodeBudget = solverBudget,
                )
                states.map { classify(it, solver) }
            }
        }.awaitAll()
    }.flatten()
    val elapsed = (System.nanoTime() - started) / 1e9

    File(out).parentFile?.mkdirs()
    File(out).writeText(
        rows.joinToString("") { r ->
            "${r.seed} ${r.g} ${r.cls} %.4f ${r.coverU} ${r.coverA} ${r.resid} ${r.wCount} ${r.finalScore}\n"
                .format(Locale.ROOT, r.value)
        }
    )

    println("mined ${rows.size} states from $games games, time %.1fs, wrote $out".format(Locale.ROOT, elapsed))
    println(EndgameSolver.statsLine())
    println()
    println("class   n      gapU<=1 resid=0 resid<=1 resid>=2 meanResid maxResid meanW")
    for (cls in listOf("CERT", "LOSS", "UNCERT")) {
        val sub = rows.filter { it.cls == cls }
        if (sub.isEmpty()) {
            println("%-7s 0".format(cls))
            continue
        }
        val gaps = sub.map { it.g - it.coverU }
        val resids = sub.map { it.resid }
        println(
            "%-7s %-6d %5.1f%%  %5.1f%%  %5.1f%%   %5.1f%%   %6.2f    %-8d %.2f".format(
                Locale.ROOT, cls, sub.size,
                gaps.count { it <= 1 } * 100.0 / sub.size,
                resids.count { it == 0 } * 100.0 / sub.size,
                resids.count { it <= 1 } * 100.0 / sub.size,
                resids.count { it >= 2 } * 100.0 / sub.size,
                resids.average(),
                resids.max(),
                sub.map { it.wCount.toDouble() }.average(),
            )
        )
    }
    val v1Violations = rows.filter { it.cls == "CERT" && it.g - it.coverU >= 2 }
    val v1Explained = v1Violations.count { it.resid <= 1 }
    val v2Violations = rows.filter { it.cls == "CERT" && it.resid >= 2 }
    println()
    println("v1 violators (CERT, gapU >= 2): ${v1Violations.size}, of them explained by windows (resid <= 1): $v1Explained")
    if (v2Violations.isEmpty()) {
        println("FALSIFICATION TEST v2: 0 certified states with resid >= 2 — composite conjecture 'Solved => path + windows' SURVIVES")
    } else {
        println("FALSIFICATION TEST v2: ${v2Violations.size} certified states with resid >= 2 — composite conjecture FALSIFIED")
        for (v in v2Violations.take(5)) {
            println(
                "seed=${v.seed} g=${v.g} coverU=${v.coverU} resid=${v.resid} W=${v.wCount} value=%.4f"
                    .format(Locale.ROOT, v.value)
            )
            println(render(v))
        }
    }
}
