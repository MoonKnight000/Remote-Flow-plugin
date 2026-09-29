package uz.remote.flow.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

class CollapsibleCard(
    private val title: String,
    val content: JComponent,
    initiallyExpanded: Boolean = true,
    headerRightComponent: JComponent? = null
) : JPanel(BorderLayout(0, 0)) {

    val contentPanel: JComponent get() = content

    var isExpanded: Boolean = initiallyExpanded
        private set

    private val toggleIconLabel = JLabel(if (initiallyExpanded) "▼" else "▶")
    private val titleLabel = JLabel(title)
    private val headerPanel = JPanel(BorderLayout(8, 0))

    init {
        isOpaque = false
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(JBColor(Color(220, 224, 230), Color(60, 63, 65)), 1, true),
            JBUI.Borders.empty(0)
        )

        headerPanel.isOpaque = true
        headerPanel.background = JBColor(Color(245, 247, 250), Color(45, 48, 50))
        headerPanel.border = JBUI.Borders.empty(7, 10)
        headerPanel.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        leftPanel.isOpaque = false

        toggleIconLabel.font = Font("Dialog", Font.BOLD, 10)
        toggleIconLabel.foreground = JBColor(Color(107, 114, 128), Color(156, 163, 175))
        leftPanel.add(toggleIconLabel)

        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 12f)
        titleLabel.foreground = JBColor.foreground()
        leftPanel.add(titleLabel)

        headerPanel.add(leftPanel, BorderLayout.WEST)

        if (headerRightComponent != null) {
            headerPanel.add(headerRightComponent, BorderLayout.EAST)
        }

        val toggleListener = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (headerRightComponent != null && SwingUtilities.isDescendingFrom(e.component, headerRightComponent)) {
                    return
                }
                toggle()
            }
        }

        headerPanel.addMouseListener(toggleListener)
        leftPanel.addMouseListener(toggleListener)
        titleLabel.addMouseListener(toggleListener)
        toggleIconLabel.addMouseListener(toggleListener)

        contentPanel.isVisible = initiallyExpanded
        contentPanel.border = JBUI.Borders.empty(8)

        add(headerPanel, BorderLayout.NORTH)
        add(contentPanel, BorderLayout.CENTER)
    }

    fun toggle() {
        setExpanded(!isExpanded)
    }

    fun setExpanded(expanded: Boolean) {
        isExpanded = expanded
        toggleIconLabel.text = if (expanded) "▼" else "▶"
        contentPanel.isVisible = expanded
        revalidate()
        repaint()
    }
}
