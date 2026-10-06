package uz.remote.flow.memory

import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import uz.remote.flow.settings.RemoteFlowSettings
import uz.remote.flow.ssh.RemoteConnectionManager
import uz.remote.flow.ssh.ServerProfile
import uz.remote.flow.system.ServerStatsManager
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridLayout
import javax.swing.*

/**
 * Native IntelliJ Dialog to configure remote server memory allocation,
 * JVM heap options (-Xmx / -Xms), Node.js memory limits, and Docker limits.
 */
class RemoteMemoryTunerDialog(
    private val project: Project,
    private val profile: ServerProfile = RemoteFlowSettings.getInstance(project).activeProfile
) : DialogWrapper(project, true) {

    private val maxHeapField = JBTextField(profile.maxHeapSize.ifBlank { "2g" })
    private val initialHeapField = JBTextField(profile.initialHeapSize.ifBlank { "512m" })
    private val extraJvmField = JBTextField(profile.extraJvmArgs)
    private val nodeMemoryField = JBTextField(if (profile.nodeMemoryLimitMb > 0) profile.nodeMemoryLimitMb.toString() else "2048")
    private val dockerMemoryField = JBTextField(profile.dockerMemoryLimit)

    private val ramStatusLabel = JBLabel("Fetching remote server hardware RAM metrics...")
    private val ramProgressBar = JProgressBar(0, 100)

    init {
        title = "Remote Memory & Allocation Tuner — ${profile.name}"
        setOKButtonText("Apply Memory Settings")
        init()
        loadRemoteHardwareStats()
    }

    private fun loadRemoteHardwareStats() {
        val connMgr = RemoteConnectionManager.getInstance(project)
        if (!connMgr.isConnected) {
            ramStatusLabel.text = "Server is currently offline. Settings will take effect on next connect."
            ramProgressBar.value = 0
            ramProgressBar.isStringPainted = true
            ramProgressBar.string = "Offline"
            return
        }

        ServerStatsManager(project).fetchMetrics(
            onParsed = { metrics ->
                SwingUtilities.invokeLater {
                    val ramPct = metrics.ramPercent
                    ramStatusLabel.text = "Server RAM: ${metrics.ramText} (${ramPct}% in use) | CPU: ${metrics.cpuText} | Disk: ${metrics.diskText}"
                    ramProgressBar.value = ramPct
                    ramProgressBar.isStringPainted = true
                    ramProgressBar.string = "${metrics.ramText} (${ramPct}%)"
                    ramProgressBar.foreground = when {
                        ramPct > 85 -> JBColor(Color(239, 68, 68), Color(248, 113, 113))
                        ramPct > 70 -> JBColor(Color(245, 158, 11), Color(251, 191, 36))
                        else -> JBColor(Color(34, 197, 94), Color(74, 222, 128))
                    }
                }
            },
            onLog = {},
            onComplete = {}
        )
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel()
        root.layout = BoxLayout(root, BoxLayout.Y_AXIS)
        root.preferredSize = Dimension(540, 480)
        root.border = JBUI.Borders.empty(12)

        // 1. Hardware Overview Banner
        val hwPanel = JPanel(BorderLayout(0, 6))
        hwPanel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder("Remote Server Hardware Status"),
            JBUI.Borders.empty(8)
        )
        hwPanel.add(ramStatusLabel, BorderLayout.NORTH)
        ramProgressBar.preferredSize = Dimension(500, 18)
        hwPanel.add(ramProgressBar, BorderLayout.CENTER)
        root.add(hwPanel)
        root.add(Box.createVerticalStrut(10))

        // 2. JVM Heap Settings
        val jvmPanel = JPanel()
        jvmPanel.layout = BoxLayout(jvmPanel, BoxLayout.Y_AXIS)
        jvmPanel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder("JVM Heap Memory (Java, Kotlin, Gradle, Spring Boot)"),
            JBUI.Borders.empty(8)
        )

        // Max Heap Row
        val maxHeapBox = JPanel(BorderLayout(8, 0))
        val maxHeapLabel = JBLabel("Max Heap Size (-Xmx):")
        maxHeapLabel.font = maxHeapLabel.font.deriveFont(Font.BOLD)
        maxHeapBox.add(maxHeapLabel, BorderLayout.WEST)
        maxHeapBox.add(maxHeapField, BorderLayout.CENTER)
        jvmPanel.add(maxHeapBox)
        jvmPanel.add(Box.createVerticalStrut(4))

        // Preset buttons for Max Heap
        val maxPresetPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        listOf("512m", "1g", "2g", "4g", "8g", "16g").forEach { size ->
            val btn = JButton(size).apply {
                font = font.deriveFont(11f)
                addActionListener { maxHeapField.text = size }
            }
            maxPresetPanel.add(btn)
        }
        jvmPanel.add(maxPresetPanel)
        jvmPanel.add(Box.createVerticalStrut(8))

        // Initial Heap Row
        val initHeapBox = JPanel(BorderLayout(8, 0))
        val initHeapLabel = JBLabel("Initial Heap Size (-Xms):")
        initHeapBox.add(initHeapLabel, BorderLayout.WEST)
        initHeapBox.add(initialHeapField, BorderLayout.CENTER)
        jvmPanel.add(initHeapBox)
        jvmPanel.add(Box.createVerticalStrut(4))

        val initPresetPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        listOf("256m", "512m", "1g", "2g").forEach { size ->
            val btn = JButton(size).apply {
                font = font.deriveFont(11f)
                addActionListener { initialHeapField.text = size }
            }
            initPresetPanel.add(btn)
        }
        jvmPanel.add(initPresetPanel)
        jvmPanel.add(Box.createVerticalStrut(8))

        // Extra JVM Args
        val extraBox = JPanel(BorderLayout(8, 0))
        extraBox.add(JBLabel("Extra JVM Flags:"), BorderLayout.WEST)
        extraJvmField.emptyText.text = "e.g. -XX:+UseG1GC -XX:MaxMetaspaceSize=512m"
        extraBox.add(extraJvmField, BorderLayout.CENTER)
        jvmPanel.add(extraBox)

        root.add(jvmPanel)
        root.add(Box.createVerticalStrut(10))

        // 3. Other Runtimes (Node.js & Docker)
        val otherPanel = JPanel(GridLayout(2, 2, 8, 8))
        otherPanel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder("Node.js & Docker Limits"),
            JBUI.Borders.empty(8)
        )
        otherPanel.add(JBLabel("Node.js Max Old Space (MB):"))
        otherPanel.add(nodeMemoryField)
        otherPanel.add(JBLabel("Docker Memory Limit (e.g. 4g):"))
        otherPanel.add(dockerMemoryField)
        dockerMemoryField.emptyText.text = "e.g. 4g or leave blank"
        root.add(otherPanel)

        return root
    }

    override fun doOKAction() {
        profile.maxHeapSize = maxHeapField.text.trim()
        profile.initialHeapSize = initialHeapField.text.trim()
        profile.extraJvmArgs = extraJvmField.text.trim()
        profile.nodeMemoryLimitMb = nodeMemoryField.text.trim().toIntOrNull() ?: 2048
        profile.dockerMemoryLimit = dockerMemoryField.text.trim()

        RemoteConnectionManager.getInstance(project).notifyUser(
            "Remote Memory Configured",
            "Updated remote memory for '${profile.name}': -Xmx${profile.maxHeapSize}, -Xms${profile.initialHeapSize}",
            NotificationType.INFORMATION
        )
        super.doOKAction()
    }
}
