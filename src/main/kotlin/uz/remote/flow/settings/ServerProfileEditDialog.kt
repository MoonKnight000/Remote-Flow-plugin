package uz.remote.flow.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
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

    private val credentialLabel = JBLabel("Password:")
    private val credentialCardLayout = CardLayout()
    private val credentialCardPanel = JPanel(credentialCardLayout)

    private val localFolderField = TextFieldWithBrowseButton()
    private val remotePathField = JBTextField(profile.remoteProjectPath)
    private val btnBrowseRemote = JButton("📁 Browse...")
    private val btnResetTemplate = JButton("⟳")

    private val excludePatternsField = JBTextField()
    private val btnResetExcludes = JButton("⟳")
    private val rsyncPathField = TextFieldWithBrowseButton()
    private val btnAutoDetectRsync = JButton("🔍 Auto")

    private val runCommandField = JBTextField(profile.runCommand)
    private val debugCommandField = JBTextField(profile.debugCommand)

    private val chkAutoSyncOnSave = com.intellij.ui.components.JBCheckBox("⚡ Fayl saqlanganda avtomatik serverga yuklash (Auto-Sync on Save)", profile.autoSyncOnSave)
    private val btnTestConn = JButton("⚡ Test Connection")

    init {
        title = if (isNew) "Add New Server Profile" else "Edit Server Profile: " + profile.name
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
            "Serverda masofaviy papka topilmadi:\n$remotePath\n\nUshbu papkani serverda hozir yaratishni xohlaysizmi?",
            "Masofaviy Papka Topilmadi",
            "Ha, Yaratish",
            "Yo'q",
            Messages.getQuestionIcon()
        )
        if (choice == Messages.YES) {
            connectionManager.createDirectory(profile, remotePath) { success, err ->
                ApplicationManager.getApplication().invokeLater {
                    if (success) {
                        Messages.showInfoMessage(project, "Masofaviy papka serverda muvaffaqiyatli yaratildi:\n$remotePath", "Papka Yaratildi")
                        onFinished?.invoke(true)
                    } else {
                        Messages.showErrorDialog(project, "Papkani yaratishda xatolik yuz berdi: " + (err ?: "Ruxsat yo'q"), "Xatolik")
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
        chkAutoSyncOnSave.isSelected = profile.autoSyncOnSave
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(0, 10))
        root.preferredSize = Dimension(560, 480)
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
            credentialLabel.text = if (isKey) "Private Key:" else "Password:"
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

        btnResetExcludes.toolTipText = "Default istisnolar ro'yxatini tiklash"
        btnResetExcludes.addActionListener {
            excludePatternsField.text = uz.remote.flow.ssh.defaultExcludes()
        }

        btnAutoDetectRsync.toolTipText = "Tizimdan rsync.exe ni avtomatik aniqlash"
        btnAutoDetectRsync.addActionListener {
            val detected = uz.remote.flow.ssh.detectRsyncPath()
            if (detected.isNotBlank()) {
                rsyncPathField.text = detected
                Messages.showInfoMessage(project, "Rsync topildi:\n$detected", "Rsync Aniqlash")
            } else {
                Messages.showWarningDialog(project, "Tizimdan rsync topilmadi. Qo'lda tanlashingiz mumkin yoki SFTP zaxira ishlatiladi.", "Rsync Topilmadi")
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
            val targetRemote = remotePathField.text.trim()

            connectionManager.testConnection(temp, checkRemoteDir = targetRemote) { ok, dirExists, msg ->
                ApplicationManager.getApplication().invokeLater {
                    if (!ok) {
                        Messages.showErrorDialog(project, msg, "Ulanib bo'lmadi")
                        return@invokeLater
                    }

                    if (targetRemote.isNotBlank() && !dirExists) {
                        promptCreateRemoteDir(temp, targetRemote)
                    } else if (targetRemote.isNotBlank()) {
                        Messages.showInfoMessage(project, "$msg\n\nMasofaviy papka mavjud: $targetRemote", "Ulanish muvaffaqiyatli!")
                    } else {
                        Messages.showInfoMessage(project, msg, "Ulanish muvaffaqiyatli!")
                    }
                }
            }
        }

        val form = JPanel(GridBagLayout())
        val gbc = GridBagConstraints()
        gbc.insets = JBUI.insets(4, 4, 4, 4)
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

        // Row 3: Username
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
        rsyncPanel.add(btnAutoDetectRsync, BorderLayout.EAST)
        gbc.gridx = 1; gbc.gridwidth = 3; gbc.weightx = 1.0; form.add(rsyncPanel, gbc)
        gbc.gridwidth = 1

        // Row 9: Run Command
        gbc.gridx = 0; gbc.gridy = 9; gbc.weightx = 0.0; form.add(JBLabel("Run Command:"), gbc)
        gbc.gridx = 1; gbc.gridwidth = 3; gbc.weightx = 1.0; form.add(runCommandField, gbc)
        gbc.gridwidth = 1

        // Row 10: Debug Command
        gbc.gridx = 0; gbc.gridy = 10; gbc.weightx = 0.0; form.add(JBLabel("Debug Command:"), gbc)
        gbc.gridx = 1; gbc.gridwidth = 3; gbc.weightx = 1.0; form.add(debugCommandField, gbc)
        gbc.gridwidth = 1

        // Row 11: Auto-Sync on Save
        gbc.gridx = 0; gbc.gridy = 11; gbc.gridwidth = 4; gbc.weightx = 1.0
        form.add(chkAutoSyncOnSave, gbc)

        // Row 12: Test Button
        gbc.gridx = 0; gbc.gridy = 12; gbc.gridwidth = 4; gbc.weightx = 1.0
        val btnP = JPanel(FlowLayout(FlowLayout.LEFT, 0, 4))
        btnP.add(btnTestConn)
        form.add(btnP, gbc)

        root.add(form, BorderLayout.CENTER)
        return root
    }

    private fun applyToProfile(p: ServerProfile) {
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
        p.runCommand = runCommandField.text.trim()
        p.debugCommand = debugCommandField.text.trim()
        p.autoSyncOnSave = chkAutoSyncOnSave.isSelected
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
