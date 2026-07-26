package org.grakovne.snake.agent.strategy.search

import org.grakovne.snake.agent.core.GameView
import org.grakovne.snake.agent.core.Position

/**
 * Reusable grid-search scratchpad for one strategy instance (single game, single thread).
 * Cells are int indices (y * width + x).
 *
 * The core representation is the vacate map: 0 = free cell, otherwise the number of steps
 * after which the body cell becomes free assuming no food is eaten meanwhile (tail = 1,
 * head = body length). Time-aware searches treat a body cell as passable at arrival time t
 * when vacate + margin <= t.
 */
class BoardSearch(val width: Int, val height: Int) {

    private val size = width * height
    private val vacate = IntArray(size)
    private val scratchVacate = IntArray(size)
    private val dist = IntArray(size)
    private val prev = IntArray(size)
    private val queue = IntArray(size)
    private val onPath = BooleanArray(size)

    private var headIdx = 0
    private var tailIdx = 0
    private var foodIdx = 0
    private var bodyLength = 0

    fun index(position: Position) = position.y * width + position.x

    fun load(game: GameView) {
        java.util.Arrays.fill(vacate, 0)
        val body = game.snake
        bodyLength = body.size
        for (i in body.indices) {
            vacate[index(body[i])] = bodyLength - i
        }
        headIdx = index(body.first())
        tailIdx = index(body.last())
        foodIdx = index(game.food)
    }

    /**
     * BFS shortest path from the head to [target]. Returns the full path including both
     * endpoints, or null. [timeAware] lets the path enter cells that will have vacated by
     * arrival time (with [margin] extra steps of safety). [blockFood] treats the food cell
     * as a wall (used while stalling, when eating is undesirable).
     */
    fun shortestPathFromHead(
        target: Int,
        timeAware: Boolean,
        margin: Int = 1,
        blockFood: Boolean = false,
        targetWalkable: Boolean = false,
        hugging: Boolean = false,
    ): IntArray? {
        val path = bfs(vacate, headIdx, target, timeAware, margin, blockFood, targetWalkable)
            ?: return null
        if (!hugging) return path
        return reconstructHugging(headIdx, target)
    }

    /**
     * Rebuilds a shortest path (over the dist field left by the last BFS) walking back from
     * the target and preferring predecessors that hug walls and body. Same length, different
     * shape: hugging trajectories do not leave single-cell holes behind the way corner-cutting
     * ones do.
     */
    private fun reconstructHugging(from: Int, target: Int): IntArray {
        val length = dist[target] + 1
        val path = IntArray(length)
        path[length - 1] = target
        var cursor = target
        for (i in length - 2 downTo 0) {
            var best = -1
            var bestScore = -1
            for (direction in 0 until 4) {
                val next = neighbor(cursor, direction)
                if (next == -1 || dist[next] != dist[cursor] - 1) continue
                val score = hugScore(next)
                if (score > bestScore) {
                    bestScore = score
                    best = next
                }
            }
            path[i] = best
            cursor = best
        }
        return path
    }

    private fun hugScore(cell: Int): Int {
        var score = 0
        for (direction in 0 until 4) {
            val next = neighbor(cell, direction)
            if (next == -1 || vacate[next] != 0) score++
        }
        return score
    }

    /** True when the head can reach the snake's own tail on the current board. */
    fun tailReachable(timeAware: Boolean, margin: Int = 1): Boolean =
        bfs(vacate, headIdx, tailIdx, timeAware, margin, blockFood = false, targetWalkable = true) != null

    /**
     * The body after walking [path] (head-first walk from the current head) and eating at
     * its last cell: the walk followed by the old body, truncated to length + 1.
     */
    fun bodyAfterEating(body: List<Position>, path: IntArray): IntArray {
        val newLength = body.size + 1
        val result = IntArray(newLength)
        var written = 0
        for (i in path.indices.reversed()) {
            if (written == newLength) break
            result[written++] = path[i]
        }
        var bodyIndex = 1
        while (written < newLength) {
            result[written++] = index(body[bodyIndex++])
        }
        return result
    }

    /**
     * Free cells with no free neighbor for the given body (head-first indices) — future
     * unreachable holes unless consumed. The food cell counts as free.
     */
    fun deadFreeCellsFor(body: IntArray): Int {
        java.util.Arrays.fill(scratchVacate, 0)
        for (i in body.indices) {
            scratchVacate[body[i]] = body.size - i
        }
        var dead = 0
        for (cell in 0 until size) {
            if (scratchVacate[cell] != 0) continue
            var freeNeighbors = 0
            for (direction in 0 until 4) {
                val next = neighbor(cell, direction)
                if (next != -1 && scratchVacate[next] == 0) freeNeighbors++
            }
            if (freeNeighbors == 0) dead++
        }
        return dead
    }

    /** Same count for the currently loaded board. */
    fun deadFreeCells(): Int {
        var dead = 0
        for (cell in 0 until size) {
            if (vacate[cell] != 0) continue
            var freeNeighbors = 0
            for (direction in 0 until 4) {
                val next = neighbor(cell, direction)
                if (next != -1 && vacate[next] == 0) freeNeighbors++
            }
            if (freeNeighbors == 0) dead++
        }
        return dead
    }

    /**
     * True when the head moving to [cell] (a non-eating move: tail vacates) leaves some
     * free cell adjacent to [cell] with no free neighbors. Only neighbors of [cell] can
     * newly die, so the check is local and cheap.
     */
    fun moveCreatesDeadCell(cell: Int): Boolean {
        fun freeAfter(x: Int): Boolean = x != cell && (vacate[x] == 0 || x == tailIdx)
        for (direction in 0 until 4) {
            val f = neighbor(cell, direction)
            if (f == -1 || !freeAfter(f)) continue
            var alive = false
            for (d2 in 0 until 4) {
                val g = neighbor(f, d2)
                if (g != -1 && freeAfter(g)) {
                    alive = true
                    break
                }
            }
            if (!alive) return true
        }
        return false
    }

    /** The body (same length) after walking [path] from the head of [body] without eating. */
    fun bodyAfterWalk(body: IntArray, path: IntArray): IntArray {
        val result = IntArray(body.size)
        var written = 0
        for (i in path.indices.reversed()) {
            if (written == body.size) break
            result[written++] = path[i]
        }
        var bodyIndex = 1
        while (written < body.size) {
            result[written++] = body[bodyIndex++]
        }
        return result
    }

    /** True when the head of [body] (head-first cell indices) can reach its own tail. */
    fun tailReachableFor(body: IntArray, timeAware: Boolean, margin: Int = 1): Boolean =
        escapePlanFor(body, timeAware, margin) != null

    /**
     * A concrete walk from the head of [body] to its tail. With [timeAware] the walk may
     * enter cells at exactly the tick they vacate — in a deterministic engine such a plan
     * is sound as long as it is followed verbatim and nothing is eaten en route: the walk
     * never revisits its own new body (BFS paths are simple) and old-body timings are exact.
     */
    fun escapePlanFor(body: IntArray, timeAware: Boolean, margin: Int = 0): IntArray? {
        java.util.Arrays.fill(scratchVacate, 0)
        for (i in body.indices) {
            scratchVacate[body[i]] = body.size - i
        }
        return bfs(
            scratchVacate, body.first(), body.last(),
            timeAware, margin, blockFood = false, targetWalkable = true,
        )
    }

    /**
     * Approximate longest path from the head to its tail over currently-free cells:
     * shortest path extended by pulling 2-cell detours into it until no extension fits.
     * Classic stalling move source. Food is treated as a wall.
     */
    /**
     * [directionBias] rotates the extension order. Varying it between stall laps reshapes
     * the trajectory each circulation, which shifts the body's vacate schedule and lets
     * timed windows onto stranded holes eventually align — a fixed order stalls in an
     * identical loop forever.
     */
    fun longestPathToTail(directionBias: Int = 0): IntArray? {
        val base = bfs(
            vacate, headIdx, tailIdx,
            timeAware = false, margin = 0, blockFood = true, targetWalkable = true,
        ) ?: return null

        val path = ArrayList<Int>(base.size)
        base.forEach { path.add(it) }
        java.util.Arrays.fill(onPath, false)
        path.forEach { onPath[it] = true }

        fun extendable(cell: Int): Boolean =
            cell != -1 && !onPath[cell] && vacate[cell] == 0 && cell != foodIdx

        var i = 0
        while (i < path.size - 1) {
            val a = path[i]
            val b = path[i + 1]
            var extended = false
            for (rotation in 0 until 4) {
                val direction = (rotation + directionBias) and 3
                val c = neighbor(a, direction)
                val d = neighbor(b, direction)
                if (extendable(c) && extendable(d)) {
                    path.add(i + 1, d)
                    path.add(i + 1, c)
                    onPath[c] = true
                    onPath[d] = true
                    extended = true
                    break
                }
            }
            if (!extended) i++
        }
        return path.toIntArray()
    }

    /** Number of statically-free cells reachable from [from] (the tail cell counts as free). */
    fun floodSizeFrom(from: Int): Int {
        if (vacate[from] != 0 && from != tailIdx) return 0
        java.util.Arrays.fill(dist, -1)
        dist[from] = 0
        queue[0] = from
        var head = 0
        var tail = 1
        var area = 0
        while (head < tail) {
            val current = queue[head++]
            area++
            for (direction in 0 until 4) {
                val next = neighbor(current, direction)
                if (next == -1 || dist[next] != -1) continue
                if (vacate[next] != 0 && next != tailIdx) continue
                dist[next] = 0
                queue[tail++] = next
            }
        }
        return area
    }

    fun neighborTowards(from: Int, to: Int): Int = to - from

    private fun bfs(
        occupancy: IntArray,
        from: Int,
        target: Int,
        timeAware: Boolean,
        margin: Int,
        blockFood: Boolean,
        targetWalkable: Boolean,
    ): IntArray? {
        java.util.Arrays.fill(dist, -1)
        dist[from] = 0
        prev[from] = from
        queue[0] = from
        var head = 0
        var tail = 1
        while (head < tail) {
            val current = queue[head++]
            if (current == target) return reconstruct(from, target)
            val arrival = dist[current] + 1
            for (direction in 0 until 4) {
                val next = neighbor(current, direction)
                if (next == -1 || dist[next] != -1) continue
                if (blockFood && next == foodIdx) continue
                val passable = when {
                    next == target && targetWalkable -> true
                    occupancy[next] == 0 -> true
                    timeAware -> occupancy[next] + margin <= arrival
                    else -> false
                }
                if (!passable) continue
                dist[next] = arrival
                prev[next] = current
                queue[tail++] = next
            }
        }
        return null
    }

    private fun reconstruct(from: Int, target: Int): IntArray {
        var length = 1
        var cursor = target
        while (cursor != from) {
            cursor = prev[cursor]
            length++
        }
        val path = IntArray(length)
        cursor = target
        for (i in length - 1 downTo 0) {
            path[i] = cursor
            cursor = prev[cursor]
        }
        return path
    }

    private fun neighbor(cell: Int, direction: Int): Int = when (direction) {
        0 -> if (cell >= width) cell - width else -1
        1 -> if (cell < size - width) cell + width else -1
        2 -> if (cell % width != 0) cell - 1 else -1
        else -> if (cell % width != width - 1) cell + 1 else -1
    }

    fun foodIndex() = foodIdx
    fun headIndex() = headIdx
    fun tailIndex() = tailIdx
}
