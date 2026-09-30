package uz.remote.flow.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.*

class ConnectionStatusBadge : JPanel(FlowLayout(FlowLayout.CENTER, 6, 2)) {

    private val iconLabel = JLabel(RemoteFlowIcons.CLOUD_DISCONNECTED)
    private val textLabel = JLabel("Disconnected")
    private val envLabel = JLabel("")

    init {
        isOpaque = true
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(JBColor(Color(229, 231, 235), Color(60, 63, 65)), 1, true),
            JBUI.Borders.empty(1, 8, 1, 8)
        )
        background = JBColor(Color(243, 244, 246), Color(45, 48, 50))

        textLabel.font = textLabel.font.deriveFont(Font.BOLD, 11f)
        textLabel.foreground = JBColor.GRAY

        envLabel.font = envLabel.font.deriveFont(Font.BOLD, 10f)
        envLabel.isOpaque = true
        envLabel.isVisible = false

        add(iconLabel)
        add(textLabel)
        add(envLabel)
    }

    fun updateStatus(connected: Boolean, serverInfo: String = "", environment: uz.remote.flow.ssh.ServerEnvironment? = null) {
        if (environment != null) {
            envLabel.text = " ${environment.displayName} "
            envLabel.background = Color(environment.tagColorRgb)
            envLabel.foreground = Color.WHITE
            envLabel.border = BorderFactory.createEmptyBorder(1, 4, 1, 4)
            envLabel.isVisible = true
        } else {
            envLabel.isVisible = false
        }
        if (connected) {
            iconLabel.icon = RemoteFlowIcons.CLOUD_CONNECTED
            textLabel.text = "Connected"
            textLabel.foreground = JBColor(Color(16, 185, 129), Color(16, 185, 129))
            background = JBColor(Color(236, 253, 245), Color(6, 78, 59, 90))
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor(Color(167, 243, 208), Color(5, 150, 105, 180)), 1, true),
                JBUI.Borders.empty(1, 8, 1, 8)
            )
            toolTipText = if (serverInfo.isNotBlank()) "Connected to $serverInfo" else "Connected to remote server"
        } else {
            iconLabel.icon = RemoteFlowIcons.CLOUD_DISCONNECTED
            textLabel.text = "Disconnected"
            textLabel.foreground = JBColor.GRAY
            background = JBColor(Color(243, 244, 246), Color(45, 48, 50))
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor(Color(229, 231, 235), Color(60, 63, 65)), 1, true),
                JBUI.Borders.empty(1, 8, 1, 8)
            )
            toolTipText = if (serverInfo.isNotBlank()) "Disconnected ($serverInfo)" else "Disconnected from server"
        }
        revalidate()
        repaint()
    }

    fun setConnecting(serverInfo: String = "") {
        iconLabel.icon = RemoteFlowIcons.CLOUD_CONNECTING
        textLabel.text = "Connecting..."
        textLabel.foreground = JBColor(Color(245, 158, 11), Color(245, 158, 11))
        background = JBColor(Color(254, 243, 199), Color(120, 53, 15, 90))
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(JBColor(Color(253, 230, 138), Color(217, 119, 6, 180)), 1, true),
            JBUI.Borders.empty(1, 8, 1, 8)
        )
        toolTipText = "Connecting to $serverInfo..."
        revalidate()
        repaint()
    }
}
