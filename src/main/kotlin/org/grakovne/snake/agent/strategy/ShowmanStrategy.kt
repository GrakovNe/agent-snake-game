package org.grakovne.snake.agent.strategy

import org.grakovne.snake.agent.core.Direction
import org.grakovne.snake.agent.core.GameView
import kotlin.random.Random

/**
 * The showman: a guaranteed full board that tries hard not to look like one.
 *
 * SHOW-ONLY, excluded from all research comparisons: the snake rides a
 * Hamiltonian cycle, which makes a full board a theorem, not an achievement.
 *
 * The disguise has two parts. The cycle starts random (spanning-tree
 * construction, different every game). Then, every tick, the bot asks what an
 * honest greedy bot would do (BFS toward food) and tries to REWIRE the cycle so
 * that the cycle's own next step is exactly that greedy step. A rewire is an
 * undirected two-edge swap {head,fwd}+{n,m} -> {head,n}+{fwd,m}; a swap may
 * split the cycle in two, so it is validated by walking the loop (must cover
 * the whole board) and reverted otherwise. While the body is short the rewires
 * almost always validate and the snake beelines to food like a plain greedy
 * bot; as the board fills they start failing and the ride degrades seamlessly
 * into pure cycle-following. The guarantee never blinks: the body stays a
 * contiguous arc of a full-board cycle at every tick.
 *
 * Starvation stays impossible: if half a lap passes without food (pathological
 * rewire luck), rewiring freezes and the frozen cycle reaches every cell
 * within one lap — 1.5 laps worst case against a two-lap budget.
 */
class ShowmanStrategy(private val random: Random) : Strategy {

    companion object {
        val steerTried = java.util.concurrent.atomic.AtomicLong()
        val steerOk = java.util.concurrent.atomic.AtomicLong()
        val steerNoChord = java.util.concurrent.atomic.AtomicLong()
        val steerBlocked = java.util.concurrent.atomic.AtomicLong()
        val noDesired = java.util.concurrent.atomic.AtomicLong()
    }

    private var width = 0
    private var height = 0

    // undirected cycle: each cell has exactly two neighbors
    private var na = IntArray(0)
    private var nb = IntArray(0)

    private val occupied = java.util.BitSet()
    private var dist = IntArray(0)
    private var queue = IntArray(0)
    private var walkMark = IntArray(0)
    private var walkStamp = 0
    private var cameFrom = -1

    override fun nextMove(game: GameView): Direction {
        if (na.size != game.width * game.height) {
            buildCycle(game.width, game.height)
            cameFrom = -1
        }

        occupied.clear()
        for (p in game.snake) occupied.set(p.y * width + p.x)

        val area = width * height
        val head = game.snake.first().let { it.y * width + it.x }
        val bodySide = if (game.snake.size > 1) {
            game.snake[1].let { it.y * width + it.x }
        } else {
            -1
        }

        var forward = forwardOf(head, bodySide)

        // Rewiring freeze half a lap before the starvation limit could matter.
        if (game.stepsSinceFood < area / 2 && forward != -1) {
            val desired = greedyStep(game, head)
            if (desired == -1) {
                noDesired.incrementAndGet()
            } else if (desired != forward && !occupied.get(desired)) {
                steerTried.incrementAndGet()
                if (steer(head, forward, desired)) {
                    steerOk.incrementAndGet()
                    forward = desired
                }
            }
            if (random.nextInt(4) == 0) mutate()
            forward = forwardOf(head, bodySide).takeIf { it != -1 } ?: forward
        }

        if (forward != -1 && (!occupied.get(forward) || isVacatingTail(game, forward))) {
            cameFrom = head
            return direction(head, forward)
        }
        // Off-cycle start or misalignment: any safe step until the body settles.
        for (dir in Direction.entries) {
            if (game.isSafeStep(dir)) {
                cameFrom = head
                return dir
            }
        }
        return Direction.UP
    }

    /** The head's cycle neighbor on the free side (not the body, not where we came from). */
    private fun forwardOf(head: Int, bodySide: Int): Int {
        val a = na[head]
        val b = nb[head]
        return when {
            a != bodySide && a != cameFrom && !occupied.get(a) -> a
            b != bodySide && b != cameFrom && !occupied.get(b) -> b
            a != bodySide -> a
            b != bodySide -> b
            else -> -1
        }
    }

    /**
     * Try to make [n] the head's forward cycle neighbor via the swap
     * {head,fwd}+{n,m} -> {head,n}+{fwd,m} for one of n's cycle neighbors m.
     * The swap must keep a single full-board cycle (validated by walking) and
     * never touches the body-side edge, so the body arc survives intact.
     */
    private fun steer(head: Int, fwd: Int, n: Int): Boolean {
        for (m in intArrayOf(na[n], nb[n])) {
            if (m == head || m == n || m == fwd) continue
            if (!adjacent(m, fwd)) continue
            swap(head, fwd, n, m)
            if (singleCycleFrom(head)) return true
            // The swap split the cycle in two. Merging two distinct cycles with
            // one swap always yields a single cycle — find any splice point
            // between the orphan loop and the main loop and stitch them.
            if (repairSplit(head)) return true
            swap(head, n, fwd, m)   // revert
            steerBlocked.incrementAndGet()
            return false
        }
        steerNoChord.incrementAndGet()
        return false
    }

    /**
     * After a splitting swap: the main loop (through [inMain]) is marked with the
     * current [walkStamp]. Finds an orphan-loop edge {a,b} and a main-loop edge
     * {c,d} with a~c, b~d, all four cells free, and swaps them — a merge of two
     * disjoint cycles, single by construction. False when no splice point exists.
     */
    private fun repairSplit(inMain: Int): Boolean {
        val area = width * height
        var orphanSeed = -1
        for (cell in 0 until area) {
            if (walkMark[cell] != walkStamp) {
                orphanSeed = cell
                break
            }
        }
        if (orphanSeed == -1) return false
        // walk the orphan loop
        var prev = orphanSeed
        var a = orphanSeed
        do {
            val b = na[a]   // one representative edge per cell suffices: both
            // orientations of {a,b} are covered when the loop visits b
            if (!occupied.get(a) && !occupied.get(b)) {
                val ax = a % width
                val ay = a / width
                for (dir in Direction.entries) {
                    val cx = ax + dir.dx
                    val cy = ay + dir.dy
                    if (cx < 0 || cy < 0 || cx >= width || cy >= height) continue
                    val c = cy * width + cx
                    if (walkMark[c] != walkStamp || occupied.get(c)) continue
                    for (d in intArrayOf(na[c], nb[c])) {
                        if (occupied.get(d) || walkMark[d] != walkStamp) continue
                        if (!adjacent(d, b)) continue
                        swap(a, b, c, d)
                        return true
                    }
                }
            }
            val nxt = if (na[a] != prev) na[a] else nb[a]
            prev = a
            a = nxt
        } while (a != orphanSeed)
        return false
    }

    /** One random liveliness mutation among free cells, validated and reverted on split. */
    private fun mutate() {
        val area = width * height
        val a = random.nextInt(area)
        if (occupied.get(a)) return
        val b = if (random.nextBoolean()) na[a] else nb[a]
        if (occupied.get(b)) return
        val ax = a % width
        val ay = a / width
        val dir = Direction.entries[random.nextInt(4)]
        val cx = ax + dir.dx
        val cy = ay + dir.dy
        if (cx < 0 || cy < 0 || cx >= width || cy >= height) return
        val c = cy * width + cx
        if (c == b || occupied.get(c) || isEdge(a, c)) return
        for (m in intArrayOf(na[c], nb[c])) {
            if (m == a || m == b || m == c || occupied.get(m)) continue
            if (!adjacent(m, b)) continue
            swap(a, b, c, m)
            if (singleCycleFrom(a) || repairSplit(a)) return
            swap(a, c, b, m)   // revert
            return
        }
    }

    /** Replace edges {p,q} and {r,s} with {p,r} and {q,s}. Caller validates. */
    private fun swap(p: Int, q: Int, r: Int, s: Int) {
        replaceNeighbor(p, q, r)
        replaceNeighbor(r, s, p)
        replaceNeighbor(q, p, s)
        replaceNeighbor(s, r, q)
    }

    private fun replaceNeighbor(cell: Int, old: Int, new: Int) {
        if (na[cell] == old) na[cell] = new else nb[cell] = new
    }

    private fun isEdge(x: Int, y: Int): Boolean = na[x] == y || nb[x] == y

    private fun adjacent(x: Int, y: Int): Boolean {
        if (x < 0 || y < 0) return false
        val dx = Math.abs(x % width - y % width)
        val dy = Math.abs(x / width - y / width)
        return dx + dy == 1
    }

    /** Walks the loop through [start]; true iff it covers the whole board. */
    private fun singleCycleFrom(start: Int): Boolean {
        val area = width * height
        walkStamp++
        var prev = start
        var cur = na[start]
        var count = 1
        walkMark[start] = walkStamp
        while (cur != start) {
            if (count > area || walkMark[cur] == walkStamp) return false
            walkMark[cur] = walkStamp
            val nxt = if (na[cur] != prev) na[cur] else nb[cur]
            prev = cur
            cur = nxt
            count++
        }
        return count == area
    }

    /** First step of the shortest free-cell path to the food, or -1. */
    private fun greedyStep(game: GameView, head: Int): Int {
        val food = game.food.y * width + game.food.x
        java.util.Arrays.fill(dist, -1)
        var qHead = 0
        var qTail = 0
        dist[food] = 0
        queue[qTail++] = food
        while (qHead < qTail) {
            val cur = queue[qHead++]
            if (cur == head) break
            val cx = cur % width
            val cy = cur / width
            for (dir in Direction.entries) {
                val nx = cx + dir.dx
                val ny = cy + dir.dy
                if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue
                val n = ny * width + nx
                if (dist[n] != -1) continue
                if (n != head && occupied.get(n)) continue
                dist[n] = dist[cur] + 1
                queue[qTail++] = n
            }
        }
        if (dist[head] == -1) return -1
        val hx = head % width
        val hy = head / width
        // straight-first tie-break: honest bots do not zigzag on equal paths
        val straight = if (cameFrom != -1) head + (head - cameFrom) else -1
        if (straight in 0 until width * height &&
            adjacent(head, straight) && !occupied.get(straight) &&
            dist[straight] == dist[head] - 1
        ) {
            return straight
        }
        for (dir in Direction.entries) {
            val nx = hx + dir.dx
            val ny = hy + dir.dy
            if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue
            val n = ny * width + nx
            if (!occupied.get(n) && dist[n] == dist[head] - 1) return n
        }
        return -1
    }

    private fun isVacatingTail(game: GameView, cell: Int): Boolean {
        val tail = game.snake.last()
        return tail.y * width + tail.x == cell && game.snake.size > 2
    }

    /**
     * Random Hamiltonian cycle from a random spanning tree of the half-resolution
     * grid: every 2x2 block starts as its own loop, and loops are merged across
     * each tree edge. A tree is acyclic and connected, so the merge result is a
     * single cycle through every cell — different in every game.
     */
    private fun buildCycle(w: Int, h: Int) {
        require(w % 2 == 0 && h % 2 == 0) { "showman needs even board dimensions" }
        width = w
        height = h
        val area = w * h
        na = IntArray(area) { -1 }
        nb = IntArray(area) { -1 }
        dist = IntArray(area)
        queue = IntArray(area)
        walkMark = IntArray(area)
        walkStamp = 0
        val w2 = w / 2
        val h2 = h / 2

        val right = Array(h2) { BooleanArray(w2) }
        val down = Array(h2) { BooleanArray(w2) }
        val visited = Array(h2) { BooleanArray(w2) }
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(random.nextInt(w2), random.nextInt(h2)))
        visited[stack.last()[1]][stack.last()[0]] = true
        val dirs = arrayOf(intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1))
        while (stack.isNotEmpty()) {
            val (x, y) = stack.last()
            val options = dirs.filter { (dx, dy) ->
                val nx = x + dx
                val ny = y + dy
                nx in 0 until w2 && ny in 0 until h2 && !visited[ny][nx]
            }
            if (options.isEmpty()) {
                stack.removeLast()
                continue
            }
            val (dx, dy) = options[random.nextInt(options.size)]
            val nx = x + dx
            val ny = y + dy
            visited[ny][nx] = true
            when {
                dx == 1 -> right[y][x] = true
                dx == -1 -> right[ny][nx] = true
                dy == 1 -> down[y][x] = true
                else -> down[ny][nx] = true
            }
            stack.addLast(intArrayOf(nx, ny))
        }

        fun link(x: Int, y: Int) {
            if (na[x] == -1) na[x] = y else nb[x] = y
            if (na[y] == -1) na[y] = x else nb[y] = x
        }

        for (yy in 0 until h2) {
            for (xx in 0 until w2) {
                val tl = (2 * yy) * w + 2 * xx
                val tr = tl + 1
                val bl = tl + w
                val br = tr + w
                // block edges, minus the sides opened by tree merges
                if (!(yy > 0 && down[yy - 1][xx])) link(tl, tr)
                if (!(yy + 1 < h2 && down[yy][xx])) link(bl, br)
                if (!(xx > 0 && right[yy][xx - 1])) link(tl, bl)
                if (!(xx + 1 < w2 && right[yy][xx])) link(tr, br)
                // merge links across tree edges
                if (xx + 1 < w2 && right[yy][xx]) {
                    link(tr, tr + 1)
                    link(br, br + 1)
                }
                if (yy + 1 < h2 && down[yy][xx]) {
                    link(bl, bl + w)
                    link(br, br + w)
                }
            }
        }
    }

    private fun direction(from: Int, to: Int): Direction = when (to - from) {
        -width -> Direction.UP
        width -> Direction.DOWN
        -1 -> Direction.LEFT
        else -> Direction.RIGHT
    }
}
