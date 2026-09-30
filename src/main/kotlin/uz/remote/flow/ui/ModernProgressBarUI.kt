package uz.remote.flow.ui

import com.intellij.ui.JBColor
import java.awt.*
import java.awt.geom.Area
import java.awt.geom.RoundRectangle2D
import javax.swing.JComponent
import javax.swing.plaf.basic.BasicProgressBarUI

/**
 * Custom modern ProgressBar UI that ensures high-contrast percentage text
 * and smooth rounded corners across both Light and Dark (Darcula) IDE themes.
 */
class ModernProgressBarUI : BasicProgressBarUI() {

    override fun getSelectionForeground(): Color = Color.WHITE

    override fun getSelectionBackground(): Color = JBColor(Color(35, 38, 42), Color(230, 235, 240))

    override fun paintDeterminate(g: Graphics, c: JComponent) {
        if (g !is Graphics2D) return
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            val width = c.width
            val height = c.height
            val arc = height.toFloat().coerceAtMost(12f)

            // 1. Paint track (background)
            val trackColor = JBColor(Color(228, 231, 235), Color(43, 45, 48))
            g2.color = trackColor
            val trackShape = RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), arc, arc)
            g2.fill(trackShape)

            // 2. Paint progress (filled part)
            val percent = progressBar.percentComplete
            val fillWidth = (width * percent).toFloat()
            if (fillWidth > 0f) {
                val fillShape = RoundRectangle2D.Float(0f, 0f, fillWidth, height.toFloat(), arc, arc)
                val area = Area(trackShape)
                area.intersect(Area(fillShape))
                g2.color = progressBar.foreground
                g2.fill(area)
            }

            // 3. Paint text string if enabled
            if (progressBar.isStringPainted) {
                paintString(g2, 0, 0, width, height, fillWidth.toInt(), null)
            }
        } finally {
            g2.dispose()
        }
    }

    override fun paintString(
        g: Graphics,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        amountFull: Int,
        b: Insets?
    ) {
        if (g !is Graphics2D) return
        val progressString = progressBar.string ?: return
        if (progressString.isBlank()) return

        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val baseFont = progressBar.font ?: g2.font
            val fontSize = 11f.coerceAtMost((height - 3).toFloat().coerceAtLeast(9f))
            g2.font = baseFont.deriveFont(Font.BOLD, fontSize)
            val fm = g2.fontMetrics
            val stringWidth = fm.stringWidth(progressString)
            val stringHeight = fm.ascent

            val textX = (width - stringWidth) / 2
            val textY = (height + stringHeight) / 2 - 1

            // 1. Draw with selectionBackground (for unfilled track)
            g2.color = getSelectionBackground()
            g2.drawString(progressString, textX, textY)

            // 2. Clip to filled area and draw with selectionForeground (for filled part)
            if (amountFull > 0) {
                val oldClip = g2.clip
                g2.clipRect(0, 0, amountFull, height)
                g2.color = getSelectionForeground()
                g2.drawString(progressString, textX, textY)
                g2.clip = oldClip
            }
        } finally {
            g2.dispose()
        }
    }
}
