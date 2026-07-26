package org.grakovne.snake.agent.ui

import org.grakovne.snake.agent.core.GameStatus
import org.grakovne.snake.agent.core.SnakeGame
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.Timer

/**
 * Modern dark UI. The game thread publishes throttled volatile snapshots; the EDT
 * samples them at 60 fps — game speed and rendering are fully decoupled, so a
 * full-throttle engine never floods the UI.
 */
class SnakeFrame(private val fieldWidth: Int, private val fieldHeight: Int) {

    private class Snapshot(
        val body: IntArray,          // head first, cell indices
        val food: Int,
        val score: Int,
        val steps: Int,
        val status: GameStatus,
        val reason: String?,
    )

    @Volatile
    private var snapshot: Snapshot? = null
    private var lastPublishNanos = 0L

    // score history ring for the sparkline
    private val sparkline = IntArray(SPARK_POINTS)
    private var sparkCount = 0
    private var sparkPos = 0
    private var sampleCountdown = 0

    private var roundLabelText = ""
    private var speedText = "—"
    private var lastSampledSteps = 0
    private var lastSampleNanos = System.nanoTime()

    private val area = fieldWidth * fieldHeight

    private val board = object : JPanel() {
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = BG
            g2.fillRect(0, 0, width, height)

            val cell = minOf((width - 2 * PAD) / fieldWidth, (height - 2 * PAD) / fieldHeight)
                .coerceAtLeast(2)
            val boardW = cell * fieldWidth
            val boardH = cell * fieldHeight
            val x0 = (width - boardW) / 2
            val y0 = (height - boardH) / 2

            g2.color = BOARD
            g2.fill(RoundRectangle2D.Float(
                (x0 - 8).toFloat(), (y0 - 8).toFloat(),
                (boardW + 16).toFloat(), (boardH + 16).toFloat(), 18f, 18f,
            ))

            val snap = snapshot ?: return
            val gap = if (cell >= 6) 1 else 0
            val arc = (cell * 0.35f).coerceAtMost(6f)

            fun cellRect(idx: Int, inset: Int = 0): RoundRectangle2D.Float {
                val cx = x0 + (idx % fieldWidth) * cell
                val cy = y0 + (idx / fieldWidth) * cell
                return RoundRectangle2D.Float(
                    (cx + gap + inset).toFloat(), (cy + gap + inset).toFloat(),
                    (cell - 2 * gap - 2 * inset).toFloat(), (cell - 2 * gap - 2 * inset).toFloat(),
                    arc, arc,
                )
            }

            // body: hue gradient head -> tail
            val n = snap.body.size
            for (i in n - 1 downTo 1) {
                val t = i.toFloat() / n
                g2.color = bodyColor(t)
                g2.fill(cellRect(snap.body[i]))
            }
            // head
            g2.color = HEAD
            g2.fill(cellRect(snap.body[0]))

            // food with a soft glow
            if (snap.status == GameStatus.RUNNING) {
                val r = cellRect(snap.food)
                g2.color = FOOD_GLOW
                g2.fill(RoundRectangle2D.Float(r.x - 3, r.y - 3, r.width + 6, r.height + 6, arc + 3, arc + 3))
                g2.color = FOOD
                g2.fill(r)
            }
        }
    }

    private val scoreLabel = label(44f, Font.BOLD, TEXT)
    private val scoreSub = label(13f, Font.PLAIN, MUTED)
    private val statusChip = label(12f, Font.BOLD, BG)
    private val statsLabel = label(12f, Font.PLAIN, MUTED)

    private val spark = object : JPanel() {
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = BOARD
            g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), 12f, 12f))
            val count = sparkCount
            if (count < 2) return

            // target line at area-1
            g2.color = GRID
            g2.stroke = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 4f, floatArrayOf(3f, 4f), 0f)
            val yTarget = 6f
            g2.drawLine(8, yTarget.toInt(), width - 8, yTarget.toInt())

            g2.stroke = BasicStroke(1.6f)
            g2.color = ACCENT
            val usable = width - 16f
            val h = height - 12f
            var prevX = 8f
            var prevY = 0f
            for (i in 0 until count) {
                val value = sparkline[(sparkPos - count + i + SPARK_POINTS) % SPARK_POINTS]
                val x = 8f + usable * i / (count - 1)
                val y = 6f + h * (1f - value.toFloat() / area)
                if (i > 0) g2.drawLine(prevX.toInt(), prevY.toInt(), x.toInt(), y.toInt())
                prevX = x
                prevY = y
            }
        }
    }

    init {
        val frame = JFrame("snake · research bot")
        frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
        frame.contentPane.background = BG
        frame.contentPane.layout = BorderLayout()

        board.background = BG
        board.preferredSize = Dimension(fieldWidth * 14 + 2 * PAD, fieldHeight * 14 + 2 * PAD)

        val side = JPanel()
        side.layout = BoxLayout(side, BoxLayout.Y_AXIS)
        side.background = BG
        side.border = BorderFactory.createEmptyBorder(28, 8, 28, 24)
        side.preferredSize = Dimension(280, 0)

        statusChip.isOpaque = true
        statusChip.background = OK
        statusChip.border = BorderFactory.createEmptyBorder(4, 12, 4, 12)

        listOf(scoreLabel, scoreSub, statusChip, statsLabel, spark).forEach {
            (it as? JLabel)?.alignmentX = 0f
        }
        spark.alignmentX = 0f
        spark.maximumSize = Dimension(Int.MAX_VALUE, 90)
        spark.background = BG

        side.add(scoreLabel)
        side.add(scoreSub)
        side.add(Box.createVerticalStrut(16))
        side.add(statusChip)
        side.add(Box.createVerticalStrut(20))
        side.add(spark)
        side.add(Box.createVerticalStrut(16))
        side.add(statsLabel)
        side.add(Box.createVerticalGlue())

        frame.add(board, BorderLayout.CENTER)
        frame.add(side, BorderLayout.EAST)
        frame.setSize(fieldWidth * 14 + 2 * PAD + 300, maxOf(fieldHeight * 14 + 2 * PAD + 40, 480))
        frame.setLocationRelativeTo(null)
        frame.isVisible = true

        Timer(16) { refresh() }.start()
    }

    /** Called from the game thread; cheap and self-throttling. */
    fun render(game: SnakeGame) {
        val now = System.nanoTime()
        val terminal = game.status != GameStatus.RUNNING
        if (!terminal && now - lastPublishNanos < 8_000_000) return
        lastPublishNanos = now

        val body = IntArray(game.snake.size)
        for (i in game.snake.indices) {
            val p = game.snake[i]
            body[i] = p.y * fieldWidth + p.x
        }
        snapshot = Snapshot(
            body, game.food.y * fieldWidth + game.food.x,
            game.score, game.steps, game.status, game.deathReason?.name,
        )

        synchronized(sparkline) {
            if (--sampleCountdown <= 0 || terminal) {
                sparkline[sparkPos] = game.score
                sparkPos = (sparkPos + 1) % SPARK_POINTS
                if (sparkCount < SPARK_POINTS) sparkCount++
                sampleCountdown = 24
            }
        }
    }

    /** Called between games. */
    fun newGame(round: Long, strategy: String, seed: Long) {
        roundLabelText = "game #${round + 1} · $strategy · seed $seed"
        synchronized(sparkline) {
            sparkCount = 0
            sparkPos = 0
            sampleCountdown = 0
        }
        lastSampledSteps = 0
        lastSampleNanos = System.nanoTime()
    }

    private fun refresh() {
        val snap = snapshot ?: return
        scoreLabel.text = "%,d".format(snap.score)
        scoreSub.text = "of %,d cells · %.1f%% full".format(area, 100.0 * snap.score / area)

        val now = System.nanoTime()
        if (now - lastSampleNanos > 500_000_000) {
            val stepsPerSec = (snap.steps - lastSampledSteps) * 1e9 / (now - lastSampleNanos)
            speedText = "%,.0f steps/s".format(stepsPerSec)
            lastSampledSteps = snap.steps
            lastSampleNanos = now
        }
        statsLabel.text = "<html>${roundLabelText}<br>steps %,d · %s</html>"
            .format(snap.steps, speedText)

        when (snap.status) {
            GameStatus.RUNNING -> {
                statusChip.text = "RUNNING"
                statusChip.background = OK
            }
            GameStatus.WON -> {
                statusChip.text = "BOARD FULL — WON"
                statusChip.background = GOLD
            }
            GameStatus.DEAD -> {
                statusChip.text = "DEAD · ${snap.reason ?: ""}"
                statusChip.background = BAD
            }
        }
        board.repaint()
        spark.repaint()
    }

    private fun label(size: Float, style: Int, color: Color) = JLabel().apply {
        font = Font("Helvetica Neue", style, size.toInt())
        foreground = color
    }

    private fun bodyColor(t: Float): Color {
        // head-side: light cyan, tail-side: deep indigo
        val hue = 0.53f + 0.09f * t
        val sat = 0.65f + 0.15f * t
        val bri = 0.95f - 0.62f * t
        return Color.getHSBColor(hue, sat, bri)
    }

    private companion object {
        const val SPARK_POINTS = 600
        const val PAD = 24
        val BG = Color(0x0f1115)
        val BOARD = Color(0x171a21)
        val GRID = Color(0x3a3f4b)
        val TEXT = Color(0xe5e7eb)
        val MUTED = Color(0x8b93a3)
        val ACCENT = Color(0x38bdf8)
        val HEAD = Color(0xe0f2fe)
        val FOOD = Color(0xfb923c)
        val FOOD_GLOW = Color(0xfb923c and 0xffffff or (0x33 shl 24), true)
        val OK = Color(0x22c55e)
        val GOLD = Color(0xeab308)
        val BAD = Color(0xef4444)
    }
}
