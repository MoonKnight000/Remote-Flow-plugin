package uz.remote.flow.ui

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.IdeBorderFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
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
import javax.swing.*
import javax.swing.table.DefaultTableModel

class RemoteFlowToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = RemoteFlowMainPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}

class RemoteFlowMainPanel(private val project: Project) : JPanel(BorderLayout(0, 4)) {

    private val settings = RemoteFlowSettings.getInstance(project)
    private val connectionManager = RemoteConnectionManager.getInstance(project)
    private val dockerManager = DockerComposeManager(project)
    private val syncManager = FastSyncManager(project)
    private val statsManager = ServerStatsManager(project)

    private val consoleView: ConsoleView = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
    private val terminalConsoleView: ConsoleView = TextConsoleBuilderFactory.getInstance().createBuilder(project).console

    // Header Controls
    private val profileComboBox = JComboBox<ServerProfile>()
    private val btnAddServer = JButton("➕")
    private val btnEditServer = JButton("✏ Edit")
    private val btnConnectToggle = JButton("⚡ Connect")
    private val statusDot = JLabel("● ")
    private val statusText = JLabel("Disconnected")
    private val btnSyncHeader = JButton("🔄 Sync")
    private val btnPingApi = JButton("🩺 Ping API")
    private val apiHealthLabel = JLabel("API: --")
    private val btnSettings = JButton("⚙ Settings")

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

        // 5 Logical Tabs
        val tabbedPane = JBTabbedPane()
        tabbedPane.addTab("📊 Dashboard", createDashboardTab())
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

        refreshProfileComboBox()
        loadProfileData(settings.activeProfile)
        updateConnectionStateUi(connectionManager.isConnected)
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
            val current = settings.activeProfile
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

        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 2))

        btnSyncHeader.toolTipText = "Kodni tezkor faol serverga yuklash"
        btnSyncHeader.addActionListener { syncFiles() }
        right.add(btnSyncHeader)

        btnPingApi.addActionListener { pingApiHealth() }
        right.add(btnPingApi)
        right.add(apiHealthLabel)

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
        if (settings.activeProfileIndex in settings.profiles.indices) {
            profileComboBox.selectedIndex = settings.activeProfileIndex
        }
    }

    private fun loadProfileData(p: ServerProfile) {
        runCommandField.text = p.runCommand
        debugCommandField.text = p.debugCommand
        updateOverviewSummary(p)
        updatePortsTableData()
    }

    private fun updateOverviewSummary(p: ServerProfile) {
        lblServerHost.text = p.host + ":" + p.port
        lblServerUser.text = p.user + " (" + p.authType.name + ")"
        lblServerRemoteDir.text = p.remoteProjectPath
    }

    private fun updateConnectionStateUi(connected: Boolean) {
        val p = settings.activeProfile
        if (connected) {
            btnConnectToggle.text = "⏹ Disconnect"
            btnConnectToggle.foreground = JBColor.RED
            statusDot.foreground = JBColor(Color(16, 185, 129), Color(16, 185, 129))
            statusText.text = "Connected"
            statusText.foreground = JBColor(Color(16, 185, 129), Color(16, 185, 129))
        } else {
            btnConnectToggle.text = "⚡ Connect"
            btnConnectToggle.foreground = JBColor.foreground()
            statusDot.foreground = JBColor.GRAY
            statusText.text = "Disconnected"
            statusText.foreground = JBColor.foreground()
        }
        updatePortsTableData()
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
        gbc.gridx = 1; gbc.gridwidth = 3; gbc.weightx = 1.0; summaryCard.add(lblServerRemoteDir, gbc)
        gbc.gridwidth = 1

        top.add(summaryCard)
        top.add(Box.createVerticalStrut(6))

        // Resource Meters Card
        val metersCard = JPanel(BorderLayout(0, 6))
        metersCard.border = IdeBorderFactory.createTitledBorder("Hardware Resource Monitor", false)

        val metersToolbar = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))
        val btnRefresh = JButton("⟳ Refresh Resource Stats")
        btnRefresh.font = btnRefresh.font.deriveFont(Font.BOLD)
        btnRefresh.addActionListener { checkServerResources() }
        metersToolbar.add(btnRefresh)
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

        top.add(quickActionCard)

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

        // Console in Center
        val consolePanel = JPanel(BorderLayout(0, 4))
        consolePanel.border = IdeBorderFactory.createTitledBorder("Live Execution Output Console", false)

        val consoleToolbar = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0))
        val btnAll = JButton("All")
        btnAll.addActionListener { log("[FILTER] Showing all\n") }
        consoleToolbar.add(btnAll)

        val btnClear = JButton("Clear Log")
        btnClear.addActionListener { consoleView.clear() }
        consoleToolbar.add(btnClear)

        consolePanel.add(consoleToolbar, BorderLayout.NORTH)
        consolePanel.add(consoleView.component, BorderLayout.CENTER)

        panel.add(top, BorderLayout.NORTH)
        panel.add(consolePanel, BorderLayout.CENTER)
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

        val btnLaunchExternal = JButton("🚀 Launch External Terminal")
        btnLaunchExternal.font = btnLaunchExternal.font.deriveFont(Font.BOLD)
        btnLaunchExternal.addActionListener { openExternalTerminal() }

        topBar.add(inputPanel, BorderLayout.CENTER)
        topBar.add(btnLaunchExternal, BorderLayout.EAST)

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
        val p = settings.activeProfile
        val cmd = "start powershell -NoExit -Command \"Write-Host 'Connecting to " + p.name + "...' -ForegroundColor Cyan; ssh -p " + p.port + " " + p.user + "@" + p.host + "\""
        try {
            Runtime.getRuntime().exec(arrayOf("cmd.exe", "/c", cmd))
            log("[TERMINAL] External SSH terminal launched for " + p.user + "@" + p.host + "\n")
        } catch (e: Exception) {
            log("[TERMINAL ERROR] " + e.message + "\n", true)
        }
    }

    private fun executeRemoteRun() {
        if (!connectionManager.isConnected) {
            log("[WARNING] Serverga ulanmagansiz! Avval 'Connect' tugmasini bosing.\n", true)
            return
        }

        val p = settings.activeProfile
        p.runCommand = runCommandField.text.trim()
        val cmd = p.runCommand
        log("[REMOTE RUN] 1. Eng yangi kodlar serverga sinxronlanmoqda...\n")

        syncManager.syncSingleServer(
            profile = p,
            onLog = { log(it) },
            onComplete = { success ->
                if (!success) {
                    log("[REMOTE RUN WARNING] Sinxronizatsiyada ogohlantirish bo'ldi, buyruq bajarilmoqda...\n", true)
                }
                log("[REMOTE RUN] 2. Masofaviy buyruq serverda bajarilmoqda: $cmd\n")
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
        val cmd = p.debugCommand
        log("[REMOTE DEBUG] 1. Kodlar serverga yuklanmoqda...\n")

        syncManager.syncSingleServer(
            profile = p,
            onLog = { log(it) },
            onComplete = { _ ->
                log("[REMOTE DEBUG] 2. Ilova debug rejimida (port 5005) serverda ishga tushirilmoqda: $cmd\n")
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
        ApplicationManager.getApplication().invokeLater {
            consoleView.print(message, if (isError) ConsoleViewContentType.ERROR_OUTPUT else ConsoleViewContentType.NORMAL_OUTPUT)
        }
    }

    private fun connectSSH() {
        val p = settings.activeProfile
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

    private fun checkServerResources() {
        if (!connectionManager.isConnected) {
            log("[WARNING] Serverga ulanmagansiz! Avval 'Connect' tugmasini bosing.\n", true)
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
            onLog = { log(it) },
            onComplete = { success ->
                if (!success) {
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
        val p = settings.activeProfile
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
}
