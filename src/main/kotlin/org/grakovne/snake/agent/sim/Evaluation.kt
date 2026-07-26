package org.grakovne.snake.agent.sim

import org.grakovne.snake.agent.core.DeathReason
import org.grakovne.snake.agent.core.GameResult
import org.grakovne.snake.agent.core.GameStatus

class Evaluation(val results: List<GameResult>) {

    init {
        require(results.isNotEmpty()) { "evaluation needs at least one game" }
    }

    val meanScore: Double = results.map { it.score }.average()
    val medianScore: Double = results.map { it.score }.sorted().let { scores ->
        if (scores.size % 2 == 1) scores[scores.size / 2].toDouble()
        else (scores[scores.size / 2 - 1] + scores[scores.size / 2]) / 2.0
    }
    val minScore: Int = results.minOf { it.score }
    val maxScore: Int = results.maxOf { it.score }
    val meanSteps: Double = results.map { it.steps }.average()
    val totalSteps: Long = results.sumOf { it.steps.toLong() }
    val wonGames: Int = results.count { it.status == GameStatus.WON }
    val deaths: Map<DeathReason, Int> =
        results.mapNotNull { it.deathReason }.groupingBy { it }.eachCount()

    fun summary(): String = buildString {
        appendLine("games: ${results.size}, won: $wonGames")
        appendLine(
            "score: mean=%.2f median=%.1f min=%d max=%d".format(meanScore, medianScore, minScore, maxScore)
        )
        appendLine("steps: mean=%.1f total=%d".format(meanSteps, totalSteps))
        append("deaths: ${deaths.entries.joinToString { "${it.key}=${it.value}" }.ifEmpty { "none" }}")
    }
}
