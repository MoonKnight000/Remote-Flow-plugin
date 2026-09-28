package uz.remote.flow.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
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

    private val runCommandField = JBTextField()
    private val debugCommandField = JBTextField()

    private val autoReconnectCheck = JBCheckBox("Auto-Reconnect & Keep-Alive", true)
    private val btnTestConnection = JButton("⚡ Test Connection")

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

        btnResetRemoteTemplate.toolTipText = "Shablon bo'yicha qayta tiklash (/<user>/remote-flow/<project>)"
        btnResetRemoteTemplate.addActionListener {
            val u = userField.text.trim().ifBlank { "root" }
            val p = project.name.trim().ifBlank { "app" }
            remotePathField.text = if (u == "root") "/root/remote-flow/$p" else "/home/$u/remote-flow/$p"
        }

        btnBrowseRemote.addActionListener {
            saveCurrentSelection()
            val current = getCurrentProfile() ?: return@addActionListener
            if (!connectionManager.isConnected) {
                Messages.showInfoMessage(project, "Masofaviy papkalarni ko'rish uchun avval serverga ulanish lozim.", "Eslatma")
                return@addActionListener
            }
            val dialog = RemoteDirectoryChooserDialog(project, remotePathField.text.trim(), current)
            if (dialog.showAndGet()) {
                remotePathField.text = dialog.selectedPath
                current.remoteProjectPath = dialog.selectedPath
            }
        }

        btnTestConnection.addActionListener {
            saveCurrentSelection()
            val current = getCurrentProfile() ?: return@addActionListener
            connectionManager.testConnection(current) { ok, msg ->
                ApplicationManager.getApplication().invokeLater {
                    if (ok) Messages.showInfoMessage(project, msg, "Ulanish Muvaffaqiyatli")
                    else Messages.showErrorDialog(project, msg, "Ulanishda Xatolik")
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
                val newP = ServerProfile(
                    name = newName,
                    host = "192.168.1.100",
                    localProjectPath = project.basePath ?: "",
                    remoteProjectPath = "/root/remote-flow/" + project.name
                )
                workingProfiles.add(newP)
                profileListModel.addElement(newP)
                profileList.selectedIndex = workingProfiles.size - 1
            }
            .setRemoveAction {
                if (workingProfiles.size <= 1) {
                    Messages.showWarningDialog(project, "Oxirgi server profilini o'chirib bo'lmaydi!", "Ogohlantirish")
                    return@setRemoveAction
                }
                val curIdx = profileList.selectedIndex
                if (curIdx >= 0) {
                    workingProfiles.removeAt(curIdx)
                    profileListModel.remove(curIdx)
                    selectedIndex = curIdx.coerceAtMost(workingProfiles.size - 1)
                    profileList.selectedIndex = selectedIndex
                    loadProfileToForm(workingProfiles[selectedIndex])
                }
            }
            .addExtraAction(object : com.intellij.openapi.actionSystem.AnAction("Duplicate Profile", "Nusxa olish", com.intellij.icons.AllIcons.Actions.Copy) {
                override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                    saveCurrentSelection()
                    val cur = getCurrentProfile() ?: return
                    val copy = cur.copyProfile()
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
        }

        return root
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

        // Row 7: Commands
        gbc.gridx = 0; gbc.gridy = 7; gbc.weightx = 0.0; form.add(JBLabel("Run Command:"), gbc)
        gbc.gridx = 1; gbc.gridwidth = 3; gbc.weightx = 1.0; form.add(runCommandField, gbc)
        gbc.gridwidth = 1

        gbc.gridx = 0; gbc.gridy = 8; gbc.weightx = 0.0; form.add(JBLabel("Debug Command:"), gbc)
        gbc.gridx = 1; gbc.gridwidth = 3; gbc.weightx = 1.0; form.add(debugCommandField, gbc)
        gbc.gridwidth = 1

        // Row 9: Buttons
        val btnRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4))
        btnRow.add(btnTestConnection)
        btnRow.add(autoReconnectCheck)
        gbc.gridx = 0; gbc.gridy = 9; gbc.gridwidth = 4; gbc.weightx = 1.0
        form.add(btnRow, gbc)

        // Ports Table
        val portsBox = JPanel(BorderLayout(0, 6))
        portsBox.border = IdeBorderFactory.createTitledBorder("Forwarded Ports (SSH Tunnels)", false)

        val portCols = arrayOf("Local Port", "Remote Port", "Service Name")
        portsTableModel = object : DefaultTableModel(portCols, 0) {
            override fun isCellEditable(row: Int, column: Int): Boolean = true
        }
        portsTable = JBTable(portsTableModel)
        portsTable.rowHeight = 24

        val portToolbar = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))
        val btnAddPort = JButton("➕ Add Port")
        btnAddPort.addActionListener {
            portsTableModel.addRow(arrayOf("8080", "8080", "Custom Service"))
        }
        val btnDelPort = JButton("🗑 Remove Port")
        btnDelPort.addActionListener {
            val r = portsTable.selectedRow
            if (r >= 0) portsTableModel.removeRow(r)
        }
        portToolbar.add(btnAddPort)
        portToolbar.add(btnDelPort)

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
        localFolderField.text = p.localProjectPath.ifBlank { project.basePath ?: "" }
        remotePathField.text = p.remoteProjectPath
        runCommandField.text = p.runCommand
        debugCommandField.text = p.debugCommand
        autoReconnectCheck.isSelected = settings.autoReconnect

        // Ports table
        if (::portsTableModel.isInitialized) {
            portsTableModel.rowCount = 0
            for (pt in p.forwardedPorts) {
                portsTableModel.addRow(arrayOf(pt.localPort.toString(), pt.remotePort.toString(), pt.serviceName))
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
        p.runCommand = runCommandField.text.trim()
        p.debugCommand = debugCommandField.text.trim()

        if (::portsTableModel.isInitialized) {
            val updated = mutableListOf<PortMapping>()
            for (row in 0 until portsTableModel.rowCount) {
                val lPort = (portsTableModel.getValueAt(row, 0) as? String)?.toIntOrNull() ?: 8080
                val rPort = (portsTableModel.getValueAt(row, 1) as? String)?.toIntOrNull() ?: 8080
                val sName = (portsTableModel.getValueAt(row, 2) as? String) ?: "Service"
                updated.add(PortMapping(lPort, rPort, sName))
            }
            p.forwardedPorts = updated
        }
    }

    private fun resetWorkingCopy() {
        workingProfiles.clear()
        for (p in settings.profiles) {
            workingProfiles.add(p.copyProfile())
        }
        selectedIndex = settings.activeProfileIndex.coerceIn(workingProfiles.indices)

        profileListModel.clear()
        for (p in workingProfiles) {
            profileListModel.addElement(p)
        }
    }

    override fun isModified(): Boolean {
        saveCurrentSelection()
        if (settings.autoReconnect != autoReconnectCheck.isSelected) return true
        if (settings.activeProfileIndex != selectedIndex) return true
        if (settings.profiles.size != workingProfiles.size) return true
        for (i in workingProfiles.indices) {
            val a = workingProfiles[i]
            val b = settings.profiles[i]
            if (a.name != b.name || a.host != b.host || a.port != b.port ||
                a.user != b.user || a.password != b.password || a.privateKeyPath != b.privateKeyPath ||
                a.remoteProjectPath != b.remoteProjectPath || a.runCommand != b.runCommand ||
                a.debugCommand != b.debugCommand || a.authType != b.authType ||
                a.forwardedPorts.size != b.forwardedPorts.size
            ) {
                return true
            }
        }
        return false
    }

    override fun apply() {
        saveCurrentSelection()
        settings.profiles = workingProfiles.map { it.copyProfile() }.toMutableList()
        settings.activeProfileIndex = selectedIndex.coerceIn(settings.profiles.indices)
        settings.autoReconnect = autoReconnectCheck.isSelected

        project.messageBus.syncPublisher(RemoteConnectionListener.TOPIC).profileChanged(settings.activeProfile)
    }

    override fun reset() {
        resetWorkingCopy()
        if (workingProfiles.isNotEmpty()) {
            profileList.selectedIndex = selectedIndex
            loadProfileToForm(workingProfiles[selectedIndex])
        }
    }
}
