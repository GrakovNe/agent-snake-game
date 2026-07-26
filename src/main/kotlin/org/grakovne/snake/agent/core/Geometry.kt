package org.grakovne.snake.agent.core

import kotlin.math.abs

data class Position(val x: Int, val y: Int) {
    operator fun plus(direction: Direction) = Position(x + direction.dx, y + direction.dy)

    fun manhattanTo(other: Position) = abs(x - other.x) + abs(y - other.y)
}

enum class Direction(val dx: Int, val dy: Int) {
    UP(0, -1),
    DOWN(0, 1),
    LEFT(-1, 0),
    RIGHT(1, 0);

    val opposite: Direction
        get() = when (this) {
            UP -> DOWN
            DOWN -> UP
            LEFT -> RIGHT
            RIGHT -> LEFT
        }
}
