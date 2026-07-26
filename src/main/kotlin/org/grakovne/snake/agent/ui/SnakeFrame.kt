package org.grakovne.snake.agent.ui

import org.grakovne.snake.agent.core.GameStatus
import org.grakovne.snake.agent.core.SnakeGame
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.Timer

/**
 * Pure show mode: the board, the score and a progress bar — nothing else.
 * The game thread publishes throttled snapshots; the EDT samples them at 60 fps,
 * so rendering stays smooth at any engine speed. Game state is communicated by
 * the bar color: accent while running, gold on a full board, red on death.
 */
class SnakeFrame(private val fieldWidth: Int, private val fieldHeight: Int) {

    private class Snapshot(
        val body: IntArray,          // head first, cell indices
        val food: Int,
        val score: Int,
        val status: GameStatus,
    )

    @Volatile
    private var snapshot: Snapshot? = null
    private var lastPublishNanos = 0L

    private val area = fieldWidth * fieldHeight

    private val header = object : JPanel() {
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.color = BG
            g2.fillRect(0, 0, width, height)

            val snap = snapshot
            val score = snap?.score ?: 0
            val pct = score.toFloat() / area
            val x = PAD
            val w = width - 2 * PAD

            g2.font = NUM_BIG
            g2.color = TEXT
            val scoreStr = "%,d".format(score).replace(',', ' ')
            g2.drawString(scoreStr, x, 44)

            g2.font = TEXT_SMALL
            g2.color = MUTED
            val total = "/ %,d".format(area).replace(',', ' ')
            g2.drawString(total, x + g2.getFontMetrics(NUM_BIG).stringWidth(scoreStr) + 10, 44)

            val pctStr = "%.1f%%".format(java.util.Locale.ROOT, pct * 100)
            g2.drawString(pctStr, x + w - g2.fontMetrics.stringWidth(pctStr), 44)

            val (from, to) = when (snap?.status) {
                GameStatus.WON -> GOLD to GOLD_DEEP
                GameStatus.DEAD -> BAD to BAD_DEEP
                else -> ACCENT to ACCENT_DEEP
            }
            g2.color = TRACK
            g2.fill(RoundRectangle2D.Float(x.toFloat(), 58f, w.toFloat(), 6f, 3f, 3f))
            g2.paint = GradientPaint(x.toFloat(), 0f, from, (x + w).toFloat(), 0f, to)
            g2.fill(RoundRectangle2D.Float(x.toFloat(), 58f, w * pct, 6f, 3f, 3f))
        }
    }

    private val board = object : JPanel() {
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = BG
            g2.fillRect(0, 0, width, height)

            val cell = minOf((width - 2 * PAD) / fieldWidth, (height - TOP_GAP - PAD) / fieldHeight)
                .coerceAtLeast(2)
            val boardW = cell * fieldWidth
            val boardH = cell * fieldHeight
            val x0 = (width - boardW) / 2
            val y0 = TOP_GAP + (height - TOP_GAP - PAD - boardH) / 2

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
            // (connector is defined on the class to keep this closure light)

            // Segments are joined by small connectors so the body reads as one
            // continuous creature instead of a string of beads.
            val n = snap.body.size
            for (i in n - 1 downTo 1) {
                g2.color = bodyColor(i.toFloat() / n)
                g2.fill(cellRect(snap.body[i]))
                connector(g2, snap.body[i], snap.body[i - 1], x0, y0, cell, gap)
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

    init {
        val frame = JFrame("snake")
        frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
        frame.contentPane.background = BG
        frame.contentPane.layout = BorderLayout()

        header.preferredSize = Dimension(0, 76)
        board.preferredSize = Dimension(fieldWidth * 14 + 2 * PAD, fieldHeight * 14 + PAD)

        frame.add(header, BorderLayout.NORTH)
        frame.add(board, BorderLayout.CENTER)
        frame.setSize(fieldWidth * 14 + 2 * PAD + 16, fieldHeight * 14 + PAD + 76 + 40)
        frame.setLocationRelativeTo(null)
        frame.isVisible = true

        Timer(16) {
            header.repaint()
            board.repaint()
        }.start()
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
            body, game.food.y * fieldWidth + game.food.x, game.score, game.status,
        )
    }

    /** Kept for the runner's lifecycle; the show needs no per-game bookkeeping. */
    @Suppress("UNUSED_PARAMETER")
    fun newGame(round: Long, strategy: String, seed: Long) = Unit

    private fun bodyColor(t: Float): Color {
        // brightness floor keeps the tail clearly visible against the board
        val hue = 0.53f + 0.09f * t
        val sat = 0.62f + 0.18f * t
        val bri = 0.95f - 0.38f * t
        return Color.getHSBColor(hue, sat, bri)
    }

    /** Fills the gap strip between two adjacent body cells (current fill color). */
    private fun connector(g2: Graphics2D, a: Int, b: Int, x0: Int, y0: Int, cell: Int, gap: Int) {
        if (gap == 0) return
        val ax = x0 + (a % fieldWidth) * cell
        val ay = y0 + (a / fieldWidth) * cell
        val bx = x0 + (b % fieldWidth) * cell
        val by = y0 + (b / fieldWidth) * cell
        when {
            ay == by -> g2.fillRect(minOf(ax, bx) + cell - gap, ay + gap, 2 * gap, cell - 2 * gap)
            ax == bx -> g2.fillRect(ax + gap, minOf(ay, by) + cell - gap, cell - 2 * gap, 2 * gap)
        }
    }

    private companion object {
        const val PAD = 24
        const val TOP_GAP = 20

        val BG = Color(0x0f1115)
        val CARD = Color(0x171a21)
        val TRACK = Color(0x232833)
        val TEXT = Color(0xe5e7eb)
        val MUTED = Color(0x8b93a3)
        val ACCENT = Color(0x38bdf8)
        val ACCENT_DEEP = Color(0x2563eb)
        val GOLD = Color(0xeab308)
        val GOLD_DEEP = Color(0xca8a04)
        val BAD = Color(0xf87171)
        val BAD_DEEP = Color(0xdc2626)
        val HEAD = Color(0xe0f2fe)
        val FOOD = Color(0xfb923c)
        val FOOD_GLOW = Color(0xfb, 0x92, 0x3c, 0x33)

        val NUM_BIG = Font("Helvetica Neue", Font.BOLD, 36)
        val TEXT_SMALL = Font("Helvetica Neue", Font.PLAIN, 13)
    }
}
