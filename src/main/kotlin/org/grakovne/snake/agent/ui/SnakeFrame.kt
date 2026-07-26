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
 * Everything is drawn on one canvas, so the header is pixel-aligned to the
 * board card: the score starts over its left edge, the percent ends over its
 * right edge, the bar is exactly as wide as the board. Game state is
 * communicated by the bar color: accent while running, gold on a full board,
 * red on death. The game thread publishes throttled snapshots; the EDT
 * samples them at 60 fps, so rendering stays smooth at any engine speed.
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

    private val canvas = object : JPanel() {
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.color = BG
            g2.fillRect(0, 0, width, height)

            // shared geometry: board card first, header aligns to it
            val availH = height - HEADER_H - MARGIN
            val cell = minOf((width - 2 * MARGIN) / fieldWidth, availH / fieldHeight)
                .coerceAtLeast(2)
            val boardW = cell * fieldWidth
            val boardH = cell * fieldHeight
            val x0 = (width - boardW) / 2
            val y0 = HEADER_H + (availH - boardH) / 2

            paintHeader(g2, x0, boardW)
            paintBoard(g2, x0, y0, cell, boardW, boardH)
        }

        private fun paintHeader(g2: Graphics2D, x0: Int, boardW: Int) {
            val snap = snapshot
            val score = snap?.score ?: 0
            val pct = score.toFloat() / area

            g2.font = NUM_BIG
            g2.color = TEXT
            val scoreStr = "%,d".format(score).replace(',', ' ')
            g2.drawString(scoreStr, x0, 46)

            g2.font = TEXT_SMALL
            g2.color = MUTED
            g2.drawString(
                "/ %,d".format(area).replace(',', ' '),
                x0 + g2.getFontMetrics(NUM_BIG).stringWidth(scoreStr) + 10, 46,
            )
            val pctStr = "%.1f%%".format(java.util.Locale.ROOT, pct * 100)
            g2.drawString(pctStr, x0 + boardW - g2.fontMetrics.stringWidth(pctStr), 46)

            val (from, to) = when (snap?.status) {
                GameStatus.WON -> GOLD to GOLD_DEEP
                GameStatus.DEAD -> BAD to BAD_DEEP
                else -> ACCENT to ACCENT_DEEP
            }
            g2.color = TRACK
            g2.fill(RoundRectangle2D.Float(x0.toFloat(), 60f, boardW.toFloat(), 6f, 3f, 3f))
            g2.paint = GradientPaint(x0.toFloat(), 0f, from, (x0 + boardW).toFloat(), 0f, to)
            g2.fill(RoundRectangle2D.Float(x0.toFloat(), 60f, boardW * pct, 6f, 3f, 3f))
        }

        private fun paintBoard(g2: Graphics2D, x0: Int, y0: Int, cell: Int, boardW: Int, boardH: Int) {
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

    init {
        val frame = JFrame("snake")
        frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
        frame.contentPane.background = BG
        frame.contentPane.layout = BorderLayout()

        canvas.background = BG
        canvas.preferredSize = Dimension(
            fieldWidth * 14 + 2 * MARGIN,
            fieldHeight * 14 + HEADER_H + MARGIN,
        )
        frame.add(canvas, BorderLayout.CENTER)
        frame.pack()
        frame.setLocationRelativeTo(null)
        frame.isVisible = true

        Timer(16) { canvas.repaint() }.start()
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
        val hue = 0.53f + 0.09f * t
        val sat = 0.65f + 0.15f * t
        val bri = 0.95f - 0.62f * t
        return Color.getHSBColor(hue, sat, bri)
    }

    private companion object {
        const val MARGIN = 32
        const val HEADER_H = 92

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
