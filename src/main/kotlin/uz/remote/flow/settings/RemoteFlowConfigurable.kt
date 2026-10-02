package uz.remote.flow.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.IdeBorderFactory
import com.intellij.ui.JBColor
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.*
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import uz.remote.flow.ssh.*
import uz.remote.flow.ui.RemoteDirectoryChooserDialog
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.table.DefaultTableModel

class RemoteFlowConfigurable(private val project: Project) : Configurable {

    private val settings = RemoteFlowSettings.getInstance(project)
    private val connectionManager = RemoteConnectionManager.getInstance(project)

    // Cloned working copy of profiles for cancel/apply semantics
    private val workingProfiles = mutableListOf<ServerProfile>()
    private var selectedIndex = 0

    // UI components
    private val profileListModel = DefaultListModel<ServerProfile>()
    private val profileList = JBList(profileListModel)

    private val nameField = JBTextField()
    private val hostField = JBTextField()
    private val portField = JBTextField()
    private val authTypeBox = JComboBox(arrayOf("Password", "SSH Private Key (.pem / id_rsa)"))
    private val userField = JBTextField()
    private val passwordField = JBPasswordField()
    private val keyPathField = TextFieldWithBrowseButton()

    private val credentialLabel = JBLabel("Password:")
    private val credentialCardLayout = CardLayout()
    private val credentialCardPanel = JPanel(credentialCardLayout)

    private val localFolderField = TextFieldWithBrowseButton()
    private val remotePathField = JBTextField()
    private val btnBrowseRemote = JButton("📁 Browse Remote...")
    private val btnResetRemoteTemplate = JButton("⟳")

    private val excludePatternsField = JBTextField()
    private val btnResetExcludes = JButton("⟳ Default")
    private val rsyncPathField = TextFieldWithBrowseButton(JBTextField())
    private val btnAutoDetectRsync = JButton("Auto-Detect", AllIcons.Actions.Search)

    private val runCommandField = JBTextField()
    private val debugCommandField = JBTextField()

    private val javaHomeField = JBTextField()
    private val btnAutoDetectJava = JButton("Detect Remote Java", AllIcons.Actions.Search)
    private val chkAutoSyncOnSave = JBCheckBox("⚡ Real-Time Auto-Sync (Sync when typing pauses & external AI changes)", true).apply {
        toolTipText = "Automatically detects and uploads code changes after you finish typing or when external AI coding tools modify files."
    }
    private val autoSyncDelayField = JBTextField("1500", 5)
    private val chkRouteStandardRun = JBCheckBox("🔄 Route standard IDE Run/Debug (Application / Spring Boot) to active remote server", true)
    private val chkOpenBrowser = JBCheckBox("🌐 Auto-open browser when application is ready", false)
    private val browserUrlField = JBTextField()

    private val autoReconnectCheck = JBCheckBox("Auto-Reconnect & Keep-Alive", true)
    private val chkEnableAgentBridge = JBCheckBox("🤖 Enable AI Agent Bridge & Global CLI (rf in ~/.remote-flow/bin)", true).apply {
        toolTipText = "Provides global 'rf' CLI command for AI Coding Agents and terminal users. Zero files are placed in your project directory."
    }
    private val btnTestConnection = JButton("Test Connection", AllIcons.Actions.Execute)

    private lateinit var portsTableModel: DefaultTableModel
    private lateinit var portsTable: JBTable

    private var mainPanel: JPanel? = null

    override fun getDisplayName(): String = "Remote Flow"

    override fun createComponent(): JComponent {
        resetWorkingCopy()

        val root = JPanel(BorderLayout(12, 0))
        root.border = JBUI.Borders.empty(8)

        // Setup credentials card
        val pwdCard = JPanel(BorderLayout())
        pwdCard.add(passwordField, BorderLayout.CENTER)
        val keyCard = JPanel(BorderLayout())
        keyCard.add(keyPathField, BorderLayout.CENTER)
        credentialCardPanel.add(pwdCard, "PASSWORD")
        credentialCardPanel.add(keyCard, "KEY")

        authTypeBox.addActionListener {
            val isKey = authTypeBox.selectedIndex == 1
            credentialLabel.text = if (isKey) "Private Key:" else "Password:"
            credentialCardLayout.show(credentialCardPanel, if (isKey) "KEY" else "PASSWORD")
        }

        // Browse listeners
        localFolderField.addBrowseFolderListener(
            "Select Local Project Directory",
            "Choose directory to sync with remote server",
            project,
            FileChooserDescriptorFactory.createSingleFolderDescriptor()
        )

        keyPathField.addBrowseFolderListener(
            "Select SSH Private Key",
            "Choose .pem or id_rsa file",
            project,
            FileChooserDescriptorFactory.createSingleFileDescriptor()
        )

        btnResetRemoteTemplate.toolTipText = "Reset to default template (/<user>/remote-flow/<local-folder>)"
        btnResetRemoteTemplate.addActionListener {
            val u = userField.text.trim().ifBlank { "root" }
            val fName = resolveLocalFolderName()
            remotePathField.text = if (u == "root") "/root/remote-flow/$fName" else "/home/$u/remote-flow/$fName"
        }

        btnResetExcludes.toolTipText = "Reset to default exclude patterns"
        btnResetExcludes.addActionListener {
            excludePatternsField.text = uz.remote.flow.ssh.defaultExcludes()
        }

        rsyncPathField.addBrowseFolderListener(
            "Select Rsync Executable",
            "Choose rsync or rsync.exe",
            project,
            FileChooserDescriptorFactory.createSingleFileDescriptor()
        )

        (rsyncPathField.textField as JBTextField).emptyText.text = "From IntelliJ IDEA Rsync settings (automatic)"
        btnAutoDetectRsync.toolTipText = "Auto-detect rsync.exe from IntelliJ IDEA or system PATH"
        btnAutoDetectRsync.addActionListener {
            val detected = uz.remote.flow.ssh.detectRsyncPath()
            if (detected.isNotBlank()) {
                rsyncPathField.text = detected
                val idePath = uz.remote.flow.sync.IntelliJRsyncConfigProvider.getRsyncConfig().rsyncPath
                val origin = if (detected.equals(idePath, ignoreCase = true)) " (from IntelliJ IDEA Tools -> Rsync settings)" else ""
                Messages.showInfoMessage(project, "Rsync detected$origin:\n$detected", "Rsync Detection")
            } else {
                Messages.showWarningDialog(project, "Rsync executable not found in system PATH. You can browse manually or SFTP will be used automatically.", "Rsync Not Found")
            }
        }

        localFolderField.textField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            private fun syncRemote() {
                val fName = resolveLocalFolderName()
                val u = userField.text.trim().ifBlank { "root" }
                val curRemote = remotePathField.text.trim()
                if (curRemote.isBlank() || curRemote.matches(Regex(".*/remote-flow(/.*)?$"))) {
                    remotePathField.text = if (u == "root") "/root/remote-flow/$fName" else "/home/$u/remote-flow/$fName"
                }
            }
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) { syncRemote() }
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) { syncRemote() }
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) { syncRemote() }
        })

        btnBrowseRemote.addActionListener {
            saveCurrentSelection()
            val current = getCurrentProfile() ?: return@addActionListener
            val targetRemote = remotePathField.text.trim().ifBlank { "/root" }

            connectionManager.checkDirectoryExists(current, targetRemote) { exists, _ ->
                ApplicationManager.getApplication().invokeLater {
                    if (exists) {
                        val dialog = RemoteDirectoryChooserDialog(project, targetRemote, current)
                        if (dialog.showAndGet()) {
                            remotePathField.text = dialog.selectedPath
                            current.remoteProjectPath = dialog.selectedPath
                        }
                    } else {
                        promptCreateRemoteDir(current, targetRemote) { created ->
                            val openPath = if (created) targetRemote else "/root"
                            val dialog = RemoteDirectoryChooserDialog(project, openPath, current)
                            if (dialog.showAndGet()) {
                                remotePathField.text = dialog.selectedPath
                                current.remoteProjectPath = dialog.selectedPath
                            }
                        }
                    }
                }
            }
        }

        btnTestConnection.addActionListener {
            saveCurrentSelection()
            val current = getCurrentProfile() ?: return@addActionListener
            val targetRemote = remotePathField.text.trim()

            connectionManager.testConnection(current, checkRemoteDir = targetRemote) { ok, dirExists, msg ->
                ApplicationManager.getApplication().invokeLater {
                    if (!ok) {
                        Messages.showErrorDialog(project, msg, "Connection Error")
                        return@invokeLater
                    }

                    if (targetRemote.isNotBlank() && !dirExists) {
                        promptCreateRemoteDir(current, targetRemote)
                    } else if (targetRemote.isNotBlank()) {
                        Messages.showInfoMessage(project, "$msg\n\nRemote directory exists: $targetRemote", "Connection Successful")
                    } else {
                        Messages.showInfoMessage(project, msg, "Connection Successful")
                    }
                }
            }
        }

        // Left Profiles List with Toolbar (+ / - / Duplicate)
        profileList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        profileList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                val newIdx = profileList.selectedIndex
                if (newIdx >= 0 && newIdx < workingProfiles.size && newIdx != selectedIndex) {
                    saveCurrentSelection()
                    selectedIndex = newIdx
                    loadProfileToForm(workingProfiles[selectedIndex])
                }
            }
        }

        val decorator = ToolbarDecorator.createDecorator(profileList)
            .setAddAction {
                saveCurrentSelection()
                val newName = "Server " + (workingProfiles.size + 1)
                val fName = resolveLocalFolderName()
                val localPath = localFolderField.text.trim().ifBlank { project.basePath ?: "" }
                val newP = ServerProfile(
                    name = newName,
                    host = "192.168.1.100",
                    localProjectPath = localPath,
                    remoteProjectPath = "/root/remote-flow/$fName"
                )
                workingProfiles.add(newP)
                profileListModel.addElement(newP)
                profileList.selectedIndex = workingProfiles.size - 1
            }
            .setRemoveAction {
                val curIdx = profileList.selectedIndex
                if (curIdx >= 0 && curIdx < workingProfiles.size) {
                    workingProfiles.removeAt(curIdx)
                    profileListModel.remove(curIdx)
                    if (workingProfiles.isNotEmpty()) {
                        selectedIndex = curIdx.coerceAtMost(workingProfiles.size - 1)
                        profileList.selectedIndex = selectedIndex
                        loadProfileToForm(workingProfiles[selectedIndex])
                    } else {
                        selectedIndex = 0
                        clearForm()
                    }
                }
            }
            .addExtraAction(object : com.intellij.openapi.actionSystem.AnAction("Duplicate Profile", "Duplicate selected profile", com.intellij.icons.AllIcons.Actions.Copy) {
                override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                    saveCurrentSelection()
                    val cur = getCurrentProfile() ?: return
                    val copy = cur.duplicateProfile()
                    workingProfiles.add(copy)
                    profileListModel.addElement(copy)
                    profileList.selectedIndex = workingProfiles.size - 1
                }
            })

        val listPanel = decorator.createPanel()
        listPanel.preferredSize = Dimension(220, 400)
        listPanel.border = IdeBorderFactory.createTitledBorder("Server Profiles", false)

        // Right Detail Editor
        val detailPanel = createDetailPanel()

        val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listPanel, detailPanel)
        splitPane.dividerLocation = 220
        splitPane.resizeWeight = 0.25

        root.add(splitPane, BorderLayout.CENTER)
        mainPanel = root

        if (workingProfiles.isNotEmpty()) {
            profileList.selectedIndex = selectedIndex.coerceIn(workingProfiles.indices)
            loadProfileToForm(workingProfiles[profileList.selectedIndex])
        } else {
            clearForm()
        }

        return root
    }

    private fun clearForm() {
        nameField.text = ""
        hostField.text = ""
        portField.text = "22"
        authTypeBox.selectedIndex = 0
        userField.text = "root"
        passwordField.text = ""
        keyPathField.text = ""
        localFolderField.text = project.basePath ?: ""
        remotePathField.text = ""
        excludePatternsField.text = uz.remote.flow.ssh.defaultExcludes()
        rsyncPathField.text = ""
        javaHomeField.text = ""
        runCommandField.text = ""
        debugCommandField.text = ""
        chkAutoSyncOnSave.isSelected = true
        autoSyncDelayField.text = "1500"
        if (::portsTableModel.isInitialized) {
            portsTableModel.rowCount = 0
        }
    }

    private fun resolveLocalFolderName(): String {
        val path = localFolderField.text.trim().replace('\\', '/').trimEnd('/')
        val folder = path.substringAfterLast('/')
        return folder.ifBlank { project.name.trim().ifBlank { "app" } }
    }

    private fun promptCreateRemoteDir(profile: ServerProfile, remotePath: String, onFinished: ((Boolean) -> Unit)? = null) {
        val choice = Messages.showYesNoDialog(
            project,
            "Remote directory was not found on server:\n$remotePath\n\nWould you like to create this directory on the remote server now?",
            "Remote Directory Not Found",
            "Yes, Create",
            "No",
            Messages.getQuestionIcon()
        )
        if (choice == Messages.YES) {
            connectionManager.createDirectory(profile, remotePath) { success, err ->
                ApplicationManager.getApplication().invokeLater {
                    if (success) {
                        Messages.showInfoMessage(project, "Remote directory created successfully on server:\n$remotePath", "Directory Created")
                        onFinished?.invoke(true)
                    } else {
                        Messages.showErrorDialog(project, "Failed to create directory: " + (err ?: "Permission denied"), "Error")
                        onFinished?.invoke(false)
                    }
                }
            }
        } else {
            onFinished?.invoke(false)
        }
    }

    private fun createDetailPanel(): JPanel {
        val panel = JPanel(BorderLayout(0, 10))
        panel.border = JBUI.Borders.empty(4, 12, 4, 4)

        val form = JPanel(GridBagLayout())
        val gbc = GridBagConstraints()
        gbc.insets = JBUI.insets(3, 4, 3, 4)
        gbc.anchor = GridBagConstraints.WEST
        gbc.fill = GridBagConstraints.HORIZONTAL

        // Row 0: Name
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0; form.add(JBLabel("Profile Name:"), gbc)
        gbc.gridx = 1; gbc.gridwidth = 3; gbc.weightx = 1.0; form.add(nameField, gbc)
        gbc.gridwidth = 1

        // Row 1: Host & Port
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0; form.add(JBLabel("Host IP:"), gbc)
        gbc.gridx = 1; gbc.weightx = 0.7; form.add(hostField, gbc)
        gbc.gridx = 2; gbc.weightx = 0.0; form.add(JBLabel("Port:"), gbc)
        gbc.gridx = 3; gbc.weightx = 0.3; form.add(portField, gbc)

        // Row 2: Auth Type
        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0.0; form.add(JBLabel("Auth Type:"), gbc)
        gbc.gridx = 1; gbc.gridwidth = 3; gbc.weightx = 1.0; form.add(authTypeBox, gbc)
        gbc.gridwidth = 1

        // Row 3: User
        gbc.gridx = 0; gbc.gridy = 3; gbc.weightx = 0.0; form.add(JBLabel("Username:"), gbc)
        gbc.gridx = 1; gbc.gridwidth = 3; gbc.weightx = 1.0; form.add(userField, gbc)
        gbc.gridwidth = 1

        // Row 4: Password / Key
        gbc.gridx = 0; gbc.gridy = 4; gbc.weightx = 0.0; form.add(credentialLabel, gbc)
        gbc.gridx = 1; gbc.gridwidth = 3; gbc.weightx = 1.0; form.add(credentialCardPanel, gbc)
        gbc.gridwidth = 1

        // Row 5: Local Dir
        gbc.gridx = 0; gbc.gridy = 5; gbc.weightx = 0.0; form.add(JBLabel("Local Dir:"), gbc)
        gbc.gridx = 1; gbc.gridwidth = 3; gbc.weightx = 1.0; form.add(localFolderField, gbc)
        gbc.gridwidth = 1

        // Row 6: Remote Dir
        gbc.gridx = 0; gbc.gridy = 6; gbc.weightx = 0.0; form.add(JBLabel("Remote Dir:"), gbc)
        val remotePanel = JPanel(BorderLayout(4, 0))
        remotePanel.add(remotePathField, BorderLayout.CENTER)
        val remoteBtns = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0))
        remoteBtns.add(btnResetRemoteTemplate)
        remoteBtns.add(btnBrowseRemote)
        remotePanel.add(remoteBtns, BorderLayout.EAST)
        gbc.gridx = 1; gbc.gridwidth = 3; gbc.weightx = 1.0; form.add(remotePanel, gbc)
        gbc.gridwidth = 1

        // Row 7: Exclude Paths
        gbc.gridx = 0; gbc.gridy = 7; gbc.weightx = 0.0; form.add(JBLabel("Exclude Paths:"), gbc)
        val excludePanel = JPanel(BorderLayout(4, 0))
        excludePatternsField.toolTipText = "e.g.: .git, .gradle, build, .idea, out, target, node_modules, *.log"
        excludePanel.add(excludePatternsField, BorderLayout.CENTER)
        excludePanel.add(btnResetExcludes, BorderLayout.EAST)
        gbc.gridx = 1; gbc.gridwidth = 3; gbc.weightx = 1.0; form.add(excludePanel, gbc)
        gbc.gridwidth = 1

        // Row 8: Rsync Executable
        gbc.gridx = 0; gbc.gridy = 8; gbc.weightx = 0.0; form.add(JBLabel("Rsync Executable:"), gbc)
        val rsyncPanel = JPanel(BorderLayout(4, 0))
        rsyncPathField.textField.toolTipText = "If empty, detected automatically from IntelliJ IDEA or system PATH"
        rsyncPanel.add(rsyncPathField, BorderLayout.CENTER)
        val rsyncBtns = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0))
        rsyncBtns.add(btnAutoDetectRsync)
        val btnOpenIdeRsync = JButton("⚙ IntelliJ Rsync")
        btnOpenIdeRsync.toolTipText = "IntelliJ IDEA Rsync settings (Tools -> Rsync)"
        btnOpenIdeRsync.addActionListener {
            try {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, "rsyncConfigurable")
                val ideCfg = uz.remote.flow.sync.IntelliJRsyncConfigProvider.getRsyncConfig(forceRefresh = true)
                if (rsyncPathField.text.isBlank() && ideCfg.rsyncPath.isNotBlank()) {
                    rsyncPathField.text = ideCfg.rsyncPath
                }
            } catch (_: Exception) {}
        }
        rsyncBtns.add(btnOpenIdeRsync)
        rsyncPanel.add(rsyncBtns, BorderLayout.EAST)
        gbc.gridx = 1; gbc.gridwidth = 3; gbc.weightx = 1.0; form.add(rsyncPanel, gbc)
        gbc.gridwidth = 1

        // Row 9: Remote Java (JAVA_HOME)
        gbc.gridx = 0; gbc.gridy = 9; gbc.weightx = 0.0; form.add(JBLabel("Remote Java:"), gbc)
        val javaPanel = JPanel(BorderLayout(4, 0))
        javaHomeField.emptyText.text = "Remote Java path / JAVA_HOME (e.g.: /usr/lib/jvm/java-17-openjdk-amd64)"
        javaPanel.add(javaHomeField, BorderLayout.CENTER)
        val javaBtns = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0))
        btnAutoDetectJava.toolTipText = "Auto-detect installed Java versions on remote server"
        btnAutoDetectJava.addActionListener {
            saveCurrentSelection()
            val cur = getCurrentProfile() ?: return@addActionListener
            btnAutoDetectJava.isEnabled = false
            btnAutoDetectJava.text = "Detecting..."
            connectionManager.detectRemoteJava(cur) { list ->
                ApplicationManager.getApplication().invokeLater {
                    btnAutoDetectJava.isEnabled = true
                    btnAutoDetectJava.text = "🔍 Detect Remote Java"
                    if (list.isEmpty()) {
                        Messages.showInfoMessage(project, "Could not auto-detect Java on remote server. Please specify the path manually (e.g.: /usr/lib/jvm/java-17-openjdk-amd64)", "Remote Java")
                    } else if (list.size == 1) {
                        javaHomeField.text = list[0]
                        cur.javaHome = list[0]
                        Messages.showInfoMessage(project, "Remote Java detected successfully:\n${list[0]}", "Java Detected")
                    } else {
                        val chosen = Messages.showEditableChooseDialog(
                            "Found the following Java versions on server. Select one:",
                            "Select Remote Java",
                            Messages.getQuestionIcon(),
                            list.toTypedArray(),
                            list[0],
                            null
                        )
                        if (!chosen.isNullOrBlank()) {
                            javaHomeField.text = chosen
                            cur.javaHome = chosen
                        }
                    }
                }
            }
        }
        javaBtns.add(btnAutoDetectJava)
        javaPanel.add(javaBtns, BorderLayout.EAST)
        gbc.gridx = 1; gbc.gridwidth = 3; gbc.weightx = 1.0; form.add(javaPanel, gbc)
        gbc.gridwidth = 1

        // Row 10: Commands
        gbc.gridx = 0; gbc.gridy = 10; gbc.weightx = 0.0; form.add(JBLabel("Run Command:"), gbc)
        gbc.gridx = 1; gbc.gridwidth = 3; gbc.weightx = 1.0; form.add(runCommandField, gbc)
        gbc.gridwidth = 1

        gbc.gridx = 0; gbc.gridy = 11; gbc.weightx = 0.0; form.add(JBLabel("Debug Command:"), gbc)
        gbc.gridx = 1; gbc.gridwidth = 3; gbc.weightx = 1.0; form.add(debugCommandField, gbc)
        gbc.gridwidth = 1

        // Row 12: Real-Time Auto-Sync
        val autoSyncPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        autoSyncPanel.add(chkAutoSyncOnSave)
        autoSyncPanel.add(JBLabel("  ↳ Typing pause delay: "))
        autoSyncPanel.add(autoSyncDelayField)
        autoSyncPanel.add(JBLabel("ms"))
        gbc.gridx = 0; gbc.gridy = 12; gbc.gridwidth = 4; gbc.weightx = 1.0
        form.add(autoSyncPanel, gbc)

        // Row 13: Route Standard Run
        chkRouteStandardRun.toolTipText = "When enabled, standard Application / Spring Boot Run & Debug actions execute on the active remote server instead of locally. HTTP requests (.http), tests, and scripts are excluded."
        gbc.gridx = 0; gbc.gridy = 13; gbc.gridwidth = 4; gbc.weightx = 1.0
        form.add(chkRouteStandardRun, gbc)

        // Row 14: Browser Auto-Open
        gbc.gridx = 0; gbc.gridy = 14; gbc.weightx = 0.0; form.add(chkOpenBrowser, gbc)
        val browserPanel = JPanel(BorderLayout(4, 0))
        browserUrlField.emptyText.text = "http://localhost:8080"
        browserPanel.add(JBLabel("URL: "), BorderLayout.WEST)
        browserPanel.add(browserUrlField, BorderLayout.CENTER)
        gbc.gridx = 1; gbc.gridwidth = 3; gbc.weightx = 1.0; form.add(browserPanel, gbc)
        gbc.gridwidth = 1

        // Row 15: AI Agent Bridge & Clean Files
        val aiBridgePanel = JPanel(BorderLayout(8, 0))
        aiBridgePanel.add(chkEnableAgentBridge, BorderLayout.WEST)
        val btnCleanProject = JButton("🧹 Clean Project Files", AllIcons.Actions.GC).apply {
            toolTipText = "Remove any legacy .remote-flow.port, rf, rf.cmd, rf.ps1, or agent rules from project root"
            addActionListener {
                uz.remote.flow.agent.RemoteFlowAgentBridgeService.getInstance(project).cleanLegacyProjectFiles()
                Messages.showInfoMessage(project, "Cleaned any legacy Remote Flow files from the project workspace. Project codes are clean!", "Clean Project Files")
            }
        }
        aiBridgePanel.add(btnCleanProject, BorderLayout.EAST)
        gbc.gridx = 0; gbc.gridy = 15; gbc.gridwidth = 4; gbc.weightx = 1.0
        form.add(aiBridgePanel, gbc)

        // Row 16: Buttons
        val btnRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4))
        btnRow.add(btnTestConnection)
        btnRow.add(autoReconnectCheck)
        gbc.gridx = 0; gbc.gridy = 16; gbc.gridwidth = 4; gbc.weightx = 1.0
        form.add(btnRow, gbc)

        // Ports Table
        val portsBox = JPanel(BorderLayout(0, 6))
        portsBox.border = IdeBorderFactory.createTitledBorder("Forwarded Ports (SSH Tunnels)", false)

        val portCols = arrayOf("Direction", "Local Port", "Remote Port", "Service Name")
        portsTableModel = object : DefaultTableModel(portCols, 0) {
            override fun isCellEditable(row: Int, column: Int): Boolean = true
        }
        portsTable = JBTable(portsTableModel)
        portsTable.rowHeight = 24
        portsTable.columnModel.getColumn(0).cellEditor = DefaultCellEditor(JComboBox(arrayOf("Local -> Host", "Host -> Local")))
        portsTable.columnModel.getColumn(0).preferredWidth = 110

        val portToolbar = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0))
        val lblPortHint = JBLabel("💡 Right-click or Del to remove")
        lblPortHint.font = lblPortHint.font.deriveFont(Font.ITALIC, 11f)
        lblPortHint.foreground = JBColor.GRAY
        portToolbar.add(lblPortHint)

        val btnAddPort = JButton("Add Port", AllIcons.General.Add)
        btnAddPort.addActionListener {
            portsTableModel.addRow(arrayOf("Local -> Host", "8080", "8080", "Custom Service"))
        }
        portToolbar.add(btnAddPort)

        val btnRemovePort = JButton("Remove Port", AllIcons.General.Remove)
        btnRemovePort.toolTipText = "Delete selected port forwarding (Delete)"
        btnRemovePort.addActionListener {
            val r = portsTable.selectedRow
            if (r in 0 until portsTableModel.rowCount) {
                val modelRow = portsTable.convertRowIndexToModel(r)
                portsTableModel.removeRow(modelRow)
            } else {
                Messages.showInfoMessage(project, "Please select a port row to delete.", "No Port Selected")
            }
        }
        portToolbar.add(btnRemovePort)

        portsTable.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                checkPopup(e)
            }
            override fun mouseReleased(e: MouseEvent) {
                checkPopup(e)
            }
            private fun checkPopup(e: MouseEvent) {
                if (e.isPopupTrigger || SwingUtilities.isRightMouseButton(e)) {
                    val r = portsTable.rowAtPoint(e.point)
                    if (r in 0 until portsTable.rowCount) {
                        portsTable.setRowSelectionInterval(r, r)
                    }
                }
            }
        })

        portsTable.addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(e: java.awt.event.KeyEvent) {
                if (e.keyCode == java.awt.event.KeyEvent.VK_DELETE || e.keyCode == java.awt.event.KeyEvent.VK_BACK_SPACE) {
                    val r = portsTable.selectedRow
                    if (r in 0 until portsTableModel.rowCount) {
                        val modelRow = portsTable.convertRowIndexToModel(r)
                        portsTableModel.removeRow(modelRow)
                    }
                }
            }
        })

        val portPopup = JPopupMenu()
        val itemAdd = JMenuItem("Add Port Forward", AllIcons.General.Add)
        itemAdd.addActionListener {
            portsTableModel.addRow(arrayOf("Local -> Host", "8080", "8080", "Custom Service"))
        }
        val itemRemove = JMenuItem("Remove Port (Delete)", AllIcons.General.Remove)
        itemRemove.addActionListener {
            val currentSel = portsTable.selectedRow
            if (currentSel in 0 until portsTableModel.rowCount) {
                val modelRow = portsTable.convertRowIndexToModel(currentSel)
                portsTableModel.removeRow(modelRow)
            }
        }

        portPopup.addPopupMenuListener(object : javax.swing.event.PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: javax.swing.event.PopupMenuEvent?) {
                portPopup.removeAll()
                portPopup.add(itemAdd)
                val r = portsTable.selectedRow
                if (r in 0 until portsTableModel.rowCount) {
                    portPopup.addSeparator()
                    portPopup.add(itemRemove)
                }
            }
            override fun popupMenuWillBecomeInvisible(e: javax.swing.event.PopupMenuEvent?) {}
            override fun popupMenuCanceled(e: javax.swing.event.PopupMenuEvent?) {}
        })
        portsTable.componentPopupMenu = portPopup

        portsBox.add(portToolbar, BorderLayout.NORTH)
        portsBox.add(JBScrollPane(portsTable), BorderLayout.CENTER)
        portsBox.preferredSize = Dimension(400, 150)

        val scrollForm = JBScrollPane(form)
        scrollForm.border = IdeBorderFactory.createTitledBorder("Connection Details", false)

        panel.add(scrollForm, BorderLayout.NORTH)
        panel.add(portsBox, BorderLayout.CENTER)
        return panel
    }

    private fun getCurrentProfile(): ServerProfile? {
        if (selectedIndex in workingProfiles.indices) {
            return workingProfiles[selectedIndex]
        }
        return null
    }

    private fun loadProfileToForm(p: ServerProfile) {
        nameField.text = p.name
        hostField.text = p.host
        portField.text = p.port.toString()
        authTypeBox.selectedIndex = if (p.authType == AuthType.PRIVATE_KEY) 1 else 0
        val isKey = p.authType == AuthType.PRIVATE_KEY
        credentialLabel.text = if (isKey) "Private Key:" else "Password:"
        credentialCardLayout.show(credentialCardPanel, if (isKey) "KEY" else "PASSWORD")

        userField.text = p.user
        passwordField.text = p.password
        keyPathField.text = p.privateKeyPath
        val localPath = p.localProjectPath.ifBlank { project.basePath ?: "" }
        localFolderField.text = localPath
        val fName = localPath.replace('\\', '/').trimEnd('/').substringAfterLast('/').ifBlank { project.name.trim().ifBlank { "app" } }
        val u = p.user.ifBlank { "root" }
        val defaultRemote = if (u == "root") "/root/remote-flow/$fName" else "/home/$u/remote-flow/$fName"

        if (p.remoteProjectPath.isBlank() ||
            (p.remoteProjectPath.matches(Regex(".*/remote-flow/.*")) && !p.remoteProjectPath.endsWith("/$fName"))
        ) {
            remotePathField.text = defaultRemote
        } else {
            remotePathField.text = p.remoteProjectPath
        }
        excludePatternsField.text = p.excludePatterns.ifBlank { uz.remote.flow.ssh.defaultExcludes() }
        rsyncPathField.text = p.rsyncPath
        javaHomeField.text = p.javaHome
        runCommandField.text = p.runCommand
        debugCommandField.text = p.debugCommand
        chkAutoSyncOnSave.isSelected = p.autoSyncOnSave
        autoSyncDelayField.text = p.autoSyncDelayMs.toString()
        chkOpenBrowser.isSelected = p.openBrowserOnReady
        browserUrlField.text = p.browserUrl.ifBlank { "http://localhost:8080" }
        autoReconnectCheck.isSelected = settings.autoReconnect

        // Ports table
        if (::portsTableModel.isInitialized) {
            portsTableModel.rowCount = 0
            for (pt in p.forwardedPorts) {
                val dir = if (pt.direction == ForwardDirection.REMOTE_TO_LOCAL) "Host -> Local" else "Local -> Host"
                portsTableModel.addRow(arrayOf(dir, pt.localPort.toString(), pt.remotePort.toString(), pt.serviceName))
            }
        }
    }

    private fun saveCurrentSelection() {
        val p = getCurrentProfile() ?: return
        p.name = nameField.text.trim().ifBlank { "Server" }
        p.host = hostField.text.trim()
        p.port = portField.text.toIntOrNull() ?: 22
        p.authType = if (authTypeBox.selectedIndex == 1) AuthType.PRIVATE_KEY else AuthType.PASSWORD
        p.user = userField.text.trim()
        p.password = String(passwordField.password)
        p.privateKeyPath = keyPathField.text.trim()
        p.localProjectPath = localFolderField.text.trim()
        p.remoteProjectPath = remotePathField.text.trim()
        p.excludePatterns = excludePatternsField.text.trim().ifBlank { uz.remote.flow.ssh.defaultExcludes() }
        p.rsyncPath = rsyncPathField.text.trim()
        p.javaHome = javaHomeField.text.trim()
        p.runCommand = runCommandField.text.trim()
        p.debugCommand = debugCommandField.text.trim()
        p.autoSyncOnSave = chkAutoSyncOnSave.isSelected
        p.autoSyncDelayMs = autoSyncDelayField.text.trim().toIntOrNull() ?: 1500
        p.openBrowserOnReady = chkOpenBrowser.isSelected
        p.browserUrl = browserUrlField.text.trim().ifBlank { "http://localhost:8080" }

        if (::portsTableModel.isInitialized) {
            val updated = mutableListOf<PortMapping>()
            for (row in 0 until portsTableModel.rowCount) {
                val dirStr = portsTableModel.getValueAt(row, 0) as? String ?: ""
                val dir = if (dirStr.contains("Host -> Local")) ForwardDirection.REMOTE_TO_LOCAL else ForwardDirection.LOCAL_TO_REMOTE
                val lPort = (portsTableModel.getValueAt(row, 1) as? String)?.toIntOrNull() ?: 8080
                val rPort = (portsTableModel.getValueAt(row, 2) as? String)?.toIntOrNull() ?: 8080
                val sName = (portsTableModel.getValueAt(row, 3) as? String) ?: "Service"
                updated.add(PortMapping(lPort, rPort, sName, direction = dir))
            }
            p.forwardedPorts = updated
        }
    }

    private fun resetWorkingCopy() {
        workingProfiles.clear()
        for (p in settings.profiles) {
            val copy = p.copyProfile()
            copy.name = uz.remote.flow.ssh.cleanServerName(copy.name)
            workingProfiles.add(copy)
        }
        selectedIndex = if (workingProfiles.isNotEmpty()) {
            settings.activeProfileIndex.coerceIn(workingProfiles.indices)
        } else 0

        profileListModel.clear()
        for (p in workingProfiles) {
            profileListModel.addElement(p)
        }
    }

    override fun isModified(): Boolean {
        if (workingProfiles.isNotEmpty()) {
            saveCurrentSelection()
        }
        if (settings.autoReconnect != autoReconnectCheck.isSelected) return true
        if (settings.routeStandardRunToRemote != chkRouteStandardRun.isSelected) return true
        if (settings.enableAgentBridge != chkEnableAgentBridge.isSelected) return true
        if (settings.profiles.size != workingProfiles.size) return true
        if (settings.profiles.isNotEmpty() && settings.activeProfileIndex != selectedIndex) return true
        for (i in workingProfiles.indices) {
            val a = workingProfiles[i]
            val b = settings.profiles[i]
            if (a.name != b.name || a.host != b.host || a.port != b.port ||
                a.user != b.user || a.password != b.password || a.privateKeyPath != b.privateKeyPath ||
                a.localProjectPath != b.localProjectPath ||
                a.remoteProjectPath != b.remoteProjectPath || a.runCommand != b.runCommand ||
                a.debugCommand != b.debugCommand || a.authType != b.authType ||
                a.excludePatterns != b.excludePatterns || a.rsyncPath != b.rsyncPath ||
                a.javaHome != b.javaHome || a.autoSyncOnSave != b.autoSyncOnSave ||
                a.autoSyncDelayMs != b.autoSyncDelayMs ||
                a.openBrowserOnReady != b.openBrowserOnReady || a.browserUrl != b.browserUrl ||
                a.forwardedPorts.size != b.forwardedPorts.size
            ) {
                return true
            }
        }
        return false
    }

    override fun apply() {
        if (workingProfiles.isNotEmpty()) {
            saveCurrentSelection()
        }
        settings.profiles = workingProfiles.map {
            it.name = uz.remote.flow.ssh.cleanServerName(it.name)
            it.copyProfile()
        }.toMutableList()
        settings.activeProfileIndex = if (settings.profiles.isNotEmpty()) {
            selectedIndex.coerceIn(settings.profiles.indices)
        } else 0
        settings.autoReconnect = autoReconnectCheck.isSelected
        settings.routeStandardRunToRemote = chkRouteStandardRun.isSelected
        settings.enableAgentBridge = chkEnableAgentBridge.isSelected

        val activeP = settings.activeProfileOrNull
        if (activeP != null && activeP.remoteProjectPath.isNotBlank() && connectionManager.isConnected) {
            connectionManager.checkDirectoryExists(activeP, activeP.remoteProjectPath) { exists, _ ->
                if (!exists) {
                    ApplicationManager.getApplication().invokeLater {
                        promptCreateRemoteDir(activeP, activeP.remoteProjectPath)
                    }
                }
            }
        }

        if (activeP != null) {
            project.messageBus.syncPublisher(RemoteConnectionListener.TOPIC).profileChanged(activeP)
        }
    }

    override fun reset() {
        resetWorkingCopy()
        chkRouteStandardRun.isSelected = settings.routeStandardRunToRemote
        autoReconnectCheck.isSelected = settings.autoReconnect
        chkEnableAgentBridge.isSelected = settings.enableAgentBridge
        if (workingProfiles.isNotEmpty()) {
            profileList.selectedIndex = selectedIndex
            loadProfileToForm(workingProfiles[selectedIndex])
        } else {
            clearForm()
        }
    }
}
