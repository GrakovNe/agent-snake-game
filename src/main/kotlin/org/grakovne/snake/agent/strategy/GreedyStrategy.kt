package org.grakovne.snake.agent.strategy

import org.grakovne.snake.agent.core.Direction
import org.grakovne.snake.agent.core.GameView
import org.grakovne.snake.agent.core.Position

/**
 * Reference baseline: BFS shortest path to food; the step is taken only if the snake
 * still has at least its own length of reachable space afterwards, otherwise fall back
 * to the legal move that maximizes reachable free area (survival mode).
 */
class GreedyStrategy : Strategy {

    override fun nextMove(game: GameView): Direction {
        val toFood = firstStepTowardsFood(game)
        if (toFood != null && reachableArea(game, game.head + toFood) >= game.snake.size) {
            return toFood
        }

        val legal = game.legalMoves()
        if (legal.isEmpty()) return game.heading
        return legal.maxBy { reachableArea(game, game.head + it) }
    }

    private fun firstStepTowardsFood(game: GameView): Direction? {
        val width = game.width
        val startIndex = game.head.index(width)
        val previous = IntArray(width * game.height) { -1 }
        previous[startIndex] = startIndex

        val queue = ArrayDeque<Position>()
        queue.add(game.head)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current == game.food) {
                var index = current.index(width)
                while (previous[index] != startIndex) {
                    index = previous[index]
                }
                val firstCell = Position(index % width, index / width)
                return Direction.entries.first { game.head + it == firstCell }
            }
            for (direction in Direction.entries) {
                val next = current + direction
                if (!game.contains(next) || game.isOccupied(next)) continue
                val nextIndex = next.index(width)
                if (previous[nextIndex] != -1) continue
                previous[nextIndex] = current.index(width)
                queue.add(next)
            }
        }
        return null
    }

    private fun reachableArea(game: GameView, from: Position): Int {
        val tail = game.snake.last()
        fun passable(position: Position) =
            game.contains(position) && (!game.isOccupied(position) || position == tail)

        if (!passable(from)) return 0

        val visited = BooleanArray(game.width * game.height)
        visited[from.index(game.width)] = true
        val queue = ArrayDeque<Position>()
        queue.add(from)
        var area = 0

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            area++
            for (direction in Direction.entries) {
                val next = current + direction
                if (!passable(next)) continue
                val nextIndex = next.index(game.width)
                if (visited[nextIndex]) continue
                visited[nextIndex] = true
                queue.add(next)
            }
        }
        return area
    }

    private fun Position.index(width: Int) = y * width + x
}
