package org.grakovne.snake.agent.strategy

import org.grakovne.snake.agent.core.Direction
import org.grakovne.snake.agent.core.GameView
import kotlin.random.Random

/**
 * The showman: a guaranteed 3600/3600 that tries hard not to look like one.
 *
 * SHOW-ONLY, excluded from all research comparisons: this is the banned trick —
 * the snake rides a Hamiltonian cycle, which makes a full board a theorem, not
 * an achievement. The showmanship is that the cycle is not static: every tick
 * random 2-opt rewires (segment reversal between two grid chords) mutate the
 * route on stretches not occupied by the body, so the visible trajectory
 * wanders organically and never settles into a lawnmower pattern.
 *
 * Guarantees survive mutation: the body always stays a contiguous arc of the
 * current cycle (rewired stretches never touch it), so the cell ahead is never
 * body; food lies on the cycle and is reached within one lap. If mutations keep
 * dragging the food away for half a lap, mutation freezes until the next eat —
 * worst case 1.5 laps per food, well inside the starvation budget of two laps.
 */
class ShowmanStrategy(private val random: Random) : Strategy {

    private var width = 0
    private var height = 0
    private var next = IntArray(0)
    private var prev = IntArray(0)
    private val occupied = java.util.BitSet()

    override fun nextMove(game: GameView): Direction {
        if (next.size != game.width * game.height) buildCycle(game.width, game.height)

        occupied.clear()
        for (p in game.snake) occupied.set(p.y * width + p.x)

        // Mutation freeze: half a lap without food means the route kept dodging
        // it — stop reshaping, the frozen cycle reaches every cell in one lap.
        val area = width * height
        if (game.stepsSinceFood < area / 2) {
            repeat(2) { tryFlip() }
        }

        val head = game.snake.first().let { it.y * width + it.x }
        val target = next[head]
        if (!occupied.get(target) || isVacatingTail(game, target)) {
            return direction(head, target)
        }
        // Off-cycle start (the engine's initial body predates our cycle): any
        // safe neighbor until the body settles into a contiguous arc.
        for (dir in Direction.entries) {
            if (game.isSafeStep(dir)) return dir
        }
        return Direction.UP
    }

    private fun isVacatingTail(game: GameView, cell: Int): Boolean {
        val tail = game.snake.last()
        return tail.y * width + tail.x == cell && game.snake.size > 2
    }

    /**
     * One random 2-opt attempt. Pick edge (a -> b); pick a grid neighbor c of a
     * with successor d such that d is a grid neighbor of b. Rewiring to
     * a -> c, ..reversed.., b -> d reverses the stretch b..c and always keeps a
     * single Hamiltonian cycle. Rejected when the stretch is long (cost cap),
     * wraps past the body, or touches it.
     */
    private fun tryFlip() {
        val area = width * height
        val a = random.nextInt(area)
        val b = next[a]
        if (occupied.get(a) || occupied.get(b)) return
        val ax = a % width
        val ay = a / width
        var c = -1
        val pick = random.nextInt(4)
        for (i in 0 until 4) {
            val dir = Direction.entries[(pick + i) % 4]
            val nx = ax + dir.dx
            val ny = ay + dir.dy
            if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue
            val cand = ny * width + nx
            if (cand == b || cand == prev[a]) continue
            val d = next[cand]
            if (d == a) continue
            // d must neighbor b for the closing chord
            val bx = b % width
            val by = b / width
            val dx = d % width
            val dy = d / width
            if (Math.abs(bx - dx) + Math.abs(by - dy) != 1) continue
            c = cand
            break
        }
        if (c == -1) return
        val d = next[c]

        // walk b..c forward, bounded, refusing body cells
        var steps = 0
        var cur = b
        while (cur != c) {
            if (occupied.get(cur) || steps > 60) return
            cur = next[cur]
            steps++
        }
        if (occupied.get(c)) return

        // reverse the stretch b..c: a -> c, reversed interior, b -> d
        var node = b
        var prv = -1
        while (prv != c) {
            val nxt = next[node]
            val p2 = prev[node]
            next[node] = p2
            prev[node] = nxt
            prv = node
            node = nxt
        }
        next[a] = c
        prev[c] = a
        next[b] = d
        prev[d] = b
    }

    /** Left column down, then boustrophedon rows back up. Needs even height. */
    private fun buildCycle(w: Int, h: Int) {
        require(h % 2 == 0) { "showman needs an even board height" }
        width = w
        height = h
        next = IntArray(w * h)
        prev = IntArray(w * h)
        val order = ArrayList<Int>(w * h)
        for (y in 0 until h) order.add(y * w)                     // (0,0) .. (0,h-1)
        var leftToRight = true
        for (y in h - 1 downTo 0) {                                // rows over x in [1, w-1]
            if (leftToRight) {
                for (x in 1 until w) order.add(y * w + x)
            } else {
                for (x in w - 1 downTo 1) order.add(y * w + x)
            }
            leftToRight = !leftToRight
        }
        for (i in order.indices) {
            val from = order[i]
            val to = order[(i + 1) % order.size]
            next[from] = to
            prev[to] = from
        }
    }

    private fun direction(from: Int, to: Int): Direction = when (to - from) {
        -width -> Direction.UP
        width -> Direction.DOWN
        -1 -> Direction.LEFT
        else -> Direction.RIGHT
    }
}
