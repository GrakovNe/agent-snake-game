package org.grakovne.snake.agent.ui

import org.grakovne.snake.agent.core.GameStatus
import org.grakovne.snake.agent.core.Position
import org.grakovne.snake.agent.core.SnakeGame
import org.jfree.chart.ChartFactory
import org.jfree.chart.ChartPanel
import org.jfree.chart.plot.PlotOrientation
import org.jfree.data.xy.XYSeries
import org.jfree.data.xy.XYSeriesCollection
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

/**
 * Swing UI in the style of the original grakovne/snake project: 10px cells with a gray
 * frame, black snake with a red head, magenta food, big score counter and a transparent
 * length-over-steps chart on the right.
 */
class SnakeFrame(fieldWidth: Int, fieldHeight: Int) {

    private data class Snapshot(
        val snake: List<Position>,
        val food: Position,
        val score: Int,
        val status: GameStatus,
    )

    private val cell = 10
    private val margin = 30
    private val boardWidth = (fieldWidth + 2) * cell
    private val boardHeight = (fieldHeight + 2) * cell

    @Volatile
    private var snapshot: Snapshot? = null

    private val board = object : JPanel() {
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val current = snapshot ?: return
            val g2 = g as Graphics2D

            g2.color = Color.GRAY
            g2.fillRect(margin, margin, boardWidth, cell)
            g2.fillRect(margin, margin + boardHeight - cell, boardWidth, cell)
            g2.fillRect(margin, margin, cell, boardHeight)
            g2.fillRect(margin + boardWidth - cell, margin, cell, boardHeight)

            g2.color = Color.MAGENTA
            fillCell(g2, current.food)

            g2.color = Color.BLACK
            current.snake.forEachIndexed { index, segment ->
                if (index == 0) g2.color = Color.RED
                fillCell(g2, segment)
                if (index == 0) g2.color = Color.BLACK
            }
        }

        private fun fillCell(g2: Graphics2D, position: Position) {
            g2.fillRect(margin + (position.x + 1) * cell, margin + (position.y + 1) * cell, cell, cell)
        }
    }

    private val scoreLabel = JLabel()
    private val statusLabel = JLabel()
    private val series = XYSeries("Snake Length")
    private var chartSteps = 0

    init {
        val sideX = boardWidth + margin * 2
        val sideWidth = 360

        val frame = JFrame("org.grakovne.snake.agent")
        val panel = frame.contentPane as JPanel
        panel.layout = null
        panel.background = Color.WHITE

        board.background = Color.WHITE
        board.setBounds(0, 0, boardWidth + margin * 2, boardHeight + margin * 2)

        scoreLabel.setBounds(sideX, 40, sideWidth, 50)
        scoreLabel.font = scoreLabel.font.deriveFont(36.0f).deriveFont(1)
        scoreLabel.horizontalAlignment = SwingConstants.CENTER

        statusLabel.setBounds(sideX, 95, sideWidth, 30)
        statusLabel.horizontalAlignment = SwingConstants.CENTER

        val chart = ChartFactory.createXYLineChart(
            "", "Steps", "Size",
            XYSeriesCollection(series),
            PlotOrientation.VERTICAL,
            false, true, false,
        )
        val transparent = Color(0xFF, 0xFF, 0xFF, 0)
        chart.backgroundPaint = transparent
        chart.xyPlot.backgroundPaint = transparent
        chart.xyPlot.backgroundAlpha = 0.0f
        val chartPanel = ChartPanel(chart)
        chartPanel.setBounds(sideX, 140, sideWidth, 240)

        val about = JLabel("https://github.com/GrakovNe/agent-snake-game", SwingConstants.CENTER)
        about.font = about.font.deriveFont(11.0f).deriveFont(0)
        about.setBounds(sideX, 390, sideWidth, 30)

        panel.add(board)
        panel.add(scoreLabel)
        panel.add(statusLabel)
        panel.add(chartPanel)
        panel.add(about)

        frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
        frame.preferredSize = Dimension(
            boardWidth + margin * 2 + sideWidth + 40,
            maxOf(boardHeight + margin * 2, 470),
        )
        frame.setLocation(450, 250)
        frame.pack()
        frame.isVisible = true
    }

    /** Safe to call from the game thread. */
    fun render(game: SnakeGame) {
        val current = Snapshot(game.snake.toList(), game.food, game.score, game.status)
        snapshot = current
        SwingUtilities.invokeLater {
            scoreLabel.text = "%08d".format(current.score)
            statusLabel.text = when (current.status) {
                GameStatus.RUNNING -> "RUNNING"
                GameStatus.WON -> "WON"
                GameStatus.DEAD -> "DEAD (${(game.deathReason)})"
            }
            chartSteps++
            series.add(chartSteps.toDouble(), current.score.toDouble())
            board.repaint()
        }
    }
}
