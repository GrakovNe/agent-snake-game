package org.grakovne.snake.agent.core

enum class GameStatus {
    RUNNING,
    WON,
    DEAD,
}

enum class DeathReason {
    HIT_WALL,
    HIT_SELF,
    STARVED,
}

data class GameResult(
    val score: Int,
    val steps: Int,
    val status: GameStatus,
    val deathReason: DeathReason?,
    val seed: Long,
)
