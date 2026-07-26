package org.grakovne.snake.agent.core

/**
 * Field is width x height of playable cells, walls are implicit: stepping outside kills.
 * Same seed + same strategy = identical game, food spawns included.
 *
 * The snake starts at the field center, heading RIGHT, body laid out to the left of the head.
 */
data class GameConfig(
    val width: Int = 30,
    val height: Int = 30,
    val seed: Long = 0L,
    val initialLength: Int = 3,
    val maxStepsWithoutFood: Int = width * height * 2,
) {
    init {
        require(width >= 4 && height >= 4) { "field must be at least 4x4" }
        require(initialLength in 1..width / 2) { "initialLength must fit left of the center" }
        require(maxStepsWithoutFood > 0) { "maxStepsWithoutFood must be positive" }
    }
}
