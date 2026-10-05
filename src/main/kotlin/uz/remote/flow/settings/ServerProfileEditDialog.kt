package uz.remote.flow.settings

import com.intellij.icons.AllIcons
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import uz.remote.flow.ssh.AuthType
import uz.remote.flow.ssh.RemoteConnectionListener
import uz.remote.flow.ssh.RemoteConnectionManager
import uz.remote.flow.ssh.ServerProfile
import uz.remote.flow.ui.RemoteDirectoryChooserDialog
import java.awt.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.*

class ServerProfileEditDialog(
    private val project: Project,
    private val profile: ServerProfile,
    private val isNew: Boolean = false
) : DialogWrapper(project, true) {

    private val connectionManager = RemoteConnectionManager.getInstance(project)

    private val nameField = JBTextField(profile.name)
    private val hostField = JBTextField(profile.host)
    private val portField = JBTextField(profile.port.toString())
    private val authTypeBox = JComboBox(arrayOf("Password", "SSH Private Key (.pem / id_rsa)"))
    private val userField = JBTextField(profile.user)
    private val passwordField = JBPasswordField()
    private val keyPathField = TextFieldWithBrowseButton()

    private val credentialLabel = JBLabel("Password: *")
    private val credentialCardLayout = CardLayout()
    private val credentialCardPanel = JPanel(credentialCardLayout)

    private val localFolderField = TextFieldWithBrowseButton()
    private val remotePathField = JBTextField(profile.remoteProjectPath)
    private val btnBrowseRemote = JButton("Browse...", AllIcons.Nodes.Folder)
    private val btnResetTemplate = JButton("⟳")

    private val excludePatternsField = JBTextField()
    private val btnResetExcludes = JButton("⟳")
    private val rsyncPathField = TextFieldWithBrowseButton(JBTextField())
    private val btnAutoDetectRsync = JButton("Auto", AllIcons.Actions.Search)

    private val javaHomeField = JBTextField(profile.javaHome)
    private val btnAutoDetectJava = JButton("Auto", AllIcons.Actions.Search)
    private val runCommandField = JBTextField(profile.runCommand)
    private val debugCommandField = JBTextField(profile.debugCommand)
    private val testCommandField = JBTextField(profile.testCommand.ifBlank { "./gradlew test" })
    private val buildCommandField = JBTextField(profile.buildCommand.ifBlank { "./gradlew build -x test" })

    private val envBox = JComboBox(arrayOf("DEV (Development)", "STAGING (Pre-production)", "PRODUCTION (Live Protected)"))
    private val chkConfirmProduction = com.intellij.ui.components.JBCheckBox("⚠️ Confirm all destructive actions on Production (Run, Stop, Sync)", profile.confirmOnProduction)

    private val chkOpenBrowser = com.intellij.ui.components.JBCheckBox("🌐 Auto-open browser when application is ready", profile.openBrowserOnReady)
    private val browserUrlField = JBTextField(profile.browserUrl)

    private val preRunCommandField = JBTextField(profile.preRunCommand)
    private val postRunCommandField = JBTextField(profile.postRunCommand)

    private val chkAutoSyncOnSave = com.intellij.ui.components.JBCheckBox("⚡ Real-Time Auto-Sync (Sync when typing pauses & external AI changes)", profile.autoSyncOnSave).apply {
        toolTipText = "Automatically detects and uploads code changes after you finish typing or when external AI coding tools modify files."
    }
    private val autoSyncDelayField = JBTextField(profile.autoSyncDelayMs.toString(), 5)
    private val btnTestConn = JButton("Test Connection", AllIcons.Actions.Execute)
    private val lblTestStatus = JBLabel("").apply {
        font = com.intellij.util.ui.JBUI.Fonts.smallFont()
    }

    init {
        title = if (isNew) "Add New Server Profile" else "Edit Server Profile: " + profile.name
        nameField.emptyText.text = "e.g. My Remote Server (Optional)"
        hostField.emptyText.text = "e.g. 192.168.1.100 or myserver.com (Required)"
        userField.emptyText.text = "e.g. root or ubuntu (Required, default: root)"
        remotePathField.emptyText.text = "e.g. /root/remote-flow/myapp (Optional)"
        init()
        loadValues()
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

    private fun loadValues() {
        authTypeBox.selectedIndex = if (profile.authType == AuthType.PRIVATE_KEY) 1 else 0
        passwordField.text = profile.password
        keyPathField.text = profile.privateKeyPath
        val localPath = profile.localProjectPath.ifBlank { project.basePath ?: "" }
        localFolderField.text = localPath
        val fName = localPath.replace('\\', '/').trimEnd('/').substringAfterLast('/').ifBlank { project.name.trim().ifBlank { "app" } }
        val u = profile.user.ifBlank { "root" }
        val defaultRemote = if (u == "root") "/root/remote-flow/$fName" else "/home/$u/remote-flow/$fName"

        if (profile.remoteProjectPath.isBlank() ||
            (profile.remoteProjectPath.matches(Regex(".*/remote-flow/.*")) && !profile.remoteProjectPath.endsWith("/$fName"))
        ) {
            remotePathField.text = defaultRemote
        } else {
            remotePathField.text = profile.remoteProjectPath
        }
        excludePatternsField.text = profile.excludePatterns.ifBlank { uz.remote.flow.ssh.defaultExcludes() }
        rsyncPathField.text = profile.rsyncPath
        javaHomeField.text = profile.javaHome
        chkAutoSyncOnSave.isSelected = profile.autoSyncOnSave
        autoSyncDelayField.text = profile.autoSyncDelayMs.toString()
        testCommandField.text = profile.testCommand.ifBlank { "./gradlew test" }
        buildCommandField.text = profile.buildCommand.ifBlank { "./gradlew build -x test" }

        envBox.selectedIndex = when (profile.environment) {
            uz.remote.flow.ssh.ServerEnvironment.DEV -> 0
            uz.remote.flow.ssh.ServerEnvironment.STAGING -> 1
            uz.remote.flow.ssh.ServerEnvironment.PRODUCTION -> 2
        }
        chkConfirmProduction.isSelected = profile.confirmOnProduction
        chkOpenBrowser.isSelected = profile.openBrowserOnReady
        browserUrlField.text = profile.browserUrl.ifBlank { "http://localhost:8080" }
        preRunCommandField.text = profile.preRunCommand
        postRunCommandField.text = profile.postRunCommand
        lblTestStatus.text = ""
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(0, 10))
        root.preferredSize = Dimension(580, 540)
        root.border = JBUI.Borders.empty(10)

        // Setup CardLayout for credentials
        val pwdCard = JPanel(BorderLayout())
        pwdCard.add(passwordField, BorderLayout.CENTER)
        val keyCard = JPanel(BorderLayout())
        keyCard.add(keyPathField, BorderLayout.CENTER)
        credentialCardPanel.add(pwdCard, "PASSWORD")
        credentialCardPanel.add(keyCard, "KEY")

        authTypeBox.addActionListener {
            val isKey = authTypeBox.selectedIndex == 1
            credentialLabel.text = if (isKey) "Private Key: *" else "Password: *"
            credentialCardLayout.show(credentialCardPanel, if (isKey) "KEY" else "PASSWORD")
        }

        localFolderField.addBrowseFolderListener(
            "Select Local Project Directory",
            "Choose directory to sync with server",
            project,
            FileChooserDescriptorFactory.createSingleFolderDescriptor()
        )

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

        keyPathField.addBrowseFolderListener(
            "Select SSH Private Key",
            "Choose .pem or id_rsa file",
            project,
            FileChooserDescriptorFactory.createSingleFileDescriptor()
        )

        rsyncPathField.addBrowseFolderListener(
            "Select Rsync Executable",
            "Choose rsync or rsync.exe",
            project,
            FileChooserDescriptorFactory.createSingleFileDescriptor()
        )

        btnResetExcludes.toolTipText = "Reset to default exclude patterns"
        btnResetExcludes.addActionListener {
            excludePatternsField.text = uz.remote.flow.ssh.defaultExcludes()
        }

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
                Messages.showWarningDialog(project, "Rsync executable not found in system PATH. You can browse manually or SFTP fallback will be used.", "Rsync Not Found")
            }
        }

        btnResetTemplate.toolTipText = "Default template: /<user>/remote-flow/<local-folder>"
        btnResetTemplate.addActionListener {
            val u = userField.text.trim().ifBlank { "root" }
            val fName = resolveLocalFolderName()
            remotePathField.text = if (u == "root") "/root/remote-flow/$fName" else "/home/$u/remote-flow/$fName"
        }

        btnBrowseRemote.addActionListener {
            val temp = profile.copyProfile()
            applyToProfile(temp)
            val targetRemote = remotePathField.text.trim().ifBlank { "/root" }

            connectionManager.checkDirectoryExists(temp, targetRemote) { exists, _ ->
                ApplicationManager.getApplication().invokeLater {
                    if (exists) {
                        val dlg = RemoteDirectoryChooserDialog(project, targetRemote, temp)
                        if (dlg.showAndGet()) {
                            remotePathField.text = dlg.selectedPath
                        }
                    } else {
                        promptCreateRemoteDir(temp, targetRemote) { created ->
                            val openPath = if (created) targetRemote else "/root"
                            val dlg = RemoteDirectoryChooserDialog(project, openPath, temp)
                            if (dlg.showAndGet()) {
                                remotePathField.text = dlg.selectedPath
                            }
                        }
                    }
                }
            }
        }

        btnTestConn.addActionListener {
            val temp = profile.copyProfile()
            applyToProfile(temp)

            val (cleanH, cleanP) = uz.remote.flow.ssh.sanitizeServerHostAndPort(temp.host, temp.port)
            temp.host = cleanH
            temp.port = cleanP
            hostField.text = cleanH
            portField.text = cleanP.toString()

            val rawHost = hostField.text.trim()
            if (temp.user.isBlank() && rawHost.contains("@")) {
                val extractedUser = rawHost.substringBefore('@').trim()
                if (extractedUser.isNotBlank()) {
                    temp.user = extractedUser
                    userField.text = extractedUser
                }
            }
            if (temp.user.isBlank()) {
                temp.user = "root"
                userField.text = "root"
            }

            val targetRemote = remotePathField.text.trim()

            if (temp.host.isBlank()) {
                lblTestStatus.text = "✗ Server Host IP / Domain is required"
                lblTestStatus.foreground = com.intellij.ui.JBColor.RED
                Messages.showWarningDialog(contentPane, "Please enter the Server Host IP or domain first.\nRequired fields: Host IP, Username, and Password or Key.", "Host Missing")
                hostField.requestFocus()
                return@addActionListener
            }
            if (temp.authType == AuthType.PRIVATE_KEY && temp.privateKeyPath.isBlank()) {
                lblTestStatus.text = "✗ SSH Private Key file is required"
                lblTestStatus.foreground = com.intellij.ui.JBColor.RED
                Messages.showWarningDialog(contentPane, "Please select an SSH Private Key file (.pem / id_rsa).", "Key Missing")
                return@addActionListener
            }

            val authDesc = if (temp.authType == AuthType.PRIVATE_KEY) "SSH Key (${File(temp.privateKeyPath).name})" else "Password"
            val attemptMsg = "Connecting to ${temp.host}:${temp.port} as '${temp.user}' via $authDesc..."

            btnTestConn.isEnabled = false
            btnTestConn.text = "Connecting..."
            btnTestConn.icon = AllIcons.Actions.Refresh
            lblTestStatus.text = attemptMsg
            lblTestStatus.foreground = com.intellij.ui.JBColor.GRAY

            try {
                Notifications.Bus.notify(
                    Notification(
                        "Remote Flow",
                        "Remote Flow: Testing Connection",
                        "Connecting to ${temp.host}:${temp.port} as '${temp.user}' via $authDesc (timeout: 7s)...",
                        NotificationType.INFORMATION
                    ),
                    project
                )
            } catch (_: Throwable) {}

            ProgressManager.getInstance().run(
                object : Task.Modal(project, "Testing SSH Connection: ${temp.host}:${temp.port}", true) {
                    var isSuccess = false
                    var remoteDirOk = false
                    var resultMsg = ""

                    override fun run(indicator: ProgressIndicator) {
                        indicator.isIndeterminate = true
                        indicator.text = "Connecting to ${temp.host}:${temp.port} as '${temp.user}'..."
                        indicator.text2 = "Initiating TCP handshake via port ${temp.port} (timeout 7s)..."

                        val latch = CountDownLatch(1)

                        connectionManager.testConnection(
                            profile = temp,
                            checkRemoteDir = targetRemote,
                            onProgress = { progressText ->
                                indicator.text2 = progressText
                                ApplicationManager.getApplication().invokeLater {
                                    lblTestStatus.text = progressText
                                }
                            }
                        ) { ok, dirExists, msg ->
                            isSuccess = ok
                            remoteDirOk = dirExists
                            resultMsg = msg
                            latch.countDown()
                        }

                        while (!latch.await(150, TimeUnit.MILLISECONDS)) {
                            if (indicator.isCanceled) {
                                resultMsg = "Connection test cancelled by user."
                                isSuccess = false
                                break
                            }
                        }
                    }

                    override fun onSuccess() {
                        finish()
                    }

                    override fun onCancel() {
                        finish()
                    }

                    private fun finish() {
                        btnTestConn.isEnabled = true
                        btnTestConn.text = "Test Connection"
                        btnTestConn.icon = AllIcons.Actions.Execute

                        if (!isSuccess) {
                            lblTestStatus.text = "✗ $resultMsg"
                            lblTestStatus.foreground = com.intellij.ui.JBColor.RED

                            val detailedMsg = buildString {
                                appendLine("❌ Failed to connect to server!")
                                appendLine()
                                appendLine("Connection parameters attempted:")
                                appendLine("• Host IP: ${temp.host}")
                                appendLine("• Port: ${temp.port}")
                                appendLine("• Username: ${temp.user}")
                                appendLine("• Auth Type: $authDesc")
                                appendLine()
                                appendLine("Diagnosis / Reason:")
                                appendLine(resultMsg)
                            }
                            Messages.showErrorDialog(contentPane, detailedMsg, "Connection Failed")
                            return
                        }

                        lblTestStatus.text = "✓ $resultMsg"
                        lblTestStatus.foreground = com.intellij.ui.JBColor(java.awt.Color(0, 140, 0), java.awt.Color(98, 181, 67))

                        val detailedMsg = buildString {
                            appendLine("✅ Server connection successful!")
                            appendLine()
                            appendLine("Connection details:")
                            appendLine("• Host: ${temp.host}:${temp.port}")
                            appendLine("• Username: ${temp.user}")
                            appendLine("• Auth Type: $authDesc")
                            appendLine("• Result: $resultMsg")
                            if (targetRemote.isNotBlank()) {
                                appendLine("• Remote Directory: $targetRemote (${if (remoteDirOk) "Exists" else "Not found on server"})")
                            }
                        }

                        if (targetRemote.isNotBlank() && !remoteDirOk) {
                            promptCreateRemoteDir(temp, targetRemote)
                        } else {
                            Messages.showInfoMessage(contentPane, detailedMsg, "Connection Successful")
                        }
                    }
                }
            )
        }

        val form = JPanel(GridBagLayout())
        val gbc = GridBagConstraints()
        gbc.insets = JBUI.insets(4, 4, 4, 4)
        gbc.anchor = GridBagConstraints.WEST
        gbc.fill = GridBagConstraints.HORIZONTAL

        // Row 0: Name & Environment
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0; form.add(JBLabel("Profile Name:"), gbc)
        gbc.gridx = 1; gbc.weightx = 0.6; form.add(nameField, gbc)
        gbc.gridx = 2; gbc.weightx = 0.0; form.add(JBLabel("Environment:"), gbc)
        gbc.gridx = 3; gbc.weightx = 0.4; form.add(envBox, gbc)

        // Row 1: Host & Port
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0; form.add(JBLabel("Host IP: *"), gbc)
        gbc.gridx = 1; gbc.weightx = 0.7; form.add(hostField, gbc)
        gbc.gridx = 2; gbc.weightx = 0.0; form.add(JBLabel("Port:"), gbc)
        gbc.gridx = 3; gbc.weightx = 0.3; form.add(portField, gbc)

        // Row 2: Auth Type
        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0.0; form.add(JBLabel("Auth Type:"), gbc)
        gbc.gridx = 1; gbc.gridwidth = 3; gbc.weightx = 1.0; form.add(authTypeBox, gbc)
        gbc.gridwidth = 1

        // Row 3: Username
        gbc.gridx = 0; gbc.gridy = 3; gbc.weightx = 0.0; form.add(JBLabel("Username: *"), gbc)
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
        val rBtns = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0))
        rBtns.add(btnResetTemplate)
        rBtns.add(btnBrowseRemote)
        remotePanel.add(rBtns, BorderLayout.EAST)
        gbc.gridx = 1; gbc.gridwidth = 3; gbc.weightx = 1.0; form.add(remotePanel, gbc)
        gbc.gridwidth = 1

        // Row 7: Exclude Paths
        gbc.gridx = 0; gbc.gridy = 7; gbc.weightx = 0.0; form.add(JBLabel("Exclude Paths:"), gbc)
        val exPanel = JPanel(BorderLayout(4, 0))
        exPanel.add(excludePatternsField, BorderLayout.CENTER)
        exPanel.add(btnResetExcludes, BorderLayout.EAST)
        gbc.gridx = 1; gbc.gridwidth = 3; gbc.weightx = 1.0; form.add(exPanel, gbc)
        gbc.gridwidth = 1

        // Row 8: Rsync Executable
        gbc.gridx = 0; gbc.gridy = 8; gbc.weightx = 0.0; form.add(JBLabel("Rsync Path:"), gbc)
        val rsyncPanel = JPanel(BorderLayout(4, 0))
        rsyncPanel.add(rsyncPathField, BorderLayout.CENTER)
        val rsyncBtns = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0))
        rsyncBtns.add(btnAutoDetectRsync)
        val btnOpenIdeRsync = JButton("IntelliJ Rsync", AllIcons.General.Settings)
        btnOpenIdeRsync.toolTipText = "IntelliJ IDEA official Rsync settings (Tools -> Rsync)"
        btnOpenIdeRsync.addActionListener {
            try {
                com.intellij.openapi.options.ShowSettingsUtil.getInstance().showSettingsDialog(project, "rsyncConfigurable")
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
        javaHomeField.emptyText.text = "Remote Java / JAVA_HOME (e.g., /usr/lib/jvm/java-17-openjdk-amd64)"
        javaPanel.add(javaHomeField, BorderLayout.CENTER)
        val jBtns = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0))
        btnAutoDetectJava.toolTipText = "Detect available Java installations on the server"
        btnAutoDetectJava.addActionListener {
            val temp = profile.copyProfile()
            applyToProfile(temp)
            btnAutoDetectJava.isEnabled = false
            btnAutoDetectJava.text = "..."
            connectionManager.detectRemoteJava(temp) { list ->
                ApplicationManager.getApplication().invokeLater {
                    btnAutoDetectJava.isEnabled = true
                    btnAutoDetectJava.text = "🔍 Auto"
                    if (list.isEmpty()) {
                        Messages.showInfoMessage(project, "Java was not automatically detected on the remote server. Please specify manually.", "Remote Java")
                    } else if (list.size == 1) {
                        javaHomeField.text = list[0]
                        Messages.showInfoMessage(project, "Remote Java found:\n${list[0]}", "Java Found")
                    } else {
                        val chosen = Messages.showEditableChooseDialog(
                            "The following Java installations were found on the server. Select one:",
                            "Select Remote Java",
                            Messages.getQuestionIcon(),
                            list.toTypedArray(),
                            list[0],
                            null
                        )
                        if (!chosen.isNullOrBlank()) {
                            javaHomeField.text = chosen
                        }
                    }
                }
            }
        }
        jBtns.add(btnAutoDetectJava)
        javaPanel.add(jBtns, BorderLayout.EAST)
        gbc.gridx = 1; gbc.gridwidth = 3; gbc.weightx = 1.0; form.add(javaPanel, gbc)
        gbc.gridwidth = 1

        // Row 10: Run Command
        gbc.gridx = 0; gbc.gridy = 10; gbc.weightx = 0.0; form.add(JBLabel("Run Command:"), gbc)
        gbc.gridx = 1; gbc.gridwidth = 3; gbc.weightx = 1.0; form.add(runCommandField, gbc)
        gbc.gridwidth = 1

        // Row 11: Debug Command
        gbc.gridx = 0; gbc.gridy = 11; gbc.weightx = 0.0; form.add(JBLabel("Debug Command:"), gbc)
        gbc.gridx = 1; gbc.gridwidth = 3; gbc.weightx = 1.0; form.add(debugCommandField, gbc)
        gbc.gridwidth = 1

        // Row 12: Test Command (for AI & CLI)
        gbc.gridx = 0; gbc.gridy = 12; gbc.weightx = 0.0; form.add(JBLabel("Test Command:"), gbc)
        testCommandField.emptyText.text = "./gradlew test"
        gbc.gridx = 1; gbc.gridwidth = 3; gbc.weightx = 1.0; form.add(testCommandField, gbc)
        gbc.gridwidth = 1

        // Row 13: Build Command (for AI & CLI)
        gbc.gridx = 0; gbc.gridy = 13; gbc.weightx = 0.0; form.add(JBLabel("Build Command:"), gbc)
        buildCommandField.emptyText.text = "./gradlew build -x test"
        gbc.gridx = 1; gbc.gridwidth = 3; gbc.weightx = 1.0; form.add(buildCommandField, gbc)
        gbc.gridwidth = 1

        // Row 14: Pre-Run Hook
        gbc.gridx = 0; gbc.gridy = 14; gbc.weightx = 0.0; form.add(JBLabel("Pre-Run Hook:"), gbc)
        preRunCommandField.emptyText.text = "Optional bash command before build (e.g., npm run build, ./mvnw compile)"
        gbc.gridx = 1; gbc.gridwidth = 3; gbc.weightx = 1.0; form.add(preRunCommandField, gbc)
        gbc.gridwidth = 1

        // Row 15: Post-Run Hook
        gbc.gridx = 0; gbc.gridy = 15; gbc.weightx = 0.0; form.add(JBLabel("Post-Run Hook:"), gbc)
        postRunCommandField.emptyText.text = "Optional bash command when app becomes ready"
        gbc.gridx = 1; gbc.gridwidth = 3; gbc.weightx = 1.0; form.add(postRunCommandField, gbc)
        gbc.gridwidth = 1

        // Row 16: Browser Auto-Open
        gbc.gridx = 0; gbc.gridy = 16; gbc.weightx = 0.0; form.add(chkOpenBrowser, gbc)
        val browserPanel = JPanel(BorderLayout(4, 0))
        browserUrlField.emptyText.text = "http://localhost:8080"
        browserPanel.add(JBLabel("URL: "), BorderLayout.WEST)
        browserPanel.add(browserUrlField, BorderLayout.CENTER)
        gbc.gridx = 1; gbc.gridwidth = 3; gbc.weightx = 1.0; form.add(browserPanel, gbc)
        gbc.gridwidth = 1

        // Row 17: Production Safety & Auto-Sync
        gbc.gridx = 0; gbc.gridy = 17; gbc.gridwidth = 4; gbc.weightx = 1.0
        val flagsPanel = JPanel(GridLayout(3, 1, 0, 2))
        flagsPanel.add(chkConfirmProduction)
        flagsPanel.add(chkAutoSyncOnSave)
        val delayPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        delayPanel.add(JBLabel("   ↳ Typing pause delay: "))
        delayPanel.add(autoSyncDelayField)
        delayPanel.add(JBLabel("ms (waits after you stop typing before syncing)"))
        flagsPanel.add(delayPanel)
        form.add(flagsPanel, gbc)

        // Row 18: Test Button & Note
        gbc.gridx = 0; gbc.gridy = 18; gbc.gridwidth = 4; gbc.weightx = 1.0
        val btnP = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4))
        btnP.add(btnTestConn)
        val lblMandatoryNote = JBLabel("(* = Host IP, Username, and Password/Key are required)").apply {
            font = font.deriveFont(Font.ITALIC, 11f)
            foreground = com.intellij.ui.JBColor.GRAY
        }
        btnP.add(lblMandatoryNote)
        form.add(btnP, gbc)

        // Row 19: Live Test Status
        gbc.gridx = 0; gbc.gridy = 19; gbc.gridwidth = 4; gbc.weightx = 1.0
        val statusP = JPanel(BorderLayout(4, 0))
        statusP.add(lblTestStatus, BorderLayout.CENTER)
        form.add(statusP, gbc)

        val scroll = com.intellij.ui.components.JBScrollPane(form).apply {
            border = JBUI.Borders.empty()
        }
        root.add(scroll, BorderLayout.CENTER)
        return root
    }

    override fun doValidate(): com.intellij.openapi.ui.ValidationInfo? {
        val (cleanH, _) = uz.remote.flow.ssh.sanitizeServerHostAndPort(hostField.text.trim(), 22)
        if (cleanH.isBlank()) {
            return com.intellij.openapi.ui.ValidationInfo("Server Host IP or domain is required.", hostField)
        }
        val isKey = authTypeBox.selectedIndex == 1
        if (isKey && keyPathField.text.trim().isBlank()) {
            return com.intellij.openapi.ui.ValidationInfo("SSH Private Key file is required.", keyPathField)
        }
        return super.doValidate()
    }

    private fun applyToProfile(p: ServerProfile) {
        val (cleanH, cleanP) = uz.remote.flow.ssh.sanitizeServerHostAndPort(hostField.text.trim(), portField.text.toIntOrNull() ?: 22)
        p.name = nameField.text.trim().ifBlank { "Server" }
        p.host = cleanH
        p.port = cleanP
        p.authType = if (authTypeBox.selectedIndex == 1) AuthType.PRIVATE_KEY else AuthType.PASSWORD
        p.user = userField.text.trim().ifBlank { "root" }
        p.password = String(passwordField.password)
        p.privateKeyPath = keyPathField.text.trim()
        p.localProjectPath = localFolderField.text.trim()
        p.remoteProjectPath = remotePathField.text.trim()
        p.excludePatterns = excludePatternsField.text.trim().ifBlank { uz.remote.flow.ssh.defaultExcludes() }
        p.rsyncPath = rsyncPathField.text.trim()
        p.javaHome = javaHomeField.text.trim()
        p.runCommand = runCommandField.text.trim()
        p.debugCommand = debugCommandField.text.trim()
        p.testCommand = testCommandField.text.trim().ifBlank { "./gradlew test" }
        p.buildCommand = buildCommandField.text.trim().ifBlank { "./gradlew build -x test" }
        p.autoSyncOnSave = chkAutoSyncOnSave.isSelected
        p.autoSyncDelayMs = autoSyncDelayField.text.trim().toIntOrNull() ?: 1500

        p.environment = when (envBox.selectedIndex) {
            1 -> uz.remote.flow.ssh.ServerEnvironment.STAGING
            2 -> uz.remote.flow.ssh.ServerEnvironment.PRODUCTION
            else -> uz.remote.flow.ssh.ServerEnvironment.DEV
        }
        p.confirmOnProduction = chkConfirmProduction.isSelected
        p.openBrowserOnReady = chkOpenBrowser.isSelected
        p.browserUrl = browserUrlField.text.trim().ifBlank { "http://localhost:8080" }
        p.preRunCommand = preRunCommandField.text.trim()
        p.postRunCommand = postRunCommandField.text.trim()
    }

    override fun doOKAction() {
        applyToProfile(profile)
        val settings = RemoteFlowSettings.getInstance(project)
        if (isNew) {
            settings.profiles.add(profile)
            settings.activeProfileIndex = settings.profiles.size - 1
        }
        project.messageBus.syncPublisher(RemoteConnectionListener.TOPIC).profileChanged(profile)
        super.doOKAction()
    }
}
