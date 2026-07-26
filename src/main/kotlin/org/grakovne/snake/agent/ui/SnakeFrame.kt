package org.grakovne.snake.agent.ui

import org.grakovne.snake.agent.core.GameStatus
import org.grakovne.snake.agent.core.SnakeGame
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.Timer

/**
 * Modern dark UI. The game thread publishes throttled volatile snapshots; the EDT
 * samples them at 60 fps — game speed and rendering are fully decoupled, so a
 * full-throttle engine never floods the UI. The side panel is a single custom
 * canvas: score card, status chip, a real length chart and a stats block.
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

    // score history ring for the chart
    private val history = IntArray(CHART_POINTS)
    private var historyCount = 0
    private var historyPos = 0
    private var sampleCountdown = 0

    private var roundNumber = 0L
    private var strategyName = ""
    private var seedText = ""
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

            g2.color = CARD
            g2.fill(RoundRectangle2D.Float(
                (x0 - 8).toFloat(), (y0 - 8).toFloat(),
                (boardW + 16).toFloat(), (boardH + 16).toFloat(), 18f, 18f,
            ))

            val snap = snapshot ?: return
            val gap = if (cell >= 6) 1 else 0
            val arc = (cell * 0.35f).coerceAtMost(6f)

            fun cellRect(idx: Int): RoundRectangle2D.Float {
                val cx = x0 + (idx % fieldWidth) * cell
                val cy = y0 + (idx / fieldWidth) * cell
                return RoundRectangle2D.Float(
                    (cx + gap).toFloat(), (cy + gap).toFloat(),
                    (cell - 2 * gap).toFloat(), (cell - 2 * gap).toFloat(),
                    arc, arc,
                )
            }

            val n = snap.body.size
            for (i in n - 1 downTo 1) {
                g2.color = bodyColor(i.toFloat() / n)
                g2.fill(cellRect(snap.body[i]))
            }
            g2.color = HEAD
            g2.fill(cellRect(snap.body[0]))

            if (snap.status == GameStatus.RUNNING) {
                val r = cellRect(snap.food)
                g2.color = FOOD_GLOW
                g2.fill(RoundRectangle2D.Float(r.x - 3, r.y - 3, r.width + 6, r.height + 6, arc + 3, arc + 3))
                g2.color = FOOD
                g2.fill(r)
            }
        }
    }

    private val side = object : JPanel() {
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.color = BG
            g2.fillRect(0, 0, width, height)

            val snap = snapshot
            val x = 20
            val w = width - 40
            var y = 34

            // ---- score ----
            val score = snap?.score ?: 0
            g2.font = NUM_BIG
            g2.color = TEXT
            val scoreStr = "%,d".format(score).replace(',', ' ')
            g2.drawString(scoreStr, x, y + 34)
            g2.font = TEXT_SMALL
            g2.color = MUTED
            val scoreW = g2.getFontMetrics(NUM_BIG).stringWidth(scoreStr)
            g2.drawString("/ %,d".format(area).replace(',', ' '), x + scoreW + 10, y + 34)
            y += 50

            // progress bar
            val pct = score.toFloat() / area
            g2.color = TRACK
            g2.fill(RoundRectangle2D.Float(x.toFloat(), y.toFloat(), w.toFloat(), 6f, 3f, 3f))
            g2.paint = GradientPaint(x.toFloat(), 0f, ACCENT, (x + w).toFloat(), 0f, ACCENT_DEEP)
            g2.fill(RoundRectangle2D.Float(x.toFloat(), y.toFloat(), w * pct, 6f, 3f, 3f))
            y += 22
            g2.font = TEXT_SMALL
            g2.color = MUTED
            g2.drawString("%.1f%% of the board".format(pct * 100), x, y)
            y += 28

            // ---- status chip ----
            val (chipText, chipColor) = when (snap?.status) {
                GameStatus.WON -> "BOARD FULL" to GOLD
                GameStatus.DEAD -> "DEAD · ${snap.reason ?: ""}" to BAD
                else -> "RUNNING" to OK
            }
            g2.font = CHIP_FONT
            val chipW = g2.fontMetrics.stringWidth(chipText) + 24
            g2.color = Color(chipColor.red, chipColor.green, chipColor.blue, 38)
            g2.fill(RoundRectangle2D.Float(x.toFloat(), y.toFloat(), chipW.toFloat(), 24f, 12f, 12f))
            g2.color = chipColor
            g2.drawString(chipText, x + 12, y + 16)
            y += 46

            // ---- chart ----
            g2.font = HEADER_FONT
            g2.color = MUTED
            g2.drawString("LENGTH", x, y)
            y += 10
            val chartH = 190
            g2.color = CARD
            g2.fill(RoundRectangle2D.Float(x.toFloat(), y.toFloat(), w.toFloat(), chartH.toFloat(), 12f, 12f))
            paintChart(g2, x + 14, y + 12, w - 28, chartH - 24)
            y += chartH + 30

            // ---- stats ----
            g2.font = HEADER_FONT
            g2.color = MUTED
            g2.drawString("RUN", x, y)
            y += 18
            val rows = listOf(
                "game" to "#${roundNumber + 1}",
                "strategy" to strategyName,
                "seed" to seedText,
                "steps" to "%,d".format(snap?.steps ?: 0).replace(',', ' '),
                "speed" to speedText,
            )
            for ((label, value) in rows) {
                g2.font = TEXT_SMALL
                g2.color = MUTED
                g2.drawString(label, x, y)
                g2.font = MONO
                g2.color = TEXT
                val vw = g2.fontMetrics.stringWidth(value)
                g2.drawString(value, x + w - vw, y)
                y += 22
            }
        }

        private fun paintChart(g2: Graphics2D, cx: Int, cy: Int, cw: Int, ch: Int) {
            // level grid: 25 / 50 / 75 / 100%
            g2.font = TINY
            for (level in intArrayOf(25, 50, 75, 100)) {
                val ly = cy + ch - ch * level / 100
                g2.color = if (level == 100) GRID_STRONG else GRID
                g2.stroke = if (level == 100) DASH else THIN
                g2.drawLine(cx + 26, ly, cx + cw, ly)
                g2.color = MUTED_DIM
                g2.drawString("$level", cx, ly + 4)
            }
            g2.stroke = THIN

            val count = historyCount
            if (count < 2) return
            val plotX = cx + 26
            val plotW = cw - 26

            val line = Path2D.Float()
            val fill = Path2D.Float()
            var lastX = 0f
            var lastY = 0f
            for (i in 0 until count) {
                val value = history[(historyPos - count + i + CHART_POINTS) % CHART_POINTS]
                val px = plotX + plotW.toFloat() * i / (CHART_POINTS - 1)
                val py = cy + ch - ch.toFloat() * value / area
                if (i == 0) {
                    line.moveTo(px, py)
                    fill.moveTo(px, (cy + ch).toFloat())
                    fill.lineTo(px, py)
                } else {
                    line.lineTo(px, py)
                    fill.lineTo(px, py)
                }
                lastX = px
                lastY = py
            }
            fill.lineTo(lastX, (cy + ch).toFloat())
            fill.closePath()

            g2.paint = GradientPaint(0f, cy.toFloat(), ACCENT_FILL, 0f, (cy + ch).toFloat(), ACCENT_FADE)
            g2.fill(fill)
            g2.color = ACCENT
            g2.stroke = LINE
            g2.draw(line)
            g2.fillOval(lastX.toInt() - 3, lastY.toInt() - 3, 6, 6)
            g2.stroke = THIN
        }
    }

    init {
        val frame = JFrame("snake · research bot")
        frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
        frame.contentPane.background = BG
        frame.contentPane.layout = BorderLayout()

        board.background = BG
        board.preferredSize = Dimension(fieldWidth * 14 + 2 * PAD, fieldHeight * 14 + 2 * PAD)
        side.background = BG
        side.preferredSize = Dimension(320, 0)

        frame.add(board, BorderLayout.CENTER)
        frame.add(side, BorderLayout.EAST)
        frame.setSize(fieldWidth * 14 + 2 * PAD + 340, maxOf(fieldHeight * 14 + 2 * PAD + 40, 560))
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

        synchronized(history) {
            if (--sampleCountdown <= 0 || terminal) {
                history[historyPos] = game.score
                historyPos = (historyPos + 1) % CHART_POINTS
                if (historyCount < CHART_POINTS) historyCount++
                sampleCountdown = 20
            }
        }
    }

    /** Called between games. */
    fun newGame(round: Long, strategy: String, seed: Long) {
        roundNumber = round
        strategyName = strategy
        seedText = seed.toString()
        synchronized(history) {
            historyCount = 0
            historyPos = 0
            sampleCountdown = 0
        }
        lastSampledSteps = 0
        lastSampleNanos = System.nanoTime()
    }

    private fun refresh() {
        val snap = snapshot ?: return
        val now = System.nanoTime()
        if (now - lastSampleNanos > 500_000_000) {
            val stepsPerSec = (snap.steps - lastSampledSteps) * 1e9 / (now - lastSampleNanos)
            speedText = "%,.0f st/s".format(stepsPerSec).replace(',', ' ')
            lastSampledSteps = snap.steps
            lastSampleNanos = now
        }
        board.repaint()
        side.repaint()
    }

    private fun bodyColor(t: Float): Color {
        val hue = 0.53f + 0.09f * t
        val sat = 0.65f + 0.15f * t
        val bri = 0.95f - 0.62f * t
        return Color.getHSBColor(hue, sat, bri)
    }

    private companion object {
        const val CHART_POINTS = 700
        const val PAD = 24

        val BG = Color(0x0f1115)
        val CARD = Color(0x171a21)
        val TRACK = Color(0x232833)
        val GRID = Color(0x232833)
        val GRID_STRONG = Color(0x3a4150)
        val TEXT = Color(0xe5e7eb)
        val MUTED = Color(0x8b93a3)
        val MUTED_DIM = Color(0x596070)
        val ACCENT = Color(0x38bdf8)
        val ACCENT_DEEP = Color(0x2563eb)
        val ACCENT_FILL = Color(0x38, 0xbd, 0xf8, 70)
        val ACCENT_FADE = Color(0x38, 0xbd, 0xf8, 6)
        val HEAD = Color(0xe0f2fe)
        val FOOD = Color(0xfb923c)
        val FOOD_GLOW = Color(0xfb, 0x92, 0x3c, 0x33)
        val OK = Color(0x34d399)
        val GOLD = Color(0xeab308)
        val BAD = Color(0xf87171)

        val NUM_BIG = Font("Helvetica Neue", Font.BOLD, 40)
        val TEXT_SMALL = Font("Helvetica Neue", Font.PLAIN, 13)
        val CHIP_FONT = Font("Helvetica Neue", Font.BOLD, 12)
        val HEADER_FONT = Font("Helvetica Neue", Font.BOLD, 11)
        val TINY = Font("Helvetica Neue", Font.PLAIN, 10)
        val MONO = Font("Menlo", Font.PLAIN, 12)

        val THIN = BasicStroke(1f)
        val LINE = BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        val DASH = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 4f, floatArrayOf(3f, 4f), 0f)
    }
}
