package uz.remote.flow.ui

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.IdeBorderFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import uz.remote.flow.settings.RemoteFlowConfigurable
import uz.remote.flow.settings.RemoteFlowSettings
import uz.remote.flow.settings.ServerProfileEditDialog
import uz.remote.flow.ssh.ForwardDirection
import uz.remote.flow.ssh.PortMapping
import uz.remote.flow.ssh.RemoteConnectionListener
import uz.remote.flow.ssh.RemoteConnectionManager
import uz.remote.flow.ssh.ServerProfile
import uz.remote.flow.sync.FastSyncManager
import uz.remote.flow.system.ServerStatsManager
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.datatransfer.StringSelection
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.swing.*
import javax.swing.table.DefaultTableModel

class RemoteFlowToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = RemoteFlowMainPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

class RemoteFlowMainPanel(private val project: Project) : JPanel(BorderLayout(0, 4)), Disposable {

    private val settings = RemoteFlowSettings.getInstance(project)
    private val connectionManager = RemoteConnectionManager.getInstance(project)
    private val syncManager = FastSyncManager(project)
    private val statsManager = ServerStatsManager(project)

    private val terminalConsoleView: ConsoleView = TextConsoleBuilderFactory.getInstance().createBuilder(project).console

    // Header Controls
    private val profileComboBox = JComboBox<ServerProfile>()
    private val btnConnectToggle = JButton("Connect", AllIcons.Actions.Execute)
    private val connectionBadge = ConnectionStatusBadge()
    private val apiHealthLabel = JLabel("API: --")
    private val btnSettings = JButton("Settings", AllIcons.General.Settings)

    // Remote Git Status Labels
    private val lblGitBranch = JLabel("🌿 Branch: -")
    private val lblGitCommit = JLabel("")
    private val lblGitStatus = JLabel("")

    // Remote Java and server overview label
    private val lblServerJava = JLabel("-")

    // Remote Files Explorer Panel & Unified Logger
    val filesPanel = RemoteFileExplorerPanel(project)
    private val tabbedPane = JBTabbedPane()
    private val logService = uz.remote.flow.logging.RemoteFlowLogService.getInstance(project)

    // Run / Debug tab controls
    private val runCommandField = JBTextField()
    private val debugCommandField = JBTextField()

    // Terminal tab controls
    private val terminalInputField = JBTextField()

    // Resource Meters
    private val cpuBar = JProgressBar(0, 100)
    private val cpuLabel = JLabel("0% (idle)")
    private val ramBar = JProgressBar(0, 100)
    private val ramLabel = JLabel("0 GB / 0 GB (0%)")
    private val diskBar = JProgressBar(0, 100)
    private val diskLabel = JLabel("0 GB / 0 GB (0%)")

    // Hardware Resource Monitor Controls
    private val monitorModeBox = JComboBox(arrayOf("⚡ Real-time (3s)", "⏱ Real-time (5s)", "🔍 Manual (On demand)", "🚫 Off (Disabled)"))
    private val monitorStatusLabel = JLabel("● Active")
    private val btnRefreshStats = JButton("Refresh", AllIcons.Actions.Refresh)
    private var monitorScheduledTask: ScheduledFuture<*>? = null
    private val monitorExecutor = AppExecutorUtil.getAppScheduledExecutorService()
    private var isUpdatingMonitorUi = false

    // Per-Core CPU Controls
    private val coresGridPanel = JPanel(GridLayout(0, 2, 8, 3))
    private val coresWrapperPanel = JPanel(BorderLayout(0, 4))
    private val lblCoresHeader = JLabel("▼ CPU Cores Breakdown (0 Cores)")
    private val coreBars = mutableListOf<Pair<JProgressBar, JLabel>>()
    private var areCoresExpanded = true
    private val ansiDecoder = com.intellij.execution.process.AnsiEscapeDecoder()

    // Overview info labels
    private val lblServerHost = JLabel("-")
    private val lblServerUser = JLabel("-")
    private val lblServerRemoteDir = JLabel("-")

    // Tables
    private lateinit var portsTableModel: DefaultTableModel
    private lateinit var portsTable: JBTable

    init {
        border = JBUI.Borders.empty(4)

        // Setup Header Control Bar
        add(createHeaderPanel(), BorderLayout.NORTH)

        // 5 Logical Tabs with native IntelliJ AllIcons
        tabbedPane.addTab("Dashboard", AllIcons.Nodes.Services, createDashboardTab())
        tabbedPane.addTab("Files", AllIcons.Nodes.Folder, filesPanel)
        tabbedPane.addTab("Run & Debug", AllIcons.Actions.Execute, createRunDebugTab())
        tabbedPane.addTab("Port Forwarding", AllIcons.General.Web, createPortsTab())
        tabbedPane.addTab("Terminal", RemoteFlowIcons.TERMINAL, createTerminalTab())

        add(tabbedPane, BorderLayout.CENTER)

        // Listen for connection and profile events across the IDE
        project.messageBus.connect().subscribe(RemoteConnectionListener.TOPIC, object : RemoteConnectionListener {
            override fun connectionStateChanged(connected: Boolean, profile: ServerProfile) {
                ApplicationManager.getApplication().invokeLater {
                    updateConnectionStateUi(connected)
                    updateOverviewSummary(settings.activeProfile)
                }
            }

            override fun profileChanged(profile: ServerProfile) {
                ApplicationManager.getApplication().invokeLater {
                    refreshProfileComboBox()
                    loadProfileData(settings.activeProfile)
                }
            }
        })

        monitorModeBox.addActionListener {
            if (isUpdatingMonitorUi) return@addActionListener
            val selected = monitorModeBox.selectedIndex
            val p = settings.activeProfileOrNull
            val modeStr = when (selected) {
                0 -> "REALTIME_3S"
                1 -> "REALTIME_5S"
                2 -> "MANUAL"
                else -> "OFF"
            }
            if (p != null) {
                p.monitorMode = modeStr
            }
            restartMonitorScheduler()
        }

        refreshProfileComboBox()
        loadProfileData(settings.activeProfile)
        updateConnectionStateUi(connectionManager.isConnected)
    }

    override fun dispose() {
        stopMonitorScheduler()
        filesPanel.dispose()
    }

    fun selectTab(titlePrefix: String) {
        for (i in 0 until tabbedPane.tabCount) {
            if (tabbedPane.getTitleAt(i).contains(titlePrefix, ignoreCase = true)) {
                tabbedPane.selectedIndex = i
                break
            }
        }
    }

    private fun createHeaderPanel(): JPanel {
        val header = JPanel(BorderLayout(6, 0))
        header.border = IdeBorderFactory.createTitledBorder("Active Server Control", false)

        val left = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2))
        left.add(JBLabel("Server:"))
        profileComboBox.preferredSize = Dimension(190, 26)
        profileComboBox.addActionListener {
            val selected = profileComboBox.selectedItem as? ServerProfile
            if (selected != null && profileComboBox.selectedIndex != settings.activeProfileIndex) {
                settings.activeProfileIndex = profileComboBox.selectedIndex
                loadProfileData(selected)
                project.messageBus.syncPublisher(RemoteConnectionListener.TOPIC).profileChanged(selected)
            }
        }
        left.add(profileComboBox)

        btnConnectToggle.font = btnConnectToggle.font.deriveFont(Font.BOLD)
        btnConnectToggle.addActionListener {
            if (connectionManager.isConnected) {
                disconnectSSH()
            } else {
                connectSSH()
            }
        }
        left.add(btnConnectToggle)
        left.add(connectionBadge)

        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 2))

        val btnLogsHeader = JButton("Logs", AllIcons.Nodes.LogFolder)
        btnLogsHeader.toolTipText = "Open unified Remote Flow logs window"
        btnLogsHeader.addActionListener {
            com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow("Remote Flow Log")?.show(null)
        }
        right.add(btnLogsHeader)

        btnSettings.toolTipText = "Configure servers, add new profiles and settings"
        btnSettings.addActionListener {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, RemoteFlowConfigurable::class.java)
        }
        right.add(btnSettings)

        header.add(left, BorderLayout.WEST)
        header.add(right, BorderLayout.EAST)
        return header
    }

    private fun refreshProfileComboBox() {
        profileComboBox.removeAllItems()
        for (profile in settings.profiles) {
            profileComboBox.addItem(profile)
        }
        if (settings.profiles.isNotEmpty()) {
            val idx = settings.activeProfileIndex.coerceIn(settings.profiles.indices)
            profileComboBox.selectedIndex = idx
            loadProfileData(settings.profiles[idx])
        } else {
            lblServerHost.text = "No server"
            lblServerUser.text = "No server"
            lblServerRemoteDir.text = "No server"
            lblServerJava.text = "-"
            runCommandField.text = ""
            debugCommandField.text = ""
        }
    }

    private fun loadProfileData(p: ServerProfile) {
        runCommandField.text = p.runCommand
        debugCommandField.text = p.debugCommand
        lblServerJava.text = if (p.javaHome.isNotBlank()) p.javaHome else "System Default"
        updateOverviewSummary(p)
        updatePortsTableData()

        isUpdatingMonitorUi = true
        try {
            val idx = when (p.monitorMode) {
                "REALTIME_5S" -> 1
                "MANUAL" -> 2
                "OFF" -> 3
                else -> 0
            }
            monitorModeBox.selectedIndex = idx
        } finally {
            isUpdatingMonitorUi = false
        }
        restartMonitorScheduler()
    }

    private fun updateOverviewSummary(p: ServerProfile) {
        if (p.host.isBlank()) {
            lblServerHost.text = "No server configured"
            lblServerUser.text = "-"
            lblServerRemoteDir.text = "-"
            lblServerJava.text = "-"
        } else {
            lblServerHost.text = p.host + ":" + p.port
            lblServerUser.text = p.user + " (" + p.authType.name + ")"
            lblServerRemoteDir.text = p.remoteProjectPath
            lblServerJava.text = if (p.javaHome.isNotBlank()) p.javaHome else "System Default"
        }
    }

    private fun updateConnectionStateUi(connected: Boolean) {
        val p = settings.activeProfile
        connectionBadge.updateStatus(connected, p.name)
        if (connected) {
            btnConnectToggle.text = "Disconnect"
            btnConnectToggle.icon = AllIcons.Actions.Suspend
            btnConnectToggle.foreground = JBColor.RED
            checkRemoteGitStatus()
        } else {
            btnConnectToggle.text = "Connect"
            btnConnectToggle.icon = AllIcons.Actions.Execute
            btnConnectToggle.foreground = JBColor.foreground()
            lblGitBranch.text = "Branch: -"
            lblGitCommit.text = ""
            lblGitStatus.text = ""
        }
        updatePortsTableData()
        restartMonitorScheduler()
    }

    // TAB 1: Dashboard
    private fun createDashboardTab(): JPanel {
        val root = JPanel(BorderLayout(0, 8))
        root.border = JBUI.Borders.empty(6)

        val top = JPanel()
        top.layout = BoxLayout(top, BoxLayout.Y_AXIS)

        // Summary Card
        val summaryCard = JPanel(GridBagLayout())
        summaryCard.border = IdeBorderFactory.createTitledBorder("Active Server Information", false)
        val gbc = GridBagConstraints()
        gbc.insets = JBUI.insets(2, 6, 2, 6)
        gbc.anchor = GridBagConstraints.WEST

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0; summaryCard.add(JBLabel("Host Address:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0; summaryCard.add(lblServerHost, gbc)

        gbc.gridx = 2; gbc.gridy = 0; gbc.weightx = 0.0; summaryCard.add(JBLabel("User & Auth:"), gbc)
        gbc.gridx = 3; gbc.weightx = 1.0; summaryCard.add(lblServerUser, gbc)

        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0; summaryCard.add(JBLabel("Remote Directory:"), gbc)
        gbc.gridx = 1; gbc.gridwidth = 2; gbc.weightx = 1.0; summaryCard.add(lblServerRemoteDir, gbc)

        val btnOpenDir = JButton("Browse Files", AllIcons.Nodes.Folder)
        btnOpenDir.toolTipText = "Browse all files in this remote directory"
        btnOpenDir.addActionListener {
            selectTab("Files")
            filesPanel.loadDirectory(settings.activeProfile.remoteProjectPath)
        }
        gbc.gridx = 3; gbc.gridwidth = 1; gbc.weightx = 0.0; summaryCard.add(btnOpenDir, gbc)

        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0.0; summaryCard.add(JBLabel("Remote Java:"), gbc)
        gbc.gridx = 1; gbc.gridwidth = 3; gbc.weightx = 1.0; summaryCard.add(lblServerJava, gbc)
        gbc.gridwidth = 1

        top.add(summaryCard)
        top.add(Box.createVerticalStrut(6))

        // Resource Meters Card (Collapsible)
        val metersContent = JPanel(BorderLayout(0, 4))
        metersContent.border = JBUI.Borders.empty(4, 6)

        val metersToolbar = JPanel(BorderLayout(6, 0))
        metersToolbar.border = JBUI.Borders.empty(0, 0, 4, 0)

        val modePanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        modePanel.add(JBLabel("Mode:"))
        monitorModeBox.preferredSize = Dimension(170, 26)
        modePanel.add(monitorModeBox)
        monitorStatusLabel.font = monitorStatusLabel.font.deriveFont(Font.BOLD, 11f)
        modePanel.add(monitorStatusLabel)

        val btnPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))
        val btnTaskManager = JButton("Task Manager", AllIcons.Nodes.Services)
        btnTaskManager.font = btnTaskManager.font.deriveFont(Font.BOLD)
        btnTaskManager.toolTipText = "Open Remote Task Manager (Top CPU & Memory processes)"
        btnTaskManager.addActionListener {
            val p = settings.activeProfileOrNull ?: return@addActionListener
            if (!connectionManager.isConnected) {
                Messages.showWarningDialog(project, "Not connected to server! Please click 'Connect' first.", "Remote Task Manager")
                return@addActionListener
            }
            uz.remote.flow.system.RemoteTaskManagerDialog(project, p).show()
        }
        btnPanel.add(btnTaskManager)

        btnRefreshStats.font = btnRefreshStats.font.deriveFont(Font.BOLD)
        btnRefreshStats.toolTipText = "Refresh resource usage now"
        btnRefreshStats.addActionListener { checkServerResources(silent = false) }
        btnPanel.add(btnRefreshStats)

        metersToolbar.add(modePanel, BorderLayout.WEST)
        metersToolbar.add(btnPanel, BorderLayout.EAST)
        metersContent.add(metersToolbar, BorderLayout.NORTH)

        val metersGrid = JPanel(GridLayout(3, 1, 0, 4))
        metersGrid.add(createMeterRow("CPU Usage:", cpuBar, cpuLabel))
        metersGrid.add(createMeterRow("RAM Usage:", ramBar, ramLabel))
        metersGrid.add(createMeterRow("Disk (/ root):", diskBar, diskLabel))

        coresWrapperPanel.isOpaque = false
        coresWrapperPanel.border = JBUI.Borders.empty(4, 6, 2, 6)
        coresWrapperPanel.isVisible = false

        lblCoresHeader.font = lblCoresHeader.font.deriveFont(Font.BOLD, 11f)
        lblCoresHeader.foreground = JBColor.GRAY
        lblCoresHeader.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        lblCoresHeader.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                areCoresExpanded = !areCoresExpanded
                lblCoresHeader.text = if (areCoresExpanded) "▼ CPU Cores Breakdown (${coreBars.size} Cores):" else "▶ CPU Cores Breakdown (${coreBars.size} Cores)"
                coresGridPanel.isVisible = areCoresExpanded
                coresWrapperPanel.revalidate()
                coresWrapperPanel.repaint()
            }
        })
        coresWrapperPanel.add(lblCoresHeader, BorderLayout.NORTH)
        coresWrapperPanel.add(coresGridPanel, BorderLayout.CENTER)

        val metersCenter = JPanel()
        metersCenter.layout = BoxLayout(metersCenter, BoxLayout.Y_AXIS)
        metersCenter.add(metersGrid)
        metersCenter.add(coresWrapperPanel)
        metersContent.add(metersCenter, BorderLayout.CENTER)

        val metersCard = CollapsibleCard(
            title = "Hardware Resource Monitor",
            content = metersContent,
            initiallyExpanded = true
        )
        top.add(metersCard)
        top.add(Box.createVerticalStrut(6))

        // Quick Actions Grid (Collapsible)
        val quickActionGrid = JPanel(GridLayout(0, 3, 6, 6))
        quickActionGrid.border = JBUI.Borders.empty(4, 6, 6, 6)

        val btnQuickSync = JButton("Sync Server", AllIcons.Actions.Upload)
        btnQuickSync.toolTipText = "Upload modified project files to the active server"
        btnQuickSync.addActionListener { syncFiles() }
        quickActionGrid.add(btnQuickSync)

        val btnDryRun = JButton("Preview Diff", AllIcons.Actions.Diff)
        btnDryRun.toolTipText = "Compare with remote server (Dry-Run Diff)"
        btnDryRun.addActionListener {
            syncManager.previewDryRun(settings.activeProfile, { log(it) }, {})
        }
        quickActionGrid.add(btnDryRun)

        val btnSyncAll = JButton("Sync All Servers", AllIcons.Actions.Commit)
        btnSyncAll.toolTipText = "Synchronize all servers in parallel"
        btnSyncAll.addActionListener { syncAllServers() }
        quickActionGrid.add(btnSyncAll)

        val btnRemoteConfigs = JButton("Remote Configs", AllIcons.General.Settings)
        btnRemoteConfigs.toolTipText = "Manage remote .env, application.yml, and gradle.properties"
        btnRemoteConfigs.addActionListener { openRemoteConfigManager() }
        quickActionGrid.add(btnRemoteConfigs)

        val btnQuickTaskManager = JButton("Task Manager", AllIcons.Nodes.Services)
        btnQuickTaskManager.toolTipText = "Inspect remote processes, top CPU/RAM, and kill unresponsive processes"
        btnQuickTaskManager.addActionListener {
            val p = settings.activeProfileOrNull ?: return@addActionListener
            if (!connectionManager.isConnected) {
                Messages.showWarningDialog(project, "Not connected to server! Please click 'Connect' first.", "Remote Task Manager")
                return@addActionListener
            }
            uz.remote.flow.system.RemoteTaskManagerDialog(project, p).show()
        }
        quickActionGrid.add(btnQuickTaskManager)

        val btnOpenTerminalQuick = JButton("SSH Terminal", RemoteFlowIcons.TERMINAL)
        btnOpenTerminalQuick.toolTipText = "Open SSH session in IntelliJ terminal"
        btnOpenTerminalQuick.addActionListener {
            val p = settings.activeProfileOrNull ?: return@addActionListener
            uz.remote.flow.terminal.RemoteTerminalHelper.openTerminal(project, p)
        }
        quickActionGrid.add(btnOpenTerminalQuick)

        val quickActionCard = CollapsibleCard(
            title = "Quick Operations",
            content = quickActionGrid,
            initiallyExpanded = true
        )
        top.add(quickActionCard)
        top.add(Box.createVerticalStrut(6))
        top.add(createGitStatusCard())
        top.add(Box.createVerticalStrut(6))

        val contentWrapper = JPanel(BorderLayout())
        contentWrapper.add(top, BorderLayout.NORTH)

        val scrollPane = JBScrollPane(contentWrapper)
        scrollPane.border = null
        root.add(scrollPane, BorderLayout.CENTER)
        return root
    }

    private fun createMeterRow(name: String, bar: JProgressBar, label: JLabel): JPanel {
        val row = JPanel(BorderLayout(12, 0))
        row.border = JBUI.Borders.empty(2, 6)

        val nameLabel = JLabel(name)
        nameLabel.preferredSize = Dimension(90, 24)
        nameLabel.font = nameLabel.font.deriveFont(Font.BOLD)

        bar.isStringPainted = true
        bar.value = 0
        bar.preferredSize = Dimension(250, 22)

        label.preferredSize = Dimension(180, 24)

        row.add(nameLabel, BorderLayout.WEST)
        row.add(bar, BorderLayout.CENTER)
        row.add(label, BorderLayout.EAST)
        return row
    }

    // TAB 2: Run & Debug
    private fun createRunDebugTab(): JPanel {
        val panel = JPanel(BorderLayout(0, 6))
        panel.border = JBUI.Borders.empty(6)

        val top = JPanel()
        top.layout = BoxLayout(top, BoxLayout.Y_AXIS)

        // Run Card
        val runCard = JPanel(GridBagLayout())
        runCard.border = IdeBorderFactory.createTitledBorder("Remote Build & Run", false)
        val gbc = GridBagConstraints()
        gbc.insets = JBUI.insets(3, 4, 3, 4)
        gbc.anchor = GridBagConstraints.WEST
        gbc.fill = GridBagConstraints.HORIZONTAL

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0; runCard.add(JBLabel("Run Command:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0; runCard.add(runCommandField, gbc)

        val runBtnRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2))
        val btnRun = JButton("Remote Run (Sync & Build & Run)", AllIcons.Actions.Execute)
        btnRun.font = btnRun.font.deriveFont(Font.BOLD)
        btnRun.foreground = JBColor(Color(16, 185, 129), Color(16, 185, 129))
        btnRun.addActionListener { executeRemoteRun() }
        runBtnRow.add(btnRun)

        val btnStop = JButton("Stop App", AllIcons.Actions.Suspend)
        btnStop.foreground = JBColor.RED
        btnStop.addActionListener { executeRemoteStop() }
        runBtnRow.add(btnStop)

        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 2; gbc.weightx = 1.0
        runCard.add(runBtnRow, gbc)
        gbc.gridwidth = 1

        // Debug Card
        val debugCard = JPanel(GridBagLayout())
        debugCard.border = IdeBorderFactory.createTitledBorder("Remote Debug (Port 5005)", false)

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0; debugCard.add(JBLabel("Debug Command:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0; debugCard.add(debugCommandField, gbc)

        val debugBtnRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2))
        val btnDebug = JButton("Remote Debug (Start in Debug Mode)", AllIcons.Actions.StartDebugger)
        btnDebug.font = btnDebug.font.deriveFont(Font.BOLD)
        btnDebug.foreground = JBColor(Color(245, 158, 11), Color(245, 158, 11))
        btnDebug.addActionListener { executeRemoteDebug() }
        debugBtnRow.add(btnDebug)

        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 2; gbc.weightx = 1.0
        debugCard.add(debugBtnRow, gbc)
        gbc.gridwidth = 1

        top.add(runCard)
        top.add(Box.createVerticalStrut(4))
        top.add(debugCard)

        // Log & Status Card in Center (all logs go to bottom "Remote Flow Log" window)
        val logInfoPanel = JPanel(BorderLayout(0, 8))
        logInfoPanel.border = IdeBorderFactory.createTitledBorder("Execution Logs", false)

        val cardContent = JPanel()
        cardContent.layout = BoxLayout(cardContent, BoxLayout.Y_AXIS)
        cardContent.border = JBUI.Borders.empty(12)

        val lblNotice = JBLabel("<html><b>All sync, build, and application logs are displayed in real time in the bottom 'Remote Flow Log' window.</b><br/>Search, filter by server, and clear logs are available there.</html>")
        lblNotice.font = lblNotice.font.deriveFont(Font.PLAIN, 12f)
        cardContent.add(lblNotice)
        cardContent.add(Box.createVerticalStrut(12))

        val btnOpenBottomLog = JButton("Open 'Remote Flow Log' Window", AllIcons.Nodes.LogFolder)
        btnOpenBottomLog.font = btnOpenBottomLog.font.deriveFont(Font.BOLD, 12f)
        btnOpenBottomLog.foreground = JBColor(Color(16, 185, 129), Color(16, 185, 129))
        btnOpenBottomLog.preferredSize = Dimension(320, 36)
        btnOpenBottomLog.toolTipText = "Open unified Remote Flow logs window"
        btnOpenBottomLog.addActionListener {
            logService.showLogWindow()
        }
        val btnRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        btnRow.add(btnOpenBottomLog)
        cardContent.add(btnRow)
        cardContent.add(Box.createVerticalStrut(16))

        val helpBox = JPanel(GridLayout(3, 1, 0, 6))
        helpBox.border = IdeBorderFactory.createTitledBorder("Quick Endpoints & Debug Info", false)
        helpBox.add(JBLabel("• JVM Debug: localhost:5005 (Connect via IntelliJ 'Remote JVM Debug' configuration)"))
        helpBox.add(JBLabel("• Web Service: http://localhost:8080 (Accessible in browser via forwarded ports)"))
        helpBox.add(JBLabel("• Health Check: http://localhost:8080/actuator/health"))
        cardContent.add(helpBox)

        logInfoPanel.add(cardContent, BorderLayout.NORTH)

        panel.add(top, BorderLayout.NORTH)
        panel.add(logInfoPanel, BorderLayout.CENTER)
        return panel
    }

    // TAB 4: Port Forwarding
    private fun createPortsTab(): JPanel {
        val panel = JPanel(BorderLayout(0, 8))
        panel.border = JBUI.Borders.empty(6)

        // 1. TOP & MAIN: Forwarded Ports Box
        val portsBox = JPanel(BorderLayout(0, 6))
        portsBox.border = BorderFactory.createCompoundBorder(
            IdeBorderFactory.createTitledBorder("Forwarded Ports Management (Bi-directional SSH Tunnels)", false),
            JBUI.Borders.empty(4, 6, 6, 6)
        )

        val portHeader = JPanel(BorderLayout(6, 0))
        val portHeaderLeft = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        val lblTunnelsSummary = JBLabel("SSH Tunnels (Local ⇄ Host)")
        lblTunnelsSummary.font = lblTunnelsSummary.font.deriveFont(Font.BOLD, 12f)
        portHeaderLeft.add(lblTunnelsSummary)

        val portHeaderRight = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))
        val btnAddPort = JButton("Forward New Port...", AllIcons.General.Add)
        btnAddPort.toolTipText = "Add new port forward (Local -> Host or Host -> Local)"
        btnAddPort.addActionListener { showAddPortDialog() }
        portHeaderRight.add(btnAddPort)

        val btnRestartTunnels = JButton("Restart All Tunnels", AllIcons.Actions.Restart)
        btnRestartTunnels.toolTipText = "Restart all active port forwarding tunnels"
        btnRestartTunnels.addActionListener { restartTunnels() }
        portHeaderRight.add(btnRestartTunnels)

        portHeader.add(portHeaderLeft, BorderLayout.WEST)
        portHeader.add(portHeaderRight, BorderLayout.EAST)
        portsBox.add(portHeader, BorderLayout.NORTH)

        val columns = arrayOf("Status", "Direction", "Service Name", "Local Endpoint", "Remote Endpoint", "Target / Action")
        portsTableModel = object : DefaultTableModel(columns, 0) {
            override fun isCellEditable(row: Int, column: Int): Boolean = false
        }
        portsTable = JBTable(portsTableModel)
        portsTable.rowHeight = 28
        portsTable.columnModel.getColumn(0).preferredWidth = 85
        portsTable.columnModel.getColumn(1).preferredWidth = 150
        portsTable.columnModel.getColumn(2).preferredWidth = 150
        portsTable.columnModel.getColumn(3).preferredWidth = 120
        portsTable.columnModel.getColumn(4).preferredWidth = 120
        portsTable.columnModel.getColumn(5).preferredWidth = 160

        // Custom Cell Renderers for Status & Direction
        portsTable.columnModel.getColumn(0).cellRenderer = object : javax.swing.table.DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                table: JTable?, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
            ): Component {
                val c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column) as JLabel
                val str = value?.toString() ?: ""
                if (!isSelected) {
                    if (str.contains("Active") || str.contains("Forwarded")) {
                        c.foreground = JBColor(Color(16, 185, 129), Color(16, 185, 129))
                    } else {
                        c.foreground = JBColor.GRAY
                    }
                }
                c.font = c.font.deriveFont(Font.BOLD)
                return c
            }
        }

        portsTable.columnModel.getColumn(1).cellRenderer = object : javax.swing.table.DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                table: JTable?, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
            ): Component {
                val c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column) as JLabel
                val str = value?.toString() ?: ""
                if (!isSelected) {
                    if (str.contains("Host ➔ 💻 Local")) {
                        c.foreground = JBColor(Color(147, 51, 234), Color(192, 132, 252))
                    } else {
                        c.foreground = JBColor(Color(2, 132, 199), Color(56, 189, 248))
                    }
                }
                c.font = c.font.deriveFont(Font.BOLD)
                return c
            }
        }

        // Double click to open web or copy
        portsTable.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val row = portsTable.selectedRow
                    val profile = settings.activeProfileOrNull ?: return
                    if (row in profile.forwardedPorts.indices) {
                        val p = profile.forwardedPorts[row]
                        val port = if (p.direction == ForwardDirection.LOCAL_TO_REMOTE) p.localPort else p.remotePort
                        if (port in listOf(80, 443, 8080, 8000, 3000, 5173, 15672, 9000, 8081)) {
                            try {
                                Desktop.getDesktop().browse(URI("http://localhost:$port"))
                            } catch (_: Exception) {}
                        } else {
                            val sel = StringSelection("localhost:$port")
                            Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
                            connectionManager.notifyUser("Remote Flow", "Address copied to clipboard: localhost:$port")
                        }
                    }
                }
            }
        })

        // Right click popup menu
        val popupMenu = JPopupMenu()
        val itemOpenBrowser = JMenuItem("Open in Browser (localhost:port)", AllIcons.General.Web)
        itemOpenBrowser.addActionListener {
            val r = portsTable.selectedRow
            val profile = settings.activeProfileOrNull ?: return@addActionListener
            if (r in profile.forwardedPorts.indices) {
                val p = profile.forwardedPorts[r]
                val port = if (p.direction == ForwardDirection.LOCAL_TO_REMOTE) p.localPort else p.remotePort
                try {
                    Desktop.getDesktop().browse(URI("http://localhost:$port"))
                } catch (_: Exception) {}
            }
        }
        val itemCopyLocal = JMenuItem("Copy Local Address", AllIcons.Actions.Copy)
        itemCopyLocal.addActionListener {
            val r = portsTable.selectedRow
            val profile = settings.activeProfileOrNull ?: return@addActionListener
            if (r in profile.forwardedPorts.indices) {
                val p = profile.forwardedPorts[r]
                val sel = StringSelection("localhost:${p.localPort}")
                Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
            }
        }
        val itemRestartTunnel = JMenuItem("Restart This Tunnel", AllIcons.Actions.Restart)
        itemRestartTunnel.addActionListener {
            val r = portsTable.selectedRow
            val profile = settings.activeProfileOrNull ?: return@addActionListener
            if (r in profile.forwardedPorts.indices) {
                val p = profile.forwardedPorts[r]
                connectionManager.stopSingleForward(p)
                connectionManager.startSingleForward(p)
                updatePortsTableData()
            }
        }
        val itemRemove = JMenuItem("Remove Port (Delete)", AllIcons.General.Remove)
        itemRemove.addActionListener { removeSelectedPort() }

        popupMenu.add(itemOpenBrowser)
        popupMenu.add(itemCopyLocal)
        popupMenu.addSeparator()
        popupMenu.add(itemRestartTunnel)
        popupMenu.add(itemRemove)

        portsTable.componentPopupMenu = popupMenu
        portsTable.addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(e: java.awt.event.KeyEvent) {
                if (e.keyCode == java.awt.event.KeyEvent.VK_DELETE || e.keyCode == java.awt.event.KeyEvent.VK_BACK_SPACE) {
                    removeSelectedPort()
                }
            }
        })
        portsBox.add(JBScrollPane(portsTable), BorderLayout.CENTER)

        panel.add(portsBox, BorderLayout.CENTER)
        panel.add(createPortKillerPanel(), BorderLayout.SOUTH)
        return panel
    }

    // TAB 5: Terminal
    private fun createTerminalTab(): JPanel {
        val panel = JPanel(BorderLayout(0, 6))
        panel.border = JBUI.Borders.empty(6)

        val topBar = JPanel(BorderLayout(8, 0))
        topBar.border = IdeBorderFactory.createTitledBorder("Interactive SSH Shell & Quick Commands", false)

        val inputPanel = JPanel(BorderLayout(6, 0))
        terminalInputField.font = Font("Monospaced", Font.PLAIN, 13)
        terminalInputField.toolTipText = "Enter command and press Enter (e.g. htop, ls -la, df -h, ps aux)"
        terminalInputField.addActionListener { executeTerminalInput() }

        val btnExec = JButton("Execute", AllIcons.Actions.Execute)
        btnExec.addActionListener { executeTerminalInput() }

        inputPanel.add(JBLabel("remote:~# "), BorderLayout.WEST)
        inputPanel.add(terminalInputField, BorderLayout.CENTER)
        inputPanel.add(btnExec, BorderLayout.EAST)

        val btnOpenIdeaTerminal = JButton("Open in Terminal", RemoteFlowIcons.TERMINAL)
        btnOpenIdeaTerminal.font = btnOpenIdeaTerminal.font.deriveFont(Font.BOLD)
        btnOpenIdeaTerminal.foreground = JBColor(Color(16, 185, 129), Color(16, 185, 129))
        btnOpenIdeaTerminal.toolTipText = "Open terminal in IntelliJ IDEA and connect via SSH"
        btnOpenIdeaTerminal.addActionListener {
            val p = settings.activeProfileOrNull ?: return@addActionListener
            uz.remote.flow.terminal.RemoteTerminalHelper.openTerminal(project, p)
        }

        val btnLaunchExternal = JButton("External Terminal", AllIcons.Actions.Forward)
        btnLaunchExternal.toolTipText = "Launch SSH session in external terminal window"
        btnLaunchExternal.addActionListener {
            val p = settings.activeProfileOrNull ?: return@addActionListener
            uz.remote.flow.terminal.RemoteTerminalHelper.openExternalTerminal(project, p)
        }

        val btnPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))
        btnPanel.add(btnOpenIdeaTerminal)
        btnPanel.add(btnLaunchExternal)

        topBar.add(inputPanel, BorderLayout.CENTER)
        topBar.add(btnPanel, BorderLayout.EAST)

        panel.add(topBar, BorderLayout.NORTH)
        panel.add(terminalConsoleView.component, BorderLayout.CENTER)
        return panel
    }

    // Handlers
    private fun executeTerminalInput() {
        val cmd = terminalInputField.text.trim()
        if (cmd.isBlank()) return

        if (!connectionManager.isConnected) {
            terminalConsoleView.print("[ERROR] Not connected to server! Please click 'Connect' first.\n", ConsoleViewContentType.ERROR_OUTPUT)
            return
        }

        terminalConsoleView.print("remote:~# $cmd\n", ConsoleViewContentType.USER_INPUT)
        terminalInputField.text = ""

        val p = settings.activeProfile
        val javaPrefix = uz.remote.flow.ssh.resolveJavaEnvPrefix(p.javaHome)
        val finalCmd = if (javaPrefix.isNotBlank()) "${javaPrefix}$cmd" else cmd

        connectionManager.executeRemoteCommand(
            cmd = finalCmd,
            workingDir = p.remoteProjectPath,
            onOutput = { line ->
                ApplicationManager.getApplication().invokeLater {
                    if (line.contains("\u001B")) {
                        ansiDecoder.escapeText(line, com.intellij.execution.process.ProcessOutputTypes.STDOUT) { chunk, outputType ->
                            val type = ConsoleViewContentType.getConsoleViewType(outputType)
                            terminalConsoleView.print(chunk, type)
                        }
                    } else {
                        terminalConsoleView.print(line, ConsoleViewContentType.NORMAL_OUTPUT)
                    }
                }
            },
            onComplete = { code ->
                ApplicationManager.getApplication().invokeLater {
                    terminalConsoleView.print("[Exit Code: $code]\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                }
            }
        )
    }

    private fun openExternalTerminal() {
        val p = settings.activeProfileOrNull ?: return
        uz.remote.flow.terminal.RemoteTerminalHelper.openTerminal(project, p)
    }

    private fun executeRemoteRun() {
        logService.showLogWindow()
        if (!connectionManager.isConnected) {
            log("[WARNING] Not connected to server! Please click 'Connect' first.\n", true)
            return
        }

        val p = settings.activeProfile
        p.runCommand = runCommandField.text.trim()
        val rawCmd = p.runCommand
        val cmd = uz.remote.flow.ssh.buildRemoteExecutionCommand(rawCmd, p.javaHome)
        log("[REMOTE RUN] 1. Syncing latest code to server...\n")

        syncManager.syncSingleServer(
            profile = p,
            onLog = { log(it) },
            onComplete = { success ->
                if (!success) {
                    log("[REMOTE RUN WARNING] Sync warning occurred, proceeding with command...\n", true)
                }
                val javaInfo = if (p.javaHome.isNotBlank()) " (Java: ${p.javaHome})" else ""
                log("[REMOTE RUN] 2. Executing remote command on server$javaInfo: $rawCmd\n")
                connectionManager.executeRemoteCommand(
                    cmd = cmd,
                    workingDir = p.remoteProjectPath,
                    onOutput = { log(it) },
                    onComplete = { code ->
                        log("[REMOTE RUN FINISHED] Exit code: $code\n")
                        connectionManager.notifyUser("Remote Flow: Execution Finished", "Application completed on server (Exit code: $code)", NotificationType.INFORMATION)
                        pingApiHealth()
                    }
                )
            }
        )
    }

    private fun executeRemoteDebug() {
        logService.showLogWindow()
        if (!connectionManager.isConnected) {
            log("[WARNING] Not connected to server! Please click 'Connect' first.\n", true)
            return
        }

        val p = settings.activeProfile
        p.debugCommand = debugCommandField.text.trim()
        val rawCmd = p.debugCommand
        val cmd = uz.remote.flow.ssh.buildRemoteExecutionCommand(rawCmd, p.javaHome)
        log("[REMOTE DEBUG] 1. Syncing code to remote server...\n")

        syncManager.syncSingleServer(
            profile = p,
            onLog = { log(it) },
            onComplete = { _ ->
                val javaInfo = if (p.javaHome.isNotBlank()) " (Java: ${p.javaHome})" else ""
                log("[REMOTE DEBUG] 2. Launching application in debug mode (port 5005) on server$javaInfo: $rawCmd\n")
                connectionManager.executeRemoteCommand(
                    cmd = cmd,
                    workingDir = p.remoteProjectPath,
                    onOutput = { log(it) },
                    onComplete = { code -> log("[REMOTE DEBUG EXIT] Exit code: $code\n") }
                )
                log("[DEBUGGER READY] Server is listening on port 5005. Launch IntelliJ 'Remote JVM Debug' configuration!\n")
                connectionManager.notifyUser("Remote Flow: Debug Ready", "Server is listening on port 5005. Connect via IntelliJ 'Remote JVM Debug'!", NotificationType.INFORMATION)
            }
        )
    }

    private fun executeRemoteStop() {
        logService.showLogWindow()
        log("[STOPPING] Sending stop command to remote application...\n")
        val stopCmd = "pkill -f bootRun 2>/dev/null; pkill -f 'java.*jar' 2>/dev/null; echo 'App stopped.'"
        connectionManager.executeRemoteCommand(
            cmd = stopCmd,
            workingDir = settings.activeProfile.remoteProjectPath,
            onOutput = { log(it) },
            onComplete = { log("[STOPPED] Application stopped.\n") }
        )
    }

    private fun updatePortsTableData() {
        if (!::portsTableModel.isInitialized) return
        portsTableModel.rowCount = 0
        val isConn = connectionManager.isConnected
        val activeProfile = settings.activeProfile
        for (p in activeProfile.forwardedPorts) {
            val status = if (isConn && p.isForwarded) "● Active" else if (isConn) "● Ready" else "○ Stopped"
            val directionText = if (p.direction == ForwardDirection.REMOTE_TO_LOCAL) "🌐 Host ➔ 💻 Local" else "💻 Local ➔ 🌐 Host"
            val localAddr = "localhost:" + p.localPort
            val remoteAddr = "remote:" + p.remotePort
            val action = when {
                p.localPort == 15672 || p.remotePort == 15672 -> "http://localhost:15672 (UI)"
                p.localPort == 8080 || p.remotePort == 8080 -> "http://localhost:8080 (API)"
                p.localPort == 5432 || p.remotePort == 5432 -> "IntelliJ Database"
                p.localPort == 6379 || p.remotePort == 6379 -> "Redis CLI"
                p.localPort == 3000 || p.remotePort == 3000 -> "http://localhost:3000 (Web)"
                p.direction == ForwardDirection.REMOTE_TO_LOCAL -> "Reverse -> localhost:${p.localPort}"
                else -> "Direct Tunnel"
            }
            portsTableModel.addRow(arrayOf(status, directionText, p.serviceName, localAddr, remoteAddr, action))
        }
    }

    private fun showAddPortDialog() {
        val activeProfile = settings.activeProfileOrNull ?: return
        val dialog = AddPortForwardDialog(project, activeProfile)
        if (dialog.showAndGet()) {
            val mapping = dialog.getResultPortMapping() ?: return
            activeProfile.forwardedPorts.add(mapping)
            if (connectionManager.isConnected) {
                connectionManager.startSingleForward(mapping)
                val dirName = if (mapping.direction == ForwardDirection.LOCAL_TO_REMOTE) "Local -> Host" else "Host -> Local"
                connectionManager.notifyUser("Remote Flow: Port Forward", "${mapping.serviceName} ($dirName) activated!")
            }
            updatePortsTableData()
        }
    }

    private fun removeSelectedPort() {
        val row = portsTable.selectedRow
        if (row < 0) {
            Messages.showInfoMessage(project, "Please select a port from the table to delete.", "No Port Selected")
            return
        }
        val profile = settings.activeProfile
        if (row < profile.forwardedPorts.size) {
            val portMap = profile.forwardedPorts[row]
            val confirm = Messages.showYesNoDialog(
                project,
                "Are you sure you want to delete port forwarding for '${portMap.serviceName}' (${portMap.localPort} <-> ${portMap.remotePort})?",
                "Delete Port Forward",
                Messages.getQuestionIcon()
            )
            if (confirm == Messages.YES) {
                connectionManager.stopSingleForward(portMap)
                profile.forwardedPorts.removeAt(row)
                updatePortsTableData()
                connectionManager.notifyUser("Remote Flow: Port Removed", "${portMap.serviceName} removed successfully")
            }
        }
    }

    private fun restartTunnels() {
        if (!connectionManager.isConnected) {
            Messages.showWarningDialog(project, "Not connected to server! Please click 'Connect' first.", "Not Connected")
            return
        }
        connectionManager.restartAllTunnels()
        updatePortsTableData()
        connectionManager.notifyUser("Remote Flow: Tunnels Restarted", "All port forwarding tunnels have been restarted!")
    }

    private fun log(message: String, isError: Boolean = false) {
        val serverName = settings.activeProfileOrNull?.name ?: ""
        val category = when {
            message.contains("[PORT") || message.contains("[TUNNEL") -> uz.remote.flow.logging.LogCategory.PORT
            message.contains("[SYNC") || message.contains("[RSYNC") || message.contains("[SFTP") -> uz.remote.flow.logging.LogCategory.SYNC
            message.contains("[REMOTE RUN") || message.contains("[REMOTE DEBUG") || message.contains("[STOP") -> uz.remote.flow.logging.LogCategory.RUN
            message.contains("[CONNECT") || message.contains("[SSH") -> uz.remote.flow.logging.LogCategory.SSH
            message.contains("[FILE") -> uz.remote.flow.logging.LogCategory.FILES
            else -> uz.remote.flow.logging.LogCategory.ALL
        }
        logService.log(message, category, serverName, isError)
    }

    private fun connectSSH() {
        val p = settings.activeProfileOrNull
        if (p == null || p.host.isBlank()) {
            log("[WARNING] No server profile found! Please add a server in settings first.\n", true)
            ShowSettingsUtil.getInstance().showSettingsDialog(project, RemoteFlowConfigurable::class.java)
            return
        }
        connectionBadge.setConnecting(p.name)
        log("[CONNECTING] Connecting to " + p.name + " (" + p.user + "@" + p.host + ":" + p.port + ")...\n")

        connectionManager.connect(
            profile = p,
            onSuccess = {
                log("[SUCCESS] SSH Connected to " + p.name + "! All tunnels active.\n")
                pingApiHealth()
            },
            onError = {
                log("[ERROR] Connection failed: " + it.message + "\n", true)
            }
        )
    }

    private fun disconnectSSH() {
        connectionManager.disconnect()
        log("[DISCONNECTED] SSH session and tunnels closed.\n")
    }

    private fun stopMonitorScheduler() {
        monitorScheduledTask?.cancel(true)
        monitorScheduledTask = null
    }

    private fun restartMonitorScheduler() {
        stopMonitorScheduler()

        val p = settings.activeProfileOrNull
        val mode = p?.monitorMode ?: when (monitorModeBox.selectedIndex) {
            0 -> "REALTIME_3S"
            1 -> "REALTIME_5S"
            2 -> "MANUAL"
            else -> "OFF"
        }

        ApplicationManager.getApplication().invokeLater {
            if (!connectionManager.isConnected) {
                monitorStatusLabel.text = "⏸ Paused (Offline)"
                monitorStatusLabel.foreground = JBColor.GRAY
                btnRefreshStats.isEnabled = false
                return@invokeLater
            }

            btnRefreshStats.isEnabled = (mode != "OFF")

            when (mode) {
                "OFF" -> {
                    monitorStatusLabel.text = "○ Off"
                    monitorStatusLabel.foreground = JBColor.GRAY
                    cpuBar.value = 0
                    cpuLabel.text = "Off"
                    ramBar.value = 0
                    ramLabel.text = "Off"
                    diskBar.value = 0
                    diskLabel.text = "Off"
                    coresWrapperPanel.isVisible = false
                }
                "MANUAL" -> {
                    monitorStatusLabel.text = "● Manual"
                    monitorStatusLabel.foreground = JBColor(Color(59, 130, 246), Color(96, 165, 250))
                }
                "REALTIME_5S" -> {
                    monitorStatusLabel.text = "● Live (5s)"
                    monitorStatusLabel.foreground = JBColor(Color(16, 185, 129), Color(16, 185, 129))
                    checkServerResources(silent = true)
                    monitorScheduledTask = monitorExecutor.scheduleWithFixedDelay({
                        val curMode = settings.activeProfileOrNull?.monitorMode ?: when (monitorModeBox.selectedIndex) {
                            0 -> "REALTIME_3S"
                            1 -> "REALTIME_5S"
                            2 -> "MANUAL"
                            else -> "OFF"
                        }
                        if (connectionManager.isConnected && curMode != "OFF" && curMode != "MANUAL") {
                            checkServerResources(silent = true)
                        }
                    }, 5, 5, TimeUnit.SECONDS)
                }
                else -> { // "REALTIME_3S" or "REALTIME"
                    monitorStatusLabel.text = "● Live (3s)"
                    monitorStatusLabel.foreground = JBColor(Color(16, 185, 129), Color(16, 185, 129))
                    checkServerResources(silent = true)
                    monitorScheduledTask = monitorExecutor.scheduleWithFixedDelay({
                        val curMode = settings.activeProfileOrNull?.monitorMode ?: when (monitorModeBox.selectedIndex) {
                            0 -> "REALTIME_3S"
                            1 -> "REALTIME_5S"
                            2 -> "MANUAL"
                            else -> "OFF"
                        }
                        if (connectionManager.isConnected && curMode != "OFF" && curMode != "MANUAL") {
                            checkServerResources(silent = true)
                        }
                    }, 3, 3, TimeUnit.SECONDS)
                }
            }
        }
    }

    private fun checkServerResources(silent: Boolean = false) {
        val p = settings.activeProfileOrNull
        val mode = p?.monitorMode ?: when (monitorModeBox.selectedIndex) {
            0 -> "REALTIME_3S"
            1 -> "REALTIME_5S"
            2 -> "MANUAL"
            else -> "OFF"
        }
        if (mode == "OFF" && silent) {
            return
        }

        if (!connectionManager.isConnected) {
            if (!silent) {
                log("[WARNING] Not connected to server! Please click 'Connect' first.\n", true)
            }
            return
        }

        statsManager.fetchMetrics(
            onParsed = { metrics ->
                ApplicationManager.getApplication().invokeLater {
                    applyColorToBar(cpuBar, metrics.cpuPercent)
                    cpuLabel.text = metrics.cpuText

                    applyColorToBar(ramBar, metrics.ramPercent)
                    ramLabel.text = metrics.ramText

                    applyColorToBar(diskBar, metrics.diskPercent)
                    diskLabel.text = metrics.diskText

                    updateCoresUi(metrics.cpuCores)
                }
            },
            onLog = { if (!silent) log(it) },
            onComplete = { success ->
                if (!success && !silent) {
                    log("[ERROR] An error occurred while retrieving server resources.\n", true)
                }
            }
        )
    }

    private fun updateCoresUi(cores: List<Int>) {
        if (cores.isEmpty()) {
            coresWrapperPanel.isVisible = false
            return
        }

        lblCoresHeader.text = if (areCoresExpanded) "▼ CPU Cores Breakdown (${cores.size} Cores):" else "▶ CPU Cores Breakdown (${cores.size} Cores)"

        if (coreBars.size != cores.size) {
            coresGridPanel.removeAll()
            coreBars.clear()
            for (i in cores.indices) {
                val row = JPanel(BorderLayout(6, 0))
                row.border = JBUI.Borders.empty(1, 4)
                val cLabel = JLabel("Core $i:")
                cLabel.preferredSize = Dimension(52, 18)
                cLabel.font = cLabel.font.deriveFont(Font.PLAIN, 10f)

                val bar = JProgressBar(0, 100)
                bar.preferredSize = Dimension(80, 14)
                bar.isStringPainted = true

                val valLabel = JLabel("0%")
                valLabel.preferredSize = Dimension(38, 18)
                valLabel.font = valLabel.font.deriveFont(Font.BOLD, 10f)

                row.add(cLabel, BorderLayout.WEST)
                row.add(bar, BorderLayout.CENTER)
                row.add(valLabel, BorderLayout.EAST)
                coresGridPanel.add(row)
                coreBars.add(Pair(bar, valLabel))
            }
            coresGridPanel.revalidate()
            coresGridPanel.repaint()
        }

        for (i in cores.indices) {
            val pct = cores[i]
            val pair = coreBars.getOrNull(i) ?: continue
            val bar = pair.first
            val valLabel = pair.second
            applyColorToBar(bar, pct)
            valLabel.text = "$pct%"
        }

        coresGridPanel.isVisible = areCoresExpanded
        coresWrapperPanel.isVisible = true
        coresWrapperPanel.revalidate()
    }

    private fun applyColorToBar(bar: JProgressBar, percent: Int) {
        bar.value = percent
        bar.string = percent.toString() + "%"
        bar.foreground = when {
            percent < 70 -> JBColor(Color(16, 185, 129), Color(16, 185, 129))
            percent < 85 -> JBColor(Color(245, 158, 11), Color(245, 158, 11))
            else -> JBColor(Color(239, 68, 68), Color(239, 68, 68))
        }
    }

    private fun syncFiles() {
        val p = settings.activeProfileOrNull
        if (p == null || p.host.isBlank()) {
            log("[WARNING] No server profile found! Please add a server in settings first.\n", true)
            ShowSettingsUtil.getInstance().showSettingsDialog(project, RemoteFlowConfigurable::class.java)
            return
        }
        syncManager.syncSingleServer(
            profile = p,
            onLog = { log(it) },
            onComplete = { success ->
                if (success) {
                    log("[SYNC SUCCESS] Files synchronized successfully to ${p.name}!\n")
                    connectionManager.notifyUser("Remote Flow", "Files uploaded successfully to ${p.name}!", NotificationType.INFORMATION)
                } else {
                    log("[SYNC FAILED] Check log details.\n", true)
                }
            }
        )
    }

    private fun syncAllServers() {
        syncManager.syncAllServersParallel(
            profiles = settings.profiles,
            onLog = { log(it) },
            onComplete = { success ->
                if (success) {
                    log("[PARALLEL SYNC COMPLETE] All servers up to date!\n")
                    connectionManager.notifyUser("Remote Flow", "Parallel synchronization completed for all servers!", NotificationType.INFORMATION)
                } else {
                    log("[PARALLEL SYNC WARNING] Some sync tasks reported errors.\n", true)
                }
            }
        )
    }

    private fun pingApiHealth() {
        apiHealthLabel.text = "API: ..."
        val startTime = System.currentTimeMillis()

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val uri = URI.create("http://localhost:8080/actuator/health")
                val conn = uri.toURL().openConnection() as HttpURLConnection
                conn.connectTimeout = 1500
                conn.readTimeout = 1500
                conn.requestMethod = "GET"
                val code = conn.responseCode
                val elapsed = System.currentTimeMillis() - startTime

                ApplicationManager.getApplication().invokeLater {
                    apiHealthLabel.text = "API: 🟢 $code OK (${elapsed}ms)"
                    apiHealthLabel.foreground = JBColor(Color(16, 185, 129), Color(16, 185, 129))
                }
            } catch (_: Exception) {
                try {
                    val rootUri = URI.create("http://localhost:8080")
                    val rootConn = rootUri.toURL().openConnection() as HttpURLConnection
                    rootConn.connectTimeout = 1500
                    rootConn.readTimeout = 1500
                    val rootCode = rootConn.responseCode
                    val elapsed = System.currentTimeMillis() - startTime
                    ApplicationManager.getApplication().invokeLater {
                        apiHealthLabel.text = "API: 🟢 $rootCode (${elapsed}ms)"
                        apiHealthLabel.foreground = JBColor(Color(16, 185, 129), Color(16, 185, 129))
                    }
                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        apiHealthLabel.text = "API: 🔴 Offline"
                        apiHealthLabel.foreground = JBColor.RED
                    }
                }
            }
        }
    }

    private fun createPortKillerPanel(): JPanel {
        val panel = JPanel(BorderLayout(0, 6))
        panel.border = IdeBorderFactory.createTitledBorder("⚡ Remote Port Clash Inspector & Process Killer", false)

        val top = JPanel(BorderLayout(6, 0))
        val left = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2))
        left.add(JBLabel("Target Port:"))
        val portField = JBTextField("8080", 8)
        left.add(portField)
        top.add(left, BorderLayout.WEST)

        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 2))
        val statusText = JBLabel("Ready")
        statusText.font = statusText.font.deriveFont(Font.PLAIN, 11f)

        val btnCheck = JButton("Check Port", AllIcons.Actions.Search)
        btnCheck.toolTipText = "Check whether port is free or occupied (lsof / fuser / ss)"
        btnCheck.addActionListener {
            val port = portField.text.trim()
            if (port.isBlank()) return@addActionListener
            if (!connectionManager.isConnected) {
                Messages.showWarningDialog(project, "Not connected to server!", "Port Inspector")
                return@addActionListener
            }
            statusText.text = "Checking port $port..."
            val cmd = "fuser -v $port/tcp 2>&1 || lsof -i :$port -P -n 2>&1 || ss -tulpn | grep :$port 2>&1 || echo 'Port $port is free (no process found)'"
            connectionManager.executeRemoteCommand(
                cmd = cmd,
                workingDir = "",
                onOutput = { line ->
                    logService.log("[PORT $port CHECK] $line", uz.remote.flow.logging.LogCategory.SSH, settings.activeProfile.name)
                },
                onComplete = { _ ->
                    ApplicationManager.getApplication().invokeLater {
                        statusText.text = "Port $port checked (see Log window)"
                        Messages.showInfoMessage(project, "Port $port information has been written to 'Remote Flow Log' window.", "Port Check")
                    }
                }
            )
        }
        right.add(btnCheck)

        val btnKill = JButton("Kill Process on Port", AllIcons.Actions.Cancel)
        btnKill.font = btnKill.font.deriveFont(Font.BOLD)
        btnKill.foreground = JBColor.RED
        btnKill.toolTipText = "Forcefully terminate process occupying this port (SIGKILL)"
        btnKill.addActionListener {
            val port = portField.text.trim()
            if (port.isBlank()) return@addActionListener
            if (!connectionManager.isConnected) {
                Messages.showWarningDialog(project, "Not connected to server!", "Process Killer")
                return@addActionListener
            }
            val confirm = Messages.showYesNoDialog(
                project,
                "All processes occupying port $port on the remote server will be forcefully terminated (SIGKILL).\n\nDo you want to proceed?",
                "Kill Process on Port $port",
                Messages.getWarningIcon()
            )
            if (confirm != Messages.YES) return@addActionListener

            statusText.text = "Killing process on port $port..."
            val killCmd = "fuser -k -9 $port/tcp 2>&1 || (PID=\$(lsof -t -i :$port); if [ -n \"\$PID\" ]; then kill -9 \$PID && echo \"Killed PID \$PID\"; else echo \"No process found on port $port\"; fi)"
            connectionManager.executeRemoteCommand(
                cmd = killCmd,
                workingDir = "",
                onOutput = { line ->
                    logService.log("[PORT $port KILL] $line", uz.remote.flow.logging.LogCategory.SSH, settings.activeProfile.name)
                },
                onComplete = { _ ->
                    ApplicationManager.getApplication().invokeLater {
                        statusText.text = "Port $port has been freed!"
                        connectionManager.notifyUser(
                            "Port Killer 🛑",
                            "Process on port $port was terminated on remote server!",
                            NotificationType.INFORMATION
                        )
                    }
                }
            )
        }
        right.add(btnKill)

        top.add(right, BorderLayout.EAST)
        panel.add(top, BorderLayout.CENTER)
        return panel
    }

    private fun createGitStatusCard(): JPanel {
        val content = JPanel(BorderLayout(0, 4))
        content.border = JBUI.Borders.empty(4, 6)

        val topRow = JPanel(BorderLayout(8, 0))

        val branchPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        lblGitBranch.font = lblGitBranch.font.deriveFont(Font.BOLD, 12f)
        lblGitBranch.foreground = JBColor(Color(16, 185, 129), Color(16, 185, 129))
        branchPanel.add(lblGitBranch)

        lblGitStatus.font = lblGitStatus.font.deriveFont(Font.BOLD, 11f)
        branchPanel.add(lblGitStatus)
        topRow.add(branchPanel, BorderLayout.CENTER)

        val btnRow = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))
        val btnRefreshGit = JButton("Check Git", AllIcons.Actions.Refresh)
        btnRefreshGit.toolTipText = "Check remote Git branch and status"
        btnRefreshGit.addActionListener { checkRemoteGitStatus() }
        btnRow.add(btnRefreshGit)

        val btnGitPull = JButton("Git Pull", AllIcons.Actions.CheckOut)
        btnGitPull.toolTipText = "Pull latest changes on server (git pull)"
        btnGitPull.addActionListener { executeRemoteGitPull() }
        btnRow.add(btnGitPull)

        val btnGitStashPull = JButton("Stash & Pull", AllIcons.Actions.Rollback)
        btnGitStashPull.toolTipText = "Stash remote changes and pull"
        btnGitStashPull.addActionListener { executeRemoteGitStashPull() }
        btnRow.add(btnGitStashPull)

        topRow.add(btnRow, BorderLayout.EAST)

        val bottomRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        lblGitCommit.font = lblGitCommit.font.deriveFont(Font.PLAIN, 11f)
        lblGitCommit.foreground = JBColor.GRAY
        bottomRow.add(lblGitCommit)

        content.add(topRow, BorderLayout.NORTH)
        content.add(bottomRow, BorderLayout.CENTER)

        return CollapsibleCard(
            title = "Remote Git Status",
            content = content,
            initiallyExpanded = true
        )
    }

    private fun checkRemoteGitStatus() {
        val p = settings.activeProfileOrNull ?: return
        if (!connectionManager.isConnected) {
            lblGitBranch.text = "🌿 Git: Offline"
            lblGitCommit.text = ""
            lblGitStatus.text = ""
            return
        }
        val cmd = "git rev-parse --abbrev-ref HEAD 2>/dev/null; echo '---RF_DIV---'; git log -1 --format='%h - %s (%cr)' 2>/dev/null; echo '---RF_DIV---'; git status -s 2>/dev/null"
        val outputSb = StringBuilder()
        connectionManager.executeRemoteCommand(
            cmd = cmd,
            workingDir = p.remoteProjectPath,
            onOutput = { outputSb.append(it) },
            onComplete = { _ ->
                ApplicationManager.getApplication().invokeLater {
                    val parts = outputSb.toString().split("---RF_DIV---")
                    val branch = parts.getOrNull(0)?.trim()?.lines()?.lastOrNull()?.trim() ?: ""
                    val commit = parts.getOrNull(1)?.trim()?.lines()?.firstOrNull()?.trim() ?: ""
                    val statusLines = parts.getOrNull(2)?.trim()?.lines()?.filter { it.isNotBlank() } ?: emptyList()

                    if (branch.isNotBlank() && !branch.contains("fatal:")) {
                        lblGitBranch.text = "🌿 Branch: $branch"
                        lblGitCommit.text = if (commit.isNotBlank()) "📌 $commit" else ""
                        lblGitStatus.text = if (statusLines.isEmpty()) "✔ Clean" else "⚠ ${statusLines.size} modified files"
                        lblGitStatus.foreground = if (statusLines.isEmpty()) JBColor(Color(16, 185, 129), Color(16, 185, 129)) else JBColor.ORANGE
                    } else {
                        lblGitBranch.text = "🌿 Git: Not a repository"
                        lblGitCommit.text = ""
                        lblGitStatus.text = ""
                    }
                }
            }
        )
    }

    private fun executeRemoteGitPull() {
        val p = settings.activeProfileOrNull ?: return
        if (!connectionManager.isConnected) {
            log("[WARNING] Not connected to server! Please click 'Connect' first.\n", true)
            return
        }
        log("[GIT PULL] Executing git pull on remote server...\n")
        connectionManager.executeRemoteCommand(
            cmd = "git pull",
            workingDir = p.remoteProjectPath,
            onOutput = { log(it) },
            onComplete = { code ->
                log("[GIT PULL FINISHED] Exit code: $code\n")
                checkRemoteGitStatus()
                connectionManager.notifyUser("Git Pull", "git pull completed on server (Exit code: $code)", NotificationType.INFORMATION)
            }
        )
    }

    private fun executeRemoteGitStashPull() {
        val p = settings.activeProfileOrNull ?: return
        if (!connectionManager.isConnected) {
            log("[WARNING] Not connected to server! Please click 'Connect' first.\n", true)
            return
        }
        log("[GIT STASH & PULL] Executing git stash & pull on remote server...\n")
        connectionManager.executeRemoteCommand(
            cmd = "git stash && git pull && git stash pop || git pull",
            workingDir = p.remoteProjectPath,
            onOutput = { log(it) },
            onComplete = { code ->
                log("[GIT STASH & PULL FINISHED] Exit code: $code\n")
                checkRemoteGitStatus()
                connectionManager.notifyUser("Git Stash & Pull", "git stash & pull completed on server", NotificationType.INFORMATION)
            }
        )
    }

    private fun openRemoteConfigManager(initialFile: String? = null) {
        val p = settings.activeProfileOrNull ?: return
        val dialog = uz.remote.flow.config.RemoteConfigManagerDialog(project, p, initialFile)
        dialog.show()
    }
}

class AddPortForwardDialog(
    private val project: Project,
    private val activeProfile: ServerProfile
) : DialogWrapper(project, true) {

    private val connectionManager = RemoteConnectionManager.getInstance(project)

    private val radioLocalToRemote = JRadioButton("💻 Local ➔ 🌐 Host (Local Port Forwarding, ssh -L)", true)
    private val radioRemoteToLocal = JRadioButton("🌐 Host ➔ 💻 Local (Reverse Port Forwarding, ssh -R)", false)
    private val serviceNameField = JBTextField("Backend API")
    private val localPortField = JBTextField("8080")
    private val remotePortField = JBTextField("8080")
    private val hintLabel = JLabel("Connect from your local machine to remote service (DB, Redis, API)")

    // Dynamic Active Port Detection
    private data class DetectedPort(val port: Int, val process: String, val address: String)
    private val discoveredPorts = mutableListOf<DetectedPort>()
    private val activePortsComboBox = JComboBox<String>()
    private val btnScanPorts = JButton("⟳ Scan Active Ports")
    private val lblScanStatus = JLabel("")

    init {
        title = "➕ Forward Port (SSH Tunnel) - ${activeProfile.name}"
        val bg = ButtonGroup()
        bg.add(radioLocalToRemote)
        bg.add(radioRemoteToLocal)

        radioLocalToRemote.addActionListener {
            hintLabel.text = "Connect from your local machine to remote service (DB, Redis, API)"
        }
        radioRemoteToLocal.addActionListener {
            hintLabel.text = "Expose local service (Frontend, Webhook, Mock) to remote server"
        }

        init()
        scanRemotePorts()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        panel.border = JBUI.Borders.empty(8)
        val gbc = GridBagConstraints()
        gbc.insets = JBUI.insets(4, 6, 4, 6)
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.anchor = GridBagConstraints.WEST

        // 1. Dynamic Active Ports Scan Section
        val scanPanel = JPanel(BorderLayout(6, 4))
        scanPanel.border = IdeBorderFactory.createTitledBorder("Active Listening Ports on Server (Live Scan)", false)

        val scanControls = JPanel(BorderLayout(6, 0))
        activePortsComboBox.preferredSize = Dimension(380, 28)
        activePortsComboBox.addItem("Scanning remote server ports...")

        activePortsComboBox.addActionListener {
            val idx = activePortsComboBox.selectedIndex
            if (idx in discoveredPorts.indices) {
                val detected = discoveredPorts[idx]
                remotePortField.text = detected.port.toString()
                localPortField.text = detected.port.toString()
                val cleanProc = if (detected.process.isNotBlank()) detected.process.replaceFirstChar { it.uppercase() } else "Service"
                serviceNameField.text = "$cleanProc (${detected.port})"
            }
        }

        btnScanPorts.toolTipText = "Scan remote server for open listening TCP ports"
        btnScanPorts.addActionListener { scanRemotePorts() }

        scanControls.add(activePortsComboBox, BorderLayout.CENTER)
        scanControls.add(btnScanPorts, BorderLayout.EAST)
        scanPanel.add(scanControls, BorderLayout.CENTER)

        lblScanStatus.font = lblScanStatus.font.deriveFont(Font.ITALIC, 11f)
        lblScanStatus.foreground = JBColor.GRAY
        scanPanel.add(lblScanStatus, BorderLayout.SOUTH)

        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2; gbc.weightx = 1.0
        panel.add(scanPanel, gbc)

        // Direction Selection
        val dirPanel = JPanel(GridLayout(2, 1, 0, 4))
        dirPanel.border = IdeBorderFactory.createTitledBorder("Forward Direction", false)
        dirPanel.add(radioLocalToRemote)
        dirPanel.add(radioRemoteToLocal)

        gbc.gridy = 1; gbc.gridwidth = 2
        panel.add(dirPanel, gbc)

        // Hint
        hintLabel.font = hintLabel.font.deriveFont(Font.ITALIC, 11f)
        hintLabel.foreground = JBColor.GRAY
        gbc.gridy = 2; gbc.gridwidth = 2
        panel.add(hintLabel, gbc)

        // Fields
        gbc.gridwidth = 1
        gbc.gridy = 3; gbc.gridx = 0; gbc.weightx = 0.0; panel.add(JBLabel("Service Name:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0; panel.add(serviceNameField, gbc)

        gbc.gridy = 4; gbc.gridx = 0; gbc.weightx = 0.0; panel.add(JBLabel("Local Port (Local Machine):"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0; panel.add(localPortField, gbc)

        gbc.gridy = 5; gbc.gridx = 0; gbc.weightx = 0.0; panel.add(JBLabel("Remote Port (Remote Host):"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0; panel.add(remotePortField, gbc)

        panel.preferredSize = Dimension(560, 360)
        return panel
    }

    private fun scanRemotePorts() {
        if (!connectionManager.isConnected) {
            activePortsComboBox.removeAllItems()
            activePortsComboBox.addItem("⚠ Server is offline (Connect via SSH first to scan)")
            lblScanStatus.text = "Connect to server to automatically discover listening ports."
            return
        }

        lblScanStatus.text = "Scanning active listening ports on ${activeProfile.name}..."
        btnScanPorts.isEnabled = false
        activePortsComboBox.removeAllItems()
        activePortsComboBox.addItem("Scanning active ports...")

        val cmd = "ss -tlpn 2>/dev/null || netstat -tlpn 2>/dev/null || lsof -iTCP -sTCP:LISTEN -P -n 2>/dev/null"
        val outputSb = StringBuilder()

        connectionManager.executeRemoteCommand(
            cmd = cmd,
            workingDir = "",
            onOutput = { outputSb.append(it) },
            onComplete = { _ ->
                ApplicationManager.getApplication().invokeLater {
                    btnScanPorts.isEnabled = true
                    val ports = parseListeningPorts(outputSb.toString())
                    discoveredPorts.clear()
                    discoveredPorts.addAll(ports)

                    activePortsComboBox.removeAllItems()
                    if (ports.isEmpty()) {
                        activePortsComboBox.addItem("ℹ No listening ports detected (Enter manually below)")
                        lblScanStatus.text = "No listening ports found or insufficient permissions. Enter port manually."
                    } else {
                        for (p in ports) {
                            val icon = when (p.port) {
                                5432, 3306, 27017 -> "🐘"
                                6379 -> "⚡"
                                8080, 8000, 3000 -> "☕"
                                80, 443 -> "🌐"
                                else -> "🔌"
                            }
                            activePortsComboBox.addItem("$icon Port ${p.port} (${p.process}) [${p.address}]")
                        }
                        activePortsComboBox.addItem("✏ Custom Port (Enter manually below)")
                        lblScanStatus.text = "Found ${ports.size} active listening port(s) on ${activeProfile.name}."
                        if (ports.isNotEmpty()) {
                            activePortsComboBox.selectedIndex = 0
                        }
                    }
                }
            }
        )
    }

    private fun parseListeningPorts(output: String): List<DetectedPort> {
        val list = mutableListOf<DetectedPort>()
        val seen = mutableSetOf<Int>()

        val lines = output.lines()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isBlank() || line.startsWith("State") || line.startsWith("Proto") || line.startsWith("COMMAND")) continue

            val portMatch = Regex(""":(\d{2,5})\s+""").find(line)
            if (portMatch != null) {
                val port = portMatch.groupValues[1].toIntOrNull() ?: continue
                if (port in 1..65535 && !seen.contains(port)) {
                    seen.add(port)

                    var proc = ""
                    val ssMatch = Regex("""users:\(\("([^"]+)"""").find(line)
                    if (ssMatch != null) {
                        proc = ssMatch.groupValues[1]
                    } else {
                        val netstatMatch = Regex("""\d+/([a-zA-Z0-9_\-\.]+)""").find(line)
                        if (netstatMatch != null) {
                            proc = netstatMatch.groupValues[1]
                        } else {
                            val firstWord = line.split("\\s+".toRegex()).firstOrNull() ?: ""
                            if (firstWord.isNotBlank() && !firstWord.startsWith("tcp") && firstWord != "LISTEN") {
                                proc = firstWord
                            }
                        }
                    }

                    if (proc.isBlank() || proc == "users") {
                        proc = when (port) {
                            5432 -> "PostgreSQL"
                            3306 -> "MySQL"
                            6379 -> "Redis"
                            27017 -> "MongoDB"
                            5672, 15672 -> "RabbitMQ"
                            8080 -> "Spring Boot / API"
                            8000 -> "API Service"
                            3000 -> "Node/React"
                            5173 -> "Vite Dev"
                            9000 -> "MinIO / Service"
                            22 -> "SSH"
                            80 -> "HTTP"
                            443 -> "HTTPS"
                            else -> "Service"
                        }
                    }

                    val addrMatch = Regex("""(\S+):$port""").find(line)
                    val addr = addrMatch?.groupValues?.get(1) ?: "*"
                    list.add(DetectedPort(port, proc, addr))
                }
            }
        }
        return list.sortedBy { it.port }
    }

    fun getResultPortMapping(): PortMapping? {
        val lPort = localPortField.text.trim().toIntOrNull() ?: return null
        val rPort = remotePortField.text.trim().toIntOrNull() ?: return null
        val name = serviceNameField.text.trim().ifBlank { "Service ($lPort)" }
        val dir = if (radioRemoteToLocal.isSelected) ForwardDirection.REMOTE_TO_LOCAL else ForwardDirection.LOCAL_TO_REMOTE
        return PortMapping(lPort, rPort, name, direction = dir)
    }

    override fun doValidate(): ValidationInfo? {
        val lPort = localPortField.text.trim().toIntOrNull()
        if (lPort == null || lPort !in 1..65535) {
            return ValidationInfo("Local port must be between 1 and 65535!", localPortField)
        }
        val rPort = remotePortField.text.trim().toIntOrNull()
        if (rPort == null || rPort !in 1..65535) {
            return ValidationInfo("Remote port must be between 1 and 65535!", remotePortField)
        }
        if (serviceNameField.text.trim().isBlank()) {
            return ValidationInfo("Please enter a service name!", serviceNameField)
        }
        return null
    }
}
