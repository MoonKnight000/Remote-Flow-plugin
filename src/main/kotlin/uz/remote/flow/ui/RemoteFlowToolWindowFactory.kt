package uz.remote.flow.ui

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
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
import uz.remote.flow.docker.DockerComposeManager
import uz.remote.flow.settings.RemoteFlowConfigurable
import uz.remote.flow.settings.RemoteFlowSettings
import uz.remote.flow.settings.ServerProfileEditDialog
import uz.remote.flow.ssh.PortMapping
import uz.remote.flow.ssh.RemoteConnectionListener
import uz.remote.flow.ssh.RemoteConnectionManager
import uz.remote.flow.ssh.ServerProfile
import uz.remote.flow.sync.FastSyncManager
import uz.remote.flow.system.ServerStatsManager
import java.awt.*
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
    private val dockerManager = DockerComposeManager(project)
    private val syncManager = FastSyncManager(project)
    private val statsManager = ServerStatsManager(project)

    private val terminalConsoleView: ConsoleView = TextConsoleBuilderFactory.getInstance().createBuilder(project).console

    // Header Controls
    private val profileComboBox = JComboBox<ServerProfile>()
    private val btnAddServer = JButton("➕")
    private val btnEditServer = JButton("✏ Edit")
    private val btnConnectToggle = JButton("⚡ Connect")
    private val statusDot = JLabel("● ")
    private val statusText = JLabel("Disconnected")
    private val btnSyncHeader = JButton("🔄 Sync")
    private val btnFilesHeader = JButton("📁 Files")
    private val btnTerminalHeader = JButton("💻 Terminal")
    private val btnPingApi = JButton("🩺 Ping API")
    private val apiHealthLabel = JLabel("API: --")
    private val chkAutoSyncHeader = JBCheckBox("⚡ Auto-Sync")
    private val btnConfigsHeader = JButton("⚙ Configs")
    private val btnSettings = JButton("⚙ Settings")

    // Remote Git Status Labels
    private val lblGitBranch = JLabel("🌿 Branch: -")
    private val lblGitCommit = JLabel("📌 Commit: -")
    private val lblGitStatus = JLabel("Status: -")

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
    private val dockerStatsArea = JTextArea("Resurs ma'lumotlarini olish uchun 'Refresh' tugmasini bosing.")

    // Hardware Resource Monitor Controls
    private val monitorModeBox = JComboBox(arrayOf("⚡ Real-time (3s)", "⏱ Real-time (5s)", "🔍 Manual (On demand)", "🚫 Off (Disabled)"))
    private val monitorStatusLabel = JLabel("● Active")
    private val btnRefreshStats = JButton("⟳ Refresh")
    private var monitorScheduledTask: ScheduledFuture<*>? = null
    private val monitorExecutor = AppExecutorUtil.getAppScheduledExecutorService()
    private var isUpdatingMonitorUi = false

    // Overview info labels
    private val lblServerHost = JLabel("-")
    private val lblServerUser = JLabel("-")
    private val lblServerRemoteDir = JLabel("-")

    // Tables
    private lateinit var portsTableModel: DefaultTableModel
    private lateinit var portsTable: JBTable
    private lateinit var dockerTableModel: DefaultTableModel
    private lateinit var dockerTable: JBTable

    init {
        border = JBUI.Borders.empty(4)

        // Setup Header Control Bar
        add(createHeaderPanel(), BorderLayout.NORTH)

        // 6 Logical Tabs
        tabbedPane.addTab("📊 Dashboard", createDashboardTab())
        tabbedPane.addTab("📁 Files", filesPanel)
        tabbedPane.addTab("🚀 Run & Debug", createRunDebugTab())
        tabbedPane.addTab("🐳 Docker", createDockerTab())
        tabbedPane.addTab("🔌 Ports & Database", createPortsAndDbTab())
        tabbedPane.addTab("💻 Terminal", createTerminalTab())

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

        val left = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
        left.add(JBLabel("Server:"))
        profileComboBox.preferredSize = Dimension(170, 26)
        profileComboBox.addActionListener {
            val selected = profileComboBox.selectedItem as? ServerProfile
            if (selected != null && profileComboBox.selectedIndex != settings.activeProfileIndex) {
                settings.activeProfileIndex = profileComboBox.selectedIndex
                loadProfileData(selected)
                project.messageBus.syncPublisher(RemoteConnectionListener.TOPIC).profileChanged(selected)
            }
        }
        left.add(profileComboBox)

        btnAddServer.toolTipText = "Yangi server qo'shish"
        btnAddServer.addActionListener {
            val newP = ServerProfile(
                name = "Server " + (settings.profiles.size + 1),
                host = "192.168.1.100",
                localProjectPath = project.basePath ?: "",
                remoteProjectPath = "/root/remote-flow/" + project.name
            )
            val dialog = ServerProfileEditDialog(project, newP, isNew = true)
            if (dialog.showAndGet()) {
                refreshProfileComboBox()
                loadProfileData(newP)
            }
        }
        left.add(btnAddServer)

        btnEditServer.toolTipText = "Faol server parametrlarini tahrirlash"
        btnEditServer.addActionListener {
            val current = settings.activeProfileOrNull
            if (current == null) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, RemoteFlowConfigurable::class.java)
                return@addActionListener
            }
            val dialog = ServerProfileEditDialog(project, current, isNew = false)
            if (dialog.showAndGet()) {
                refreshProfileComboBox()
                loadProfileData(current)
            }
        }
        left.add(btnEditServer)

        btnConnectToggle.font = btnConnectToggle.font.deriveFont(Font.BOLD)
        btnConnectToggle.addActionListener {
            if (connectionManager.isConnected) {
                disconnectSSH()
            } else {
                connectSSH()
            }
        }
        left.add(btnConnectToggle)

        statusDot.foreground = JBColor.GRAY
        left.add(statusDot)
        statusText.font = statusText.font.deriveFont(Font.BOLD, 11f)
        left.add(statusText)

        chkAutoSyncHeader.toolTipText = "Fayl saqlanganda (Ctrl+S) avtomatik ravishda serverga sinxronizatsiya qilish"
        chkAutoSyncHeader.addActionListener {
            val p = settings.activeProfileOrNull ?: return@addActionListener
            p.autoSyncOnSave = chkAutoSyncHeader.isSelected
        }
        left.add(chkAutoSyncHeader)

        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 2))

        btnSyncHeader.toolTipText = "Kodni tezkor faol serverga yuklash"
        btnSyncHeader.addActionListener { syncFiles() }
        right.add(btnSyncHeader)

        btnFilesHeader.toolTipText = "Serverdagi loyiha papkasi va fayllarni ko'rish"
        btnFilesHeader.addActionListener {
            selectTab("Files")
            filesPanel.loadDirectory(settings.activeProfile.remoteProjectPath)
        }
        right.add(btnFilesHeader)

        btnConfigsHeader.toolTipText = "Masofaviy .env va application.yml sozlamalarini boshqarish"
        btnConfigsHeader.addActionListener { openRemoteConfigManager() }
        right.add(btnConfigsHeader)

        btnTerminalHeader.toolTipText = "IntelliJ IDEA Terminalida serverga SSH orqali ulanish"
        btnTerminalHeader.addActionListener {
            val p = settings.activeProfileOrNull ?: return@addActionListener
            uz.remote.flow.terminal.RemoteTerminalHelper.openTerminal(project, p)
        }
        right.add(btnTerminalHeader)

        btnPingApi.addActionListener { pingApiHealth() }
        right.add(btnPingApi)
        right.add(apiHealthLabel)

        val btnLogsHeader = JButton("📜 Logs")
        btnLogsHeader.toolTipText = "Pastki paneldagi Remote Flow barcha loglar oynasini ochish"
        btnLogsHeader.addActionListener {
            com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow("Remote Flow Log")?.show(null)
        }
        right.add(btnLogsHeader)

        btnSettings.toolTipText = "Barcha serverlar va sozlamalarni ochish"
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
            runCommandField.text = ""
            debugCommandField.text = ""
        }
    }

    private fun loadProfileData(p: ServerProfile) {
        runCommandField.text = p.runCommand
        debugCommandField.text = p.debugCommand
        chkAutoSyncHeader.isSelected = p.autoSyncOnSave
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
        } else {
            lblServerHost.text = p.host + ":" + p.port
            lblServerUser.text = p.user + " (" + p.authType.name + ")"
            lblServerRemoteDir.text = p.remoteProjectPath
        }
    }

    private fun updateConnectionStateUi(connected: Boolean) {
        val p = settings.activeProfile
        if (connected) {
            btnConnectToggle.text = "⏹ Disconnect"
            btnConnectToggle.foreground = JBColor.RED
            statusDot.foreground = JBColor(Color(16, 185, 129), Color(16, 185, 129))
            statusText.text = "Connected"
            statusText.foreground = JBColor(Color(16, 185, 129), Color(16, 185, 129))
            checkRemoteGitStatus()
        } else {
            btnConnectToggle.text = "⚡ Connect"
            btnConnectToggle.foreground = JBColor.foreground()
            statusDot.foreground = JBColor.GRAY
            statusText.text = "Disconnected"
            statusText.foreground = JBColor.foreground()
            lblGitBranch.text = "🌿 Branch: -"
            lblGitCommit.text = "📌 Commit: -"
            lblGitStatus.text = "Status: -"
        }
        updatePortsTableData()
        restartMonitorScheduler()
    }

    // TAB 1: Dashboard
    private fun createDashboardTab(): JPanel {
        val root = JPanel(BorderLayout(0, 8))
        root.border = JBUI.Borders.empty(8)

        val top = JPanel()
        top.layout = BoxLayout(top, BoxLayout.Y_AXIS)

        // Summary Card
        val summaryCard = JPanel(GridBagLayout())
        summaryCard.border = IdeBorderFactory.createTitledBorder("Active Server Information", false)
        val gbc = GridBagConstraints()
        gbc.insets = JBUI.insets(3, 6, 3, 6)
        gbc.anchor = GridBagConstraints.WEST

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0; summaryCard.add(JBLabel("Host Address:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0; summaryCard.add(lblServerHost, gbc)

        gbc.gridx = 2; gbc.gridy = 0; gbc.weightx = 0.0; summaryCard.add(JBLabel("User & Auth:"), gbc)
        gbc.gridx = 3; gbc.weightx = 1.0; summaryCard.add(lblServerUser, gbc)

        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0; summaryCard.add(JBLabel("Remote Directory:"), gbc)
        gbc.gridx = 1; gbc.gridwidth = 2; gbc.weightx = 1.0; summaryCard.add(lblServerRemoteDir, gbc)

        val btnOpenDir = JButton("📁 Browse Files")
        btnOpenDir.toolTipText = "Ushbu masofaviy papkadagi barcha fayllarni ko'rish"
        btnOpenDir.addActionListener {
            selectTab("Files")
            filesPanel.loadDirectory(settings.activeProfile.remoteProjectPath)
        }
        gbc.gridx = 3; gbc.gridwidth = 1; gbc.weightx = 0.0; summaryCard.add(btnOpenDir, gbc)

        top.add(summaryCard)
        top.add(Box.createVerticalStrut(6))

        // Resource Meters Card
        val metersCard = JPanel(BorderLayout(0, 6))
        metersCard.border = IdeBorderFactory.createTitledBorder("Hardware Resource Monitor", false)

        val metersToolbar = JPanel(BorderLayout(6, 0))
        metersToolbar.border = JBUI.Borders.empty(0, 4, 4, 4)

        val modePanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        modePanel.add(JBLabel("Rejim:"))
        monitorModeBox.preferredSize = Dimension(170, 26)
        modePanel.add(monitorModeBox)
        monitorStatusLabel.font = monitorStatusLabel.font.deriveFont(Font.BOLD, 11f)
        modePanel.add(monitorStatusLabel)

        val btnPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))
        btnRefreshStats.font = btnRefreshStats.font.deriveFont(Font.BOLD)
        btnRefreshStats.toolTipText = "Resurslarni hozirgi holatini yangilash"
        btnRefreshStats.addActionListener { checkServerResources(silent = false) }
        btnPanel.add(btnRefreshStats)

        metersToolbar.add(modePanel, BorderLayout.WEST)
        metersToolbar.add(btnPanel, BorderLayout.EAST)
        metersCard.add(metersToolbar, BorderLayout.NORTH)

        val metersGrid = JPanel(GridLayout(3, 1, 0, 6))
        metersGrid.add(createMeterRow("CPU Usage:", cpuBar, cpuLabel))
        metersGrid.add(createMeterRow("RAM Usage:", ramBar, ramLabel))
        metersGrid.add(createMeterRow("Disk (/ root):", diskBar, diskLabel))
        metersCard.add(metersGrid, BorderLayout.CENTER)

        top.add(metersCard)
        top.add(Box.createVerticalStrut(6))

        // Quick Actions Row
        val quickActionCard = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4))
        quickActionCard.border = IdeBorderFactory.createTitledBorder("Quick Operations", false)

        val btnBrowseFiles = JButton("📁 Browse Remote Files")
        btnBrowseFiles.toolTipText = "Serverdagi loyiha papkasi (remoteProjectPath) fayllarini ko'rish"
        btnBrowseFiles.addActionListener {
            selectTab("Files")
            filesPanel.loadDirectory(settings.activeProfile.remoteProjectPath)
        }
        quickActionCard.add(btnBrowseFiles)

        val btnDryRun = JButton("🔍 Preview / Dry-Run (Diff)")
        btnDryRun.addActionListener {
            syncManager.previewDryRun(settings.activeProfile, { log(it) }, {})
        }
        quickActionCard.add(btnDryRun)

        val btnSyncAll = JButton("🌐 Sync ALL Servers")
        btnSyncAll.addActionListener { syncAllServers() }
        quickActionCard.add(btnSyncAll)

        val btnPrune = JButton("🧹 Rescue Disk Space (Docker Prune)")
        btnPrune.addActionListener { rescueDiskSpace() }
        quickActionCard.add(btnPrune)

        val btnOpenTerminalQuick = JButton("💻 Open SSH Terminal")
        btnOpenTerminalQuick.toolTipText = "IntelliJ IDEA Terminalida serverga SSH bilan ulanish"
        btnOpenTerminalQuick.addActionListener {
            val p = settings.activeProfileOrNull ?: return@addActionListener
            uz.remote.flow.terminal.RemoteTerminalHelper.openTerminal(project, p)
        }
        quickActionCard.add(btnOpenTerminalQuick)

        val btnRemoteConfigs = JButton("⚙ Remote Configs (.env / .yml)")
        btnRemoteConfigs.toolTipText = "Masofaviy .env va application.yml sozlamalarini boshqarish"
        btnRemoteConfigs.addActionListener { openRemoteConfigManager() }
        quickActionCard.add(btnRemoteConfigs)

        top.add(quickActionCard)
        top.add(Box.createVerticalStrut(6))
        top.add(createGitStatusCard())
        top.add(Box.createVerticalStrut(6))

        // Docker Breakdown Console
        val dockerPanel = JPanel(BorderLayout(0, 4))
        dockerPanel.border = IdeBorderFactory.createTitledBorder("Docker Containers Resource Breakdown", false)
        dockerStatsArea.font = Font("Monospaced", Font.PLAIN, 12)
        dockerStatsArea.isEditable = false
        dockerPanel.add(JBScrollPane(dockerStatsArea), BorderLayout.CENTER)

        root.add(top, BorderLayout.NORTH)
        root.add(dockerPanel, BorderLayout.CENTER)
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
        val btnRun = JButton("▶ Remote Run (Sync & Build & Run)")
        btnRun.font = btnRun.font.deriveFont(Font.BOLD)
        btnRun.foreground = JBColor(Color(16, 185, 129), Color(16, 185, 129))
        btnRun.addActionListener { executeRemoteRun() }
        runBtnRow.add(btnRun)

        val btnStop = JButton("⏹ Stop App")
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
        val btnDebug = JButton("🪲 Remote Debug (Start in Debug Mode)")
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
        logInfoPanel.border = IdeBorderFactory.createTitledBorder("Ijro Jurnali (Execution Logs)", false)

        val cardContent = JPanel()
        cardContent.layout = BoxLayout(cardContent, BoxLayout.Y_AXIS)
        cardContent.border = JBUI.Borders.empty(12)

        val lblNotice = JBLabel("<html><b>Barcha sinxronizatsiya, qurish va ilova loglari pastki 'Remote Flow Log' oynasida real vaqtda ko'rsatiladi.</b><br/>U yerda loglarni qidirish, server bo'yicha filtrlash va tozalash imkoniyati mavjud.</html>")
        lblNotice.font = lblNotice.font.deriveFont(Font.PLAIN, 12f)
        cardContent.add(lblNotice)
        cardContent.add(Box.createVerticalStrut(12))

        val btnOpenBottomLog = JButton("📜 Pastki 'Remote Flow Log' Oynasini Ochish")
        btnOpenBottomLog.font = btnOpenBottomLog.font.deriveFont(Font.BOLD, 12f)
        btnOpenBottomLog.foreground = JBColor(Color(16, 185, 129), Color(16, 185, 129))
        btnOpenBottomLog.preferredSize = Dimension(320, 36)
        btnOpenBottomLog.toolTipText = "Pastki paneldagi Remote Flow barcha loglar oynasini ochish"
        btnOpenBottomLog.addActionListener {
            com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow("Remote Flow Log")?.show(null)
        }
        val btnRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        btnRow.add(btnOpenBottomLog)
        cardContent.add(btnRow)
        cardContent.add(Box.createVerticalStrut(16))

        val helpBox = JPanel(GridLayout(3, 1, 0, 6))
        helpBox.border = IdeBorderFactory.createTitledBorder("Quick Endpoints & Debug Info", false)
        helpBox.add(JBLabel("• JVM Debug: localhost:5005 (IntelliJ 'Remote JVM Debug' konfiguratsiyasi orqali ulaning)"))
        helpBox.add(JBLabel("• Web Service: http://localhost:8080 (Forwarded ports orqali brauzerda ochiladi)"))
        helpBox.add(JBLabel("• Health Check: http://localhost:8080/actuator/health"))
        cardContent.add(helpBox)

        logInfoPanel.add(cardContent, BorderLayout.NORTH)

        panel.add(top, BorderLayout.NORTH)
        panel.add(logInfoPanel, BorderLayout.CENTER)
        return panel
    }

    // TAB 3: Docker
    private fun createDockerTab(): JPanel {
        val panel = JPanel(BorderLayout(0, 6))
        panel.border = JBUI.Borders.empty(6)

        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2))
        toolbar.border = IdeBorderFactory.createTitledBorder("Docker Compose Control", false)

        val btnUp = JButton("🚀 Compose Up (-d --build)")
        btnUp.addActionListener { dockerUp() }
        toolbar.add(btnUp)

        val btnDown = JButton("🛑 Compose Down")
        btnDown.addActionListener { dockerDown() }
        toolbar.add(btnDown)

        val btnPrune = JButton("🧹 Rescue Disk Space")
        btnPrune.addActionListener { rescueDiskSpace() }
        toolbar.add(btnPrune)

        val btnRefresh = JButton("⟳ Refresh")
        btnRefresh.addActionListener { refreshDockerContainers() }
        toolbar.add(btnRefresh)

        val cols = arrayOf("Container Name", "Image", "Status", "Ports")
        dockerTableModel = object : DefaultTableModel(cols, 0) {
            override fun isCellEditable(row: Int, column: Int): Boolean = false
        }
        dockerTable = JBTable(dockerTableModel)
        dockerTable.rowHeight = 26

        val actionRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2))
        actionRow.border = IdeBorderFactory.createTitledBorder("Selected Container Actions", false)

        val btnRestartC = JButton("🔄 Restart")
        btnRestartC.addActionListener {
            val name = getSelectedContainerName() ?: return@addActionListener
            log("[DOCKER] Restarting container: $name...\n")
            dockerManager.restartContainer(name, { log(it) }, { refreshDockerContainers() })
        }
        actionRow.add(btnRestartC)

        val btnStopC = JButton("🛑 Stop")
        btnStopC.addActionListener {
            val name = getSelectedContainerName() ?: return@addActionListener
            log("[DOCKER] Stopping container: $name...\n")
            dockerManager.stopContainer(name, { log(it) }, { refreshDockerContainers() })
        }
        actionRow.add(btnStopC)

        val btnStartC = JButton("▶ Start")
        btnStartC.addActionListener {
            val name = getSelectedContainerName() ?: return@addActionListener
            log("[DOCKER] Starting container: $name...\n")
            dockerManager.startContainer(name, { log(it) }, { refreshDockerContainers() })
        }
        actionRow.add(btnStartC)

        val btnLogsC = JButton("📜 Logs")
        btnLogsC.addActionListener {
            val name = getSelectedContainerName() ?: return@addActionListener
            log("[DOCKER] Streaming logs for $name...\n")
            dockerManager.streamLogs(name, { log(it) }, {})
        }
        actionRow.add(btnLogsC)

        panel.add(toolbar, BorderLayout.NORTH)
        panel.add(JBScrollPane(dockerTable), BorderLayout.CENTER)
        panel.add(actionRow, BorderLayout.SOUTH)
        return panel
    }

    // TAB 4: Ports & Database
    private fun createPortsAndDbTab(): JPanel {
        val panel = JPanel(BorderLayout(0, 8))
        panel.border = JBUI.Borders.empty(6)

        // Database Card
        val dbCard = JPanel(GridBagLayout())
        dbCard.border = IdeBorderFactory.createTitledBorder("IntelliJ Database Explorer (PostgreSQL 5432 Tunnel)", false)
        val gbc = GridBagConstraints()
        gbc.insets = JBUI.insets(3, 4, 3, 4)
        gbc.anchor = GridBagConstraints.WEST
        gbc.fill = GridBagConstraints.HORIZONTAL

        val jdbcUrlField = JBTextField("jdbc:postgresql://localhost:5432/home_sale_db")
        jdbcUrlField.isEditable = false

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0; dbCard.add(JBLabel("JDBC URL:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0; dbCard.add(jdbcUrlField, gbc)

        val btnCopyJdbc = JButton("📋 Copy JDBC URL")
        btnCopyJdbc.addActionListener {
            val sel = StringSelection(jdbcUrlField.text)
            Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
            Messages.showInfoMessage(project, "JDBC URL buferga nusxalandi!", "Nusxalandi")
        }
        gbc.gridx = 2; gbc.weightx = 0.0; dbCard.add(btnCopyJdbc, gbc)

        val btnTestDb = JButton("⚡ Test Database Connection via Remote Query")
        btnTestDb.addActionListener {
            log("[DATABASE TEST] PostgreSQL holati tekshirilmoqda...\n")
            connectionManager.executeRemoteCommand(
                cmd = "docker exec -i \$(docker ps -qf 'name=postgres' | head -n1) psql -U postgres -d home_sale_db -c 'SELECT current_database(), version();' 2>/dev/null || psql -U postgres -c 'SELECT current_database();' 2>/dev/null",
                onOutput = { log(it) },
                onComplete = { log("[DATABASE TEST COMPLETED]\n") }
            )
        }
        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 3; gbc.weightx = 1.0
        dbCard.add(btnTestDb, gbc)
        gbc.gridwidth = 1

        // Forwarded Ports Table
        val portsBox = JPanel(BorderLayout(0, 6))
        portsBox.border = IdeBorderFactory.createTitledBorder("Forwarded Ports Management", false)

        val portHeader = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))
        val btnAddPort = JButton("➕ Forward New Port...")
        btnAddPort.addActionListener { showAddPortDialog() }
        portHeader.add(btnAddPort)
        portsBox.add(portHeader, BorderLayout.NORTH)

        val columns = arrayOf("Status", "Service Name", "Local Address", "Remote Port", "Open / Action")
        portsTableModel = object : DefaultTableModel(columns, 0) {
            override fun isCellEditable(row: Int, column: Int): Boolean = false
        }
        portsTable = JBTable(portsTableModel)
        portsTable.rowHeight = 26
        portsBox.add(JBScrollPane(portsTable), BorderLayout.CENTER)

        panel.add(dbCard, BorderLayout.NORTH)
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
        terminalInputField.toolTipText = "Buyruq kiriting va Enter bosing (masalan: htop, ls -la, df -h, ps aux)"
        terminalInputField.addActionListener { executeTerminalInput() }

        val btnExec = JButton("Execute (Enter)")
        btnExec.addActionListener { executeTerminalInput() }

        inputPanel.add(JBLabel("remote:~# "), BorderLayout.WEST)
        inputPanel.add(terminalInputField, BorderLayout.CENTER)
        inputPanel.add(btnExec, BorderLayout.EAST)

        val btnOpenIdeaTerminal = JButton("💻 Open in IntelliJ Terminal")
        btnOpenIdeaTerminal.font = btnOpenIdeaTerminal.font.deriveFont(Font.BOLD)
        btnOpenIdeaTerminal.foreground = JBColor(Color(16, 185, 129), Color(16, 185, 129))
        btnOpenIdeaTerminal.toolTipText = "IntelliJ IDEA ning o'zida terminal ochib, serverga avtomatik SSH bilan ulanish"
        btnOpenIdeaTerminal.addActionListener {
            val p = settings.activeProfileOrNull ?: return@addActionListener
            uz.remote.flow.terminal.RemoteTerminalHelper.openTerminal(project, p)
        }

        val btnLaunchExternal = JButton("🚀 External Terminal")
        btnLaunchExternal.toolTipText = "Alohida PowerShell oynasida SSH bilan ochish"
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
            terminalConsoleView.print("[ERROR] Serverga ulanmagansiz! Avval 'Connect' tugmasini bosing.\n", ConsoleViewContentType.ERROR_OUTPUT)
            return
        }

        terminalConsoleView.print("remote:~# $cmd\n", ConsoleViewContentType.USER_INPUT)
        terminalInputField.text = ""

        connectionManager.executeRemoteCommand(
            cmd = cmd,
            workingDir = settings.activeProfile.remoteProjectPath,
            onOutput = { line ->
                ApplicationManager.getApplication().invokeLater {
                    terminalConsoleView.print(line, ConsoleViewContentType.NORMAL_OUTPUT)
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
        if (!connectionManager.isConnected) {
            log("[WARNING] Serverga ulanmagansiz! Avval 'Connect' tugmasini bosing.\n", true)
            return
        }

        val p = settings.activeProfile
        p.runCommand = runCommandField.text.trim()
        val rawCmd = p.runCommand
        val cmd = "chmod +x ./gradlew ./mvnw *.sh 2>/dev/null || true; $rawCmd"
        log("[REMOTE RUN] 1. Eng yangi kodlar serverga sinxronlanmoqda...\n")

        syncManager.syncSingleServer(
            profile = p,
            onLog = { log(it) },
            onComplete = { success ->
                if (!success) {
                    log("[REMOTE RUN WARNING] Sinxronizatsiyada ogohlantirish bo'ldi, buyruq bajarilmoqda...\n", true)
                }
                log("[REMOTE RUN] 2. Masofaviy buyruq serverda bajarilmoqda: $rawCmd\n")
                connectionManager.executeRemoteCommand(
                    cmd = cmd,
                    workingDir = p.remoteProjectPath,
                    onOutput = { log(it) },
                    onComplete = { code ->
                        log("[REMOTE RUN FINISHED] Exit code: $code\n")
                        connectionManager.notifyUser("Remote Flow: Ishga tushdi", "Ilova serverda bajarildi (Exit code: $code)", NotificationType.INFORMATION)
                        pingApiHealth()
                    }
                )
            }
        )
    }

    private fun executeRemoteDebug() {
        if (!connectionManager.isConnected) {
            log("[WARNING] Serverga ulanmagansiz! Avval 'Connect' tugmasini bosing.\n", true)
            return
        }

        val p = settings.activeProfile
        p.debugCommand = debugCommandField.text.trim()
        val rawCmd = p.debugCommand
        val cmd = "chmod +x ./gradlew ./mvnw *.sh 2>/dev/null || true; $rawCmd"
        log("[REMOTE DEBUG] 1. Kodlar serverga yuklanmoqda...\n")

        syncManager.syncSingleServer(
            profile = p,
            onLog = { log(it) },
            onComplete = { _ ->
                log("[REMOTE DEBUG] 2. Ilova debug rejimida (port 5005) serverda ishga tushirilmoqda: $rawCmd\n")
                connectionManager.executeRemoteCommand(
                    cmd = cmd,
                    workingDir = p.remoteProjectPath,
                    onOutput = { log(it) },
                    onComplete = { code -> log("[REMOTE DEBUG EXIT] Exit code: $code\n") }
                )
                log("[DEBUGGER READY] Server 5005 portda kutmoqda. IntelliJ Remote JVM Debug ni ishga tushiring!\n")
                connectionManager.notifyUser("Remote Flow: Debug Tayyor", "Server 5005 portda kutmoqda. IntelliJ Remote JVM Debug ni bosing!", NotificationType.INFORMATION)
            }
        )
    }

    private fun executeRemoteStop() {
        log("[STOPPING] Masofaviy ilovani to'xtatish buyrug'i yuborilmoqda...\n")
        val stopCmd = "pkill -f bootRun 2>/dev/null; pkill -f 'java.*jar' 2>/dev/null; docker compose stop 2>/dev/null; echo 'App stopped.'"
        connectionManager.executeRemoteCommand(
            cmd = stopCmd,
            workingDir = settings.activeProfile.remoteProjectPath,
            onOutput = { log(it) },
            onComplete = { log("[STOPPED] Ilova to'xtatildi.\n") }
        )
    }

    private fun updatePortsTableData() {
        if (!::portsTableModel.isInitialized) return
        portsTableModel.rowCount = 0
        val isConn = connectionManager.isConnected
        val activeProfile = settings.activeProfile
        for (p in activeProfile.forwardedPorts) {
            val status = if (isConn && p.isForwarded) "● Forwarded" else if (isConn) "● Available" else "○ Stopped"
            val localAddr = "localhost:" + p.localPort
            val remotePort = p.remotePort.toString()
            val action = when (p.localPort) {
                15672 -> "http://localhost:15672 (UI)"
                8080 -> "http://localhost:8080 (API)"
                5432 -> "IntelliJ Database"
                else -> "Direct Tunnel"
            }
            portsTableModel.addRow(arrayOf(status, p.serviceName, localAddr, remotePort, action))
        }
    }

    private fun showAddPortDialog() {
        val portStr = Messages.showInputDialog(
            project,
            "Masofaviy server portini kiriting (masalan: 9092, 3000):",
            "Forward New Port",
            Messages.getQuestionIcon()
        ) ?: return

        val port = portStr.toIntOrNull()
        if (port == null || port !in 1..65535) {
            Messages.showErrorDialog(project, "Noto'g'ri port raqami kiritildi!", "Xatolik")
            return
        }

        val serviceName = Messages.showInputDialog(
            project,
            "Ushbu port uchun nom bering (masalan: Kafka, Frontend):",
            "Service Name",
            Messages.getQuestionIcon()
        ) ?: ("Custom Service (" + port + ")")

        settings.activeProfile.forwardedPorts.add(PortMapping(port, port, serviceName))
        if (connectionManager.isConnected) {
            connectionManager.startPortForwarding()
        }
        updatePortsTableData()
    }

    private fun rescueDiskSpace() {
        if (!connectionManager.isConnected) {
            log("[WARNING] Serverga ulanmagansiz! Avval 'Connect' tugmasini bosing.\n", true)
            return
        }

        val confirm = Messages.showYesNoDialog(
            project,
            "Serverdagi foydalanilmayotgan eski Docker image, to'xtagan container va build keshlari tozalanadi.\nBu diskda ko'p GB joy ochib beradi.\n\nDavom etasizmi?",
            "Rescue Disk Space (Docker Prune)",
            Messages.getQuestionIcon()
        )
        if (confirm != Messages.YES) return

        log("[DISK CLEANUP] docker system prune -af --volumes bajarilmoqda...\n")
        dockerManager.pruneDockerSystem(
            onOutput = { log(it) },
            onComplete = {
                log("[DISK CLEANUP COMPLETE] Disk tozalandi!\n")
                connectionManager.notifyUser("Remote Flow: Disk Tozalandi", "Docker kesh va eski imagelar tozalanib, diskda joy ochildi!", NotificationType.INFORMATION)
                checkServerResources()
            }
        )
    }

    private fun getSelectedContainerName(): String? {
        val row = dockerTable.selectedRow
        if (row < 0) {
            Messages.showWarningDialog(project, "Iltimos, jadvaldan konteynerni tanlang!", "Konteyner Tanlanmagan")
            return null
        }
        return dockerTableModel.getValueAt(row, 0) as? String
    }

    private fun refreshDockerContainers() {
        if (!connectionManager.isConnected) {
            log("[WARNING] Serverga ulanmagansiz! Avval 'Connect' tugmasini bosing.\n", true)
            return
        }

        dockerManager.fetchContainerList { list ->
            ApplicationManager.getApplication().invokeLater {
                dockerTableModel.rowCount = 0
                for (c in list) {
                    dockerTableModel.addRow(arrayOf(c.name, c.image, c.status, c.ports))
                }
                log("[DOCKER] " + list.size + " ta konteyner ro'yxati yangilandi.\n")
            }
        }
    }

    private fun log(message: String, isError: Boolean = false) {
        val serverName = settings.activeProfileOrNull?.name ?: ""
        val category = when {
            message.contains("[DOCKER") -> uz.remote.flow.logging.LogCategory.DOCKER
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
            log("[WARNING] Server profili mavjud emas! Avval sozlamalardan server qo'shing.\n", true)
            ShowSettingsUtil.getInstance().showSettingsDialog(project, RemoteFlowConfigurable::class.java)
            return
        }
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
        monitorScheduledTask?.cancel(false)
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
                        if (connectionManager.isConnected) {
                            checkServerResources(silent = true)
                        }
                    }, 5, 5, TimeUnit.SECONDS)
                }
                else -> { // "REALTIME_3S" or "REALTIME"
                    monitorStatusLabel.text = "● Live (3s)"
                    monitorStatusLabel.foreground = JBColor(Color(16, 185, 129), Color(16, 185, 129))
                    checkServerResources(silent = true)
                    monitorScheduledTask = monitorExecutor.scheduleWithFixedDelay({
                        if (connectionManager.isConnected) {
                            checkServerResources(silent = true)
                        }
                    }, 3, 3, TimeUnit.SECONDS)
                }
            }
        }
    }

    private fun checkServerResources(silent: Boolean = false) {
        if (!connectionManager.isConnected) {
            if (!silent) {
                log("[WARNING] Serverga ulanmagansiz! Avval 'Connect' tugmasini bosing.\n", true)
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

                    if (metrics.rawDockerStats.isNotBlank()) {
                        dockerStatsArea.text = metrics.rawDockerStats
                    }
                }
            },
            onLog = { if (!silent) log(it) },
            onComplete = { success ->
                if (!success && !silent) {
                    log("[ERROR] Server resurslarini olishda xatolik yuz berdi.\n", true)
                }
            }
        )
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
            log("[WARNING] Server profili mavjud emas! Avval sozlamalardan server qo'shing.\n", true)
            ShowSettingsUtil.getInstance().showSettingsDialog(project, RemoteFlowConfigurable::class.java)
            return
        }
        syncManager.syncSingleServer(
            profile = p,
            onLog = { log(it) },
            onComplete = { success ->
                if (success) {
                    log("[SYNC SUCCESS] Files synchronized successfully to ${p.name}!\n")
                    connectionManager.notifyUser("Remote Flow", "Fayllar ${p.name} serveriga muvaffaqiyatli yuklandi!", NotificationType.INFORMATION)
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
                    connectionManager.notifyUser("Remote Flow", "Barcha serverlarga sinxronizatsiya yakunlandi!", NotificationType.INFORMATION)
                } else {
                    log("[PARALLEL SYNC WARNING] Some sync tasks reported errors.\n", true)
                }
            }
        )
    }

    private fun dockerUp() {
        log("[DOCKER] Running docker compose up -d --build...\n")
        dockerManager.composeUp(
            build = true,
            onOutput = { log(it) },
            onComplete = { code ->
                log("[DOCKER] Compose Up finished with exit code $code\n")
                pingApiHealth()
            }
        )
    }

    private fun dockerDown() {
        log("[DOCKER] Running docker compose down...\n")
        dockerManager.composeDown(
            onOutput = { log(it) },
            onComplete = { code -> log("[DOCKER] Compose Down finished with exit code $code\n") }
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
        val left = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
        left.add(JBLabel("Target Port:"))
        val portField = JBTextField("8080", 6)
        left.add(portField)

        val presets = listOf("8080", "5005", "80", "443", "5432", "6379")
        for (pr in presets) {
            val b = JButton(pr)
            b.preferredSize = Dimension(b.preferredSize.width, 24)
            b.addActionListener { portField.text = pr }
            left.add(b)
        }
        top.add(left, BorderLayout.WEST)

        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 2))
        val statusText = JBLabel("Ready")
        statusText.font = statusText.font.deriveFont(Font.PLAIN, 11f)

        val btnCheck = JButton("🔍 Check Port")
        btnCheck.toolTipText = "Port band yoki bo'shligini tekshirish (lsof / fuser / ss)"
        btnCheck.addActionListener {
            val port = portField.text.trim()
            if (port.isBlank()) return@addActionListener
            if (!connectionManager.isConnected) {
                Messages.showWarningDialog(project, "Serverga ulanmagansiz!", "Port Inspector")
                return@addActionListener
            }
            statusText.text = "Checking port $port..."
            val cmd = "fuser -v $port/tcp 2>&1 || lsof -i :$port -P -n 2>&1 || ss -tulpn | grep :$port 2>&1 || echo 'Port $port bo\\'sh (jarayon yo\\'q)'"
            connectionManager.executeRemoteCommand(
                cmd = cmd,
                workingDir = "",
                onOutput = { line ->
                    logService.log("[PORT $port CHECK] $line", uz.remote.flow.logging.LogCategory.SSH, settings.activeProfile.name)
                },
                onComplete = { _ ->
                    ApplicationManager.getApplication().invokeLater {
                        statusText.text = "Port $port tekshirildi (Log oynaga qarang)"
                        Messages.showInfoMessage(project, "Port $port bo'yicha ma'lumot pastki 'Remote Flow Log' oynasiga chiqarildi.", "Port Check")
                    }
                }
            )
        }
        right.add(btnCheck)

        val btnKill = JButton("🛑 Kill Process on Port")
        btnKill.font = btnKill.font.deriveFont(Font.BOLD)
        btnKill.foreground = JBColor.RED
        btnKill.toolTipText = "Ushbu portni band qilgan jarayonni majburan to'xtatish (SIGKILL)"
        btnKill.addActionListener {
            val port = portField.text.trim()
            if (port.isBlank()) return@addActionListener
            if (!connectionManager.isConnected) {
                Messages.showWarningDialog(project, "Serverga ulanmagansiz!", "Process Killer")
                return@addActionListener
            }
            val confirm = Messages.showYesNoDialog(
                project,
                "Serverdagi $port portini band qilgan barcha jarayonlar majburan to'xtatiladi (SIGKILL).\n\nDavom etasizmi?",
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
                        statusText.text = "Port $port bo'shatildi!"
                        connectionManager.notifyUser(
                            "Port Killer 🛑",
                            "Serverdagi $port portidagi jarayon to'xtatildi!",
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
        val card = JPanel(BorderLayout(0, 4))
        card.border = IdeBorderFactory.createTitledBorder("Masofaviy Git Holati (Remote Git Status)", false)

        val infoPanel = JPanel(GridLayout(1, 3, 8, 0))
        infoPanel.border = JBUI.Borders.empty(2, 6)

        lblGitBranch.font = lblGitBranch.font.deriveFont(Font.BOLD, 12f)
        lblGitBranch.foreground = JBColor(Color(16, 185, 129), Color(16, 185, 129))
        infoPanel.add(lblGitBranch)

        lblGitCommit.font = lblGitCommit.font.deriveFont(Font.PLAIN, 11f)
        infoPanel.add(lblGitCommit)

        lblGitStatus.font = lblGitStatus.font.deriveFont(Font.PLAIN, 11f)
        infoPanel.add(lblGitStatus)

        val btnRow = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))
        val btnRefreshGit = JButton("⟳ Check Git")
        btnRefreshGit.toolTipText = "Masofaviy Git branch va statusni tekshirish"
        btnRefreshGit.addActionListener { checkRemoteGitStatus() }
        btnRow.add(btnRefreshGit)

        val btnGitPull = JButton("⬇ Git Pull")
        btnGitPull.toolTipText = "Serverda eng so'nggi o'zgarishlarni tortib olish (git pull)"
        btnGitPull.addActionListener { executeRemoteGitPull() }
        btnRow.add(btnGitPull)

        val btnGitStashPull = JButton("🔄 Stash & Pull")
        btnGitStashPull.toolTipText = "Serverdagi o'zgarishlarni stash qilib, pull qilish"
        btnGitStashPull.addActionListener { executeRemoteGitStashPull() }
        btnRow.add(btnGitStashPull)

        card.add(infoPanel, BorderLayout.CENTER)
        card.add(btnRow, BorderLayout.EAST)
        return card
    }

    private fun checkRemoteGitStatus() {
        val p = settings.activeProfileOrNull ?: return
        if (!connectionManager.isConnected) {
            lblGitBranch.text = "🌿 Git: Offline"
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
                        lblGitCommit.text = if (commit.isNotBlank()) "📌 $commit" else "📌 -"
                        lblGitStatus.text = if (statusLines.isEmpty()) "✔ Clean" else "⚠ ${statusLines.size} ta o'zgargan fayl"
                        lblGitStatus.foreground = if (statusLines.isEmpty()) JBColor(Color(16, 185, 129), Color(16, 185, 129)) else JBColor.ORANGE
                    } else {
                        lblGitBranch.text = "🌿 Git: Repozitoriya emas"
                        lblGitCommit.text = "-"
                        lblGitStatus.text = "-"
                    }
                }
            }
        )
    }

    private fun executeRemoteGitPull() {
        val p = settings.activeProfileOrNull ?: return
        if (!connectionManager.isConnected) {
            log("[WARNING] Serverga ulanmagansiz! Avval 'Connect' tugmasini bosing.\n", true)
            return
        }
        log("[GIT PULL] Serverda git pull buyrug'i bajarilmoqda...\n")
        connectionManager.executeRemoteCommand(
            cmd = "git pull",
            workingDir = p.remoteProjectPath,
            onOutput = { log(it) },
            onComplete = { code ->
                log("[GIT PULL FINISHED] Exit code: $code\n")
                checkRemoteGitStatus()
                connectionManager.notifyUser("Git Pull", "Serverda git pull bajarildi (Exit code: $code)", NotificationType.INFORMATION)
            }
        )
    }

    private fun executeRemoteGitStashPull() {
        val p = settings.activeProfileOrNull ?: return
        if (!connectionManager.isConnected) {
            log("[WARNING] Serverga ulanmagansiz! Avval 'Connect' tugmasini bosing.\n", true)
            return
        }
        log("[GIT STASH & PULL] Serverda stash va pull buyrug'i bajarilmoqda...\n")
        connectionManager.executeRemoteCommand(
            cmd = "git stash && git pull && git stash pop || git pull",
            workingDir = p.remoteProjectPath,
            onOutput = { log(it) },
            onComplete = { code ->
                log("[GIT STASH & PULL FINISHED] Exit code: $code\n")
                checkRemoteGitStatus()
                connectionManager.notifyUser("Git Stash & Pull", "Serverda git stash & pull bajarildi", NotificationType.INFORMATION)
            }
        )
    }

    private fun openRemoteConfigManager(initialFile: String? = null) {
        val p = settings.activeProfileOrNull ?: return
        val dialog = uz.remote.flow.config.RemoteConfigManagerDialog(project, p, initialFile)
        dialog.show()
    }
}
