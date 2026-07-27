package org.grakovne.snake.agent.strategy.search

import org.grakovne.snake.agent.core.Direction
import org.grakovne.snake.agent.core.GameConfig
import org.grakovne.snake.agent.core.GameStatus
import org.grakovne.snake.agent.core.Position
import org.grakovne.snake.agent.core.SnakeGame

/**
 * Exact endgame search. With few free cells the game is small enough to solve
 * instead of evaluate: expectimax over future food spawns (chance nodes, uniform
 * over free cells) and over a finite portfolio of eating walks (max nodes),
 * recursed to terminal positions within a node budget.
 *
 * Node value is the probability of finishing at score >= area-1 — the benchmark
 * target; any position with body length >= area-1 counts as 1 outright. Walks
 * come from the same vacate-schedule generators the bot plays with, and post-eat
 * bodies are computed directly (bodyAfterEating) instead of stepping the engine —
 * three orders of magnitude cheaper per node. The chosen root walk is verified by
 * an exact engine replay before being handed to the strategy.
 */
class EndgameSolver(
    private val width: Int,
    private val height: Int,
    private val starvationLimit: Int,
    private val nodeBudget: Int = 150_000,
) {


    private val area = width * height
    private val board = BoardSearch(width, height)
    private val memo = HashMap<Long, Double>()

    private val occupied = BooleanArray(area)
    private var budget = 0

    /** True when the last solve explored every line it valued (no budget cut). */
    var lastExact = true
        private set

    private class Budget : RuntimeException()

    companion object {
        const val MAX_STALLS = 2
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        val commits = java.util.concurrent.atomic.AtomicInteger()
        val provenLoss = java.util.concurrent.atomic.AtomicInteger()
        val budgetBusts = java.util.concurrent.atomic.AtomicInteger()
        val replayRejects = java.util.concurrent.atomic.AtomicInteger()
        val certified = java.util.concurrent.atomic.AtomicInteger()
        fun statsLine(): String =
            "solver: attempts=${attempts.get()} commits=${commits.get()} provenLoss=${provenLoss.get()} " +
                "budgetBusts=${budgetBusts.get()} replayRejects=${replayRejects.get()} certified=${certified.get()}"
    }

    /** A certified plan: an eating walk or a reshaping stall lap to play first. */
    data class Plan(val walk: IntArray, val value: Double, val isStall: Boolean)

    /**
     * Best plan from the state with its win probability. Null when every line
     * provably loses (with [lastExact] true) or nothing validated.
     */
    fun solve(body: List<Position>, food: Position): Plan? {
        attempts.incrementAndGet()
        memo.clear()
        budget = nodeBudget
        lastExact = true
        val cells = bodyIndices(body)
        val foodIdx = board.index(food)
        var best: Plan? = null
        for ((walk, post) in expand(cells, foodIdx)) {
            val value = try {
                postValue(post, MAX_STALLS)
            } catch (_: Budget) {
                lastExact = false
                budget = nodeBudget / 4
                continue
            }
            if (value > (best?.value ?: -1.0)) best = Plan(walk, value, isStall = false)
        }
        for ((walk, post) in stallMoves(cells, foodIdx)) {
            val value = try {
                value(post, foodIdx, MAX_STALLS - 1)
            } catch (_: Budget) {
                lastExact = false
                budget = nodeBudget / 4
                continue
            }
            if (value > (best?.value ?: -1.0) + 1e-9) best = Plan(walk, value, isStall = true)
        }
        if (!lastExact) budgetBusts.incrementAndGet()
        val plan = best ?: return null.also { if (lastExact) provenLoss.incrementAndGet() }
        if (plan.value <= 0.0 && lastExact) {
            provenLoss.incrementAndGet()
            return null
        }
        if (!plan.isStall && !replayValid(body, food, plan.walk)) {
            replayRejects.incrementAndGet()
            return null
        }
        commits.incrementAndGet()
        return plan
    }

    /** Value of a post-eat body: terminal check, then a chance node over spawns. */
    private fun postValue(post: IntArray, stallsLeft: Int): Double {
        if (post.size >= area - 1) return 1.0
        java.util.Arrays.fill(occupied, false)
        for (c in post) occupied[c] = true
        var total = 0.0
        var free = 0
        // copy: `occupied` is scratch shared across the recursion
        val cells = ArrayList<Int>(area - post.size)
        for (c in 0 until area) if (!occupied[c]) cells.add(c)
        for (c in cells) {
            free++
            total += value(post, c, stallsLeft)
        }
        return if (free == 0) 1.0 else total / free
    }

    /** Max node: best eat-or-stall value for (body, food), memoized. */
    private fun value(bodyCells: IntArray, foodIdx: Int, stallsLeft: Int): Double {
        if (--budget < 0) throw Budget()
        val key = hash(bodyCells, foodIdx) * 31 + stallsLeft
        memo[key]?.let { return it }
        var best = 0.0
        for ((_, post) in expand(bodyCells, foodIdx)) {
            val v = postValue(post, MAX_STALLS)
            if (v > best) {
                best = v
                if (best >= 1.0 - 1e-9) break
            }
        }
        if (best < 1.0 - 1e-9 && stallsLeft > 0) {
            for ((_, post) in stallMoves(bodyCells, foodIdx)) {
                val v = value(post, foodIdx, stallsLeft - 1)
                if (v > best) {
                    best = v
                    if (best >= 1.0 - 1e-9) break
                }
            }
        }
        memo[key] = best
        return best
    }

    /** Reshaping actions: full stall laps in each of the four bias shapes. */
    private fun stallMoves(bodyCells: IntArray, foodIdx: Int): List<Pair<IntArray, IntArray>> {
        loadState(bodyCells, foodIdx)
        val out = ArrayList<Pair<IntArray, IntArray>>(4)
        val seen = HashSet<Int>(8)
        for (bias in 0 until 4) {
            val p = board.longestPathToTail(directionBias = bias) ?: continue
            if (p.size < 2) continue
            var hitsFood = false
            for (c in p) if (c == foodIdx) hitsFood = true
            if (hitsFood) continue   // an unplanned bite would break the committed lap
            if (!seen.add(p.contentHashCode())) continue
            out.add(p to board.bodyAfterWalk(bodyCells, p))
        }
        return out
    }

    private fun loadState(bodyCells: IntArray, foodIdx: Int) {
        val body = ArrayList<Position>(bodyCells.size)
        for (c in bodyCells) body.add(Position(c % width, c / width))
        val sim = SnakeGame(
            GameConfig(width = width, height = height, seed = 1, maxStepsWithoutFood = starvationLimit),
            initialBody = body,
            initialFood = Position(foodIdx % width, foodIdx / width),
        )
        board.load(sim)
    }

    /** Portfolio of eating walks and their post-eat bodies for a state. */
    private fun expand(bodyCells: IntArray, foodIdx: Int): List<Pair<IntArray, IntArray>> {
        loadState(bodyCells, foodIdx)
        val body = ArrayList<Position>(bodyCells.size)
        for (c in bodyCells) body.add(Position(c % width, c / width))
        val paths = ArrayList<IntArray>(7)
        board.shortestPathFromHead(foodIdx, timeAware = false, margin = 0, hugging = true)?.let { paths.add(it) }
        board.shortestPathFromHead(foodIdx, timeAware = false, margin = 0)?.let { paths.add(it) }
        board.shortestPathFromHead(foodIdx, timeAware = true, margin = 0)?.let { paths.add(it) }
        board.foodPathSnapshots(rng = null, limit = 4) { paths.add(it) }
        val seen = HashSet<Int>(paths.size * 2)
        val out = ArrayList<Pair<IntArray, IntArray>>(paths.size)
        for (p in paths) {
            if (p.size < 2 || p.size - 1 > starvationLimit) continue
            if (!seen.add(p.contentHashCode())) continue
            out.add(p to board.bodyAfterEating(body, p))
        }
        return out
    }

    /** Exact engine replay of the root walk: the one place engine truth is enforced. */
    private fun replayValid(body: List<Position>, food: Position, walk: IntArray): Boolean {
        val sim = SnakeGame(
            GameConfig(width = width, height = height, seed = 1, maxStepsWithoutFood = starvationLimit),
            initialBody = body,
            initialFood = food,
        )
        val startScore = sim.score
        for (i in 1 until walk.size) {
            if (sim.status != GameStatus.RUNNING) return false
            sim.step(directionOf(walk[i - 1], walk[i]) ?: return false)
        }
        return sim.score > startScore && sim.status != GameStatus.DEAD
    }

    private fun directionOf(from: Int, to: Int): Direction? = when (to - from) {
        -width -> Direction.UP
        width -> Direction.DOWN
        -1 -> Direction.LEFT
        1 -> Direction.RIGHT
        else -> null
    }

    private fun bodyIndices(body: List<Position>): IntArray =
        IntArray(body.size) { i -> body[i].y * width + body[i].x }

    private fun hash(cells: IntArray, foodIdx: Int): Long {
        var h = -0x61c8864680b583ebL
        for (c in cells) {
            h = (h xor c.toLong()) * 0x100000001b3L
        }
        return (h xor foodIdx.toLong()) * 0x100000001b3L
    }
}
