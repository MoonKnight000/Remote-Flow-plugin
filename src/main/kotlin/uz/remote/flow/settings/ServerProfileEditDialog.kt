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

    private val runCommandField = JBTextField(profile.runCommand)
    private val debugCommandField = JBTextField(profile.debugCommand)

    private val btnTestConn = JButton("⚡ Test Connection")

    init {
        title = if (isNew) "Add New Server Profile" else "Edit Server Profile: " + profile.name
        init()
        loadValues()
    }

    private fun loadValues() {
        authTypeBox.selectedIndex = if (profile.authType == AuthType.PRIVATE_KEY) 1 else 0
        passwordField.text = profile.password
        keyPathField.text = profile.privateKeyPath
        localFolderField.text = profile.localProjectPath.ifBlank { project.basePath ?: "" }
        if (remotePathField.text.isBlank()) {
            remotePathField.text = "/root/remote-flow/" + project.name
        }
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(0, 10))
        root.preferredSize = Dimension(520, 420)
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

        keyPathField.addBrowseFolderListener(
            "Select SSH Private Key",
            "Choose .pem or id_rsa file",
            project,
            FileChooserDescriptorFactory.createSingleFileDescriptor()
        )

        btnResetTemplate.toolTipText = "Default template: /<user>/remote-flow/<project>"
        btnResetTemplate.addActionListener {
            val u = userField.text.trim().ifBlank { "root" }
            val p = project.name.trim().ifBlank { "app" }
            remotePathField.text = if (u == "root") "/root/remote-flow/$p" else "/home/$u/remote-flow/$p"
        }

        btnBrowseRemote.addActionListener {
            if (!connectionManager.isConnected) {
                Messages.showInfoMessage(project, "Masofaviy papkalarni ko'rish uchun avval serverga ulanish kerak.", "Eslatma")
                return@addActionListener
            }
            val dlg = RemoteDirectoryChooserDialog(project, remotePathField.text.trim(), profile)
            if (dlg.showAndGet()) {
                remotePathField.text = dlg.selectedPath
            }
        }

        btnTestConn.addActionListener {
            val temp = profile.copyProfile()
            applyToProfile(temp)
            connectionManager.testConnection(temp) { ok, msg ->
                ApplicationManager.getApplication().invokeLater {
                    if (ok) Messages.showInfoMessage(project, msg, "Ulanish muvaffaqiyatli!")
                    else Messages.showErrorDialog(project, msg, "Ulanib bo'lmadi")
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

        // Row 7: Run Command
        gbc.gridx = 0; gbc.gridy = 7; gbc.weightx = 0.0; form.add(JBLabel("Run Command:"), gbc)
        gbc.gridx = 1; gbc.gridwidth = 3; gbc.weightx = 1.0; form.add(runCommandField, gbc)
        gbc.gridwidth = 1

        // Row 8: Debug Command
        gbc.gridx = 0; gbc.gridy = 8; gbc.weightx = 0.0; form.add(JBLabel("Debug Command:"), gbc)
        gbc.gridx = 1; gbc.gridwidth = 3; gbc.weightx = 1.0; form.add(debugCommandField, gbc)
        gbc.gridwidth = 1

        // Row 9: Test Button
        gbc.gridx = 0; gbc.gridy = 9; gbc.gridwidth = 4; gbc.weightx = 1.0
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
        p.runCommand = runCommandField.text.trim()
        p.debugCommand = debugCommandField.text.trim()
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
