package uz.remote.flow.config

import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.IdeBorderFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import uz.remote.flow.files.RemoteFileManager
import uz.remote.flow.logging.LogCategory
import uz.remote.flow.logging.RemoteFlowLogService
import uz.remote.flow.ssh.RemoteConnectionManager
import uz.remote.flow.ssh.ServerProfile
import java.awt.*
import java.awt.event.ItemEvent
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.*
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

class RemoteConfigManagerDialog(
    private val project: Project,
    private val profile: ServerProfile,
    initialFilePath: String? = null
) : DialogWrapper(project, true) {

    private val connectionManager = RemoteConnectionManager.getInstance(project)
    private val fileManager = RemoteFileManager(project)
    private val logService = RemoteFlowLogService.getInstance(project)

    private val defaultCandidates = listOf(
        ".env",
        ".env.local",
        ".env.production",
        ".env.development",
        "application.yml",
        "application.yaml",
        "application-dev.yml",
        "application-prod.yml",
        "application.properties",
        "docker-compose.yml",
        "docker-compose.override.yml"
    )

    private val fileComboBox = JComboBox<String>()
    private val btnScanServer = JButton("🔍 Scan Server")
    private val btnReload = JButton("⟳ Reload")
    private val fileStatusLabel = JBLabel("Checking...")

    private val tabbedPane = JBTabbedPane()

    // Tab 1: Key-Value Table
    private val tableColumns = arrayOf("Key", "Value", "Masked")
    private val tableModel = object : DefaultTableModel(tableColumns, 0) {
        override fun isCellEditable(row: Int, column: Int): Boolean = column == 0 || column == 1
    }
    private val configTable = JBTable(tableModel)
    private val chkShowSecrets = JBCheckBox("👁 Show Secrets (Parol va tokenlarni ko'rsatish)", false)
    private val btnAddKey = JButton("➕ Add Key")
    private val btnRemoveKey = JButton("➖ Remove Key")

    // Tab 2: Raw Editor
    private val rawTextArea = JTextArea()
    private val rawStatusLabel = JBLabel("Lines: 0 | Characters: 0")

    // Options
    private val chkCreateBackup = JBCheckBox("Serverda avtomatik zaxira nusxa (.bak) yaratish", true)
    private val btnSave = JButton("💾 Save to Remote Server")

    private var currentRemoteFullPath = ""
    private var isUpdatingContent = false
    private var isSecretMasked = true

    init {
        title = "⚙ Remote Config Manager (.env / application.yml) — ${profile.name}"
        init()

        setupFileCandidates(initialFilePath)
        loadSelectedConfigFile()
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(0, 8))
        root.preferredSize = Dimension(780, 520)
        root.border = JBUI.Borders.empty(8)

        root.add(createTopFileSelectorBar(), BorderLayout.NORTH)
        root.add(createContentTabs(), BorderLayout.CENTER)
        root.add(createBottomActionBar(), BorderLayout.SOUTH)

        return root
    }

    private fun createTopFileSelectorBar(): JPanel {
        val panel = JPanel(BorderLayout(8, 0))
        panel.border = IdeBorderFactory.createTitledBorder("Masofaviy Konfiguratsiya Fayli", false)

        val left = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2))
        left.add(JBLabel("Config File:"))

        fileComboBox.isEditable = true
        fileComboBox.preferredSize = Dimension(260, 26)
        fileComboBox.addActionListener {
            if (!isUpdatingContent) {
                loadSelectedConfigFile()
            }
        }
        left.add(fileComboBox)

        btnScanServer.toolTipText = "Loyiha papkasidagi barcha .env va *.yml fayllarni serverdan qidirish"
        btnScanServer.addActionListener { scanServerForConfigs() }
        left.add(btnScanServer)

        btnReload.toolTipText = "Faylni serverdan qayta o'qish"
        btnReload.addActionListener { loadSelectedConfigFile() }
        left.add(btnReload)

        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 2))
        fileStatusLabel.font = fileStatusLabel.font.deriveFont(Font.BOLD, 11f)
        right.add(fileStatusLabel)

        panel.add(left, BorderLayout.WEST)
        panel.add(right, BorderLayout.EAST)
        return panel
    }

    private fun createContentTabs(): JPanel {
        val panel = JPanel(BorderLayout())

        // Tab 1: Key-Value View
        val kvPanel = JPanel(BorderLayout(0, 4))
        kvPanel.border = JBUI.Borders.empty(4)

        val kvTopBar = JPanel(BorderLayout())
        val kvLeft = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2))
        chkShowSecrets.addItemListener { e ->
            isSecretMasked = e.stateChange != ItemEvent.SELECTED
            configTable.repaint()
        }
        kvLeft.add(chkShowSecrets)

        val kvRight = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 2))
        btnAddKey.addActionListener {
            val k = Messages.showInputDialog(panel, "Yangi kalit (Key) nomini kiriting:", "Yangi Kalit Qo'shish", null)
            if (!k.isNullOrBlank()) {
                val isSec = isSensitiveKey(k)
                tableModel.addRow(arrayOf(k.trim(), "", if (isSec) "🔒 Secret" else "Text"))
                syncTableToRaw()
            }
        }
        btnRemoveKey.addActionListener {
            val sel = configTable.selectedRow
            if (sel in 0 until tableModel.rowCount) {
                tableModel.removeRow(sel)
                syncTableToRaw()
            }
        }
        kvRight.add(btnAddKey)
        kvRight.add(btnRemoveKey)

        kvTopBar.add(kvLeft, BorderLayout.WEST)
        kvTopBar.add(kvRight, BorderLayout.EAST)
        kvPanel.add(kvTopBar, BorderLayout.NORTH)

        // Custom Cell Renderer for Password Masking
        configTable.rowHeight = 24
        configTable.setDefaultRenderer(Any::class.java, object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                table: JTable?,
                value: Any?,
                isSelected: Boolean,
                hasFocus: Boolean,
                row: Int,
                column: Int
            ): Component {
                val comp = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
                if (column == 1 && isSecretMasked && row in 0 until tableModel.rowCount) {
                    val key = tableModel.getValueAt(row, 0)?.toString() ?: ""
                    if (isSensitiveKey(key) && value != null) {
                        text = "•".repeat(value.toString().length.coerceIn(6, 16))
                    }
                }
                return comp
            }
        })

        tableModel.addTableModelListener { e ->
            if (!isUpdatingContent && e.column in 0..1) {
                syncTableToRaw()
            }
        }

        kvPanel.add(JBScrollPane(configTable), BorderLayout.CENTER)
        tabbedPane.addTab("📋 Key-Value Table", kvPanel)

        // Tab 2: Raw Text Editor
        val rawPanel = JPanel(BorderLayout(0, 4))
        rawPanel.border = JBUI.Borders.empty(4)

        rawTextArea.font = Font("Monospaced", Font.PLAIN, 12)
        rawTextArea.tabSize = 2
        rawTextArea.border = JBUI.Borders.empty(4)
        rawTextArea.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) { updateRawStatus() }
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) { updateRawStatus() }
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) { updateRawStatus() }
        })

        rawPanel.add(JBScrollPane(rawTextArea), BorderLayout.CENTER)

        val rawBottomBar = JPanel(BorderLayout())
        rawStatusLabel.font = rawStatusLabel.font.deriveFont(Font.PLAIN, 11f)
        rawBottomBar.add(rawStatusLabel, BorderLayout.WEST)
        rawPanel.add(rawBottomBar, BorderLayout.SOUTH)

        tabbedPane.addTab("📝 Raw Editor (YAML / ENV)", rawPanel)

        // Sync when tabs switch
        tabbedPane.addChangeListener {
            if (tabbedPane.selectedIndex == 0) {
                // Switching to Key-Value: parse raw text
                parseRawToTable(rawTextArea.text)
            } else {
                // Switching to Raw
                syncTableToRaw()
            }
        }

        panel.add(tabbedPane, BorderLayout.CENTER)
        return panel
    }

    private fun createBottomActionBar(): JPanel {
        val panel = JPanel(BorderLayout(8, 0))
        panel.border = JBUI.Borders.empty(4, 0)

        val left = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        chkCreateBackup.toolTipText = "Fayl saqlanishidan avval serverda .bak nusxasi olinadi"
        left.add(chkCreateBackup)

        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0))
        btnSave.font = btnSave.font.deriveFont(Font.BOLD)
        btnSave.foreground = JBColor(Color(16, 185, 129), Color(16, 185, 129))
        btnSave.toolTipText = "O'zgarishlarni serverga yuklash"
        btnSave.addActionListener { saveContentToRemote() }
        right.add(btnSave)

        val btnClose = JButton("Close")
        btnClose.addActionListener { doCancelAction() }
        right.add(btnClose)

        panel.add(left, BorderLayout.WEST)
        panel.add(right, BorderLayout.EAST)
        return panel
    }

    override fun createActions(): Array<Action> {
        // Return empty array to use our custom bottom bar
        return emptyArray()
    }

    private fun setupFileCandidates(initialPath: String?) {
        fileComboBox.removeAllItems()
        if (!initialPath.isNullOrBlank()) {
            fileComboBox.addItem(initialPath)
        }
        for (cand in defaultCandidates) {
            if (cand != initialPath) {
                fileComboBox.addItem(cand)
            }
        }
        fileComboBox.selectedIndex = 0
    }

    private fun resolveSelectedPath(): String {
        val rel = (fileComboBox.editor.item?.toString() ?: fileComboBox.selectedItem?.toString() ?: ".env").trim()
        val base = profile.remoteProjectPath.trimEnd('/')
        return if (rel.startsWith("/")) rel else "$base/$rel"
    }

    private fun loadSelectedConfigFile() {
        val fullPath = resolveSelectedPath()
        currentRemoteFullPath = fullPath
        fileStatusLabel.text = "Yuklanmoqda..."
        fileStatusLabel.foreground = JBColor.GRAY

        fileManager.readFileContent(profile, fullPath) { content, err ->
            ApplicationManager.getApplication().invokeLater {
                if (err != null || content == null) {
                    fileStatusLabel.text = "⚪ Serverda mavjud emas (Yangi fayl yaratiladi)"
                    fileStatusLabel.foreground = JBColor.ORANGE
                    isUpdatingContent = true
                    rawTextArea.text = ""
                    tableModel.rowCount = 0
                    isUpdatingContent = false
                    updateRawStatus()
                } else {
                    val sizeKb = String.format(java.util.Locale.US, "%.1f KB", content.toByteArray().size / 1024.0)
                    fileStatusLabel.text = "🟢 Serverda mavjud ($sizeKb)"
                    fileStatusLabel.foreground = JBColor(Color(16, 185, 129), Color(16, 185, 129))

                    isUpdatingContent = true
                    rawTextArea.text = content
                    parseRawToTable(content)
                    isUpdatingContent = false
                    updateRawStatus()
                }
            }
        }
    }

    private fun scanServerForConfigs() {
        fileStatusLabel.text = "Server tekshirilmoqda..."
        val base = profile.remoteProjectPath.trimEnd('/')
        val cmd = "find \"$base\" -maxdepth 2 \\( -name \".env*\" -o -name \"*.yml\" -o -name \"*.yaml\" -o -name \"*.properties\" \\) 2>/dev/null"

        val foundLines = mutableListOf<String>()
        connectionManager.executeRemoteCommand(
            cmd = cmd,
            workingDir = "",
            onOutput = { line ->
                val trimmed = line.trim()
                if (trimmed.isNotBlank()) foundLines.add(trimmed)
            },
            onComplete = { code ->
                ApplicationManager.getApplication().invokeLater {
                    if (code == 0 && foundLines.isNotEmpty()) {
                        isUpdatingContent = true
                        fileComboBox.removeAllItems()
                        for (path in foundLines) {
                            val rel = if (path.startsWith(base)) path.removePrefix(base).trimStart('/') else path
                            fileComboBox.addItem(rel)
                        }
                        isUpdatingContent = false
                        fileComboBox.selectedIndex = 0
                        loadSelectedConfigFile()
                        Messages.showInfoMessage(project, "${foundLines.size} ta konfiguratsiya fayli serverda topildi!", "Scan Natijasi")
                    } else {
                        Messages.showInfoMessage(project, "Loyiha papkasida qo'shimcha maxsus config fayllar topilmadi.", "Scan Natijasi")
                    }
                }
            }
        )
    }

    private fun isSensitiveKey(key: String): Boolean {
        val lower = key.lowercase()
        return lower.contains("pass") ||
                lower.contains("secret") ||
                lower.contains("token") ||
                lower.contains("key") ||
                lower.contains("auth") ||
                lower.contains("pwd") ||
                lower.contains("credential") ||
                lower.contains("private")
    }

    private fun parseRawToTable(text: String) {
        tableModel.rowCount = 0
        val lines = text.lines()
        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isBlank() || line.startsWith("#") || line.startsWith("//")) continue

            if (line.contains("=")) {
                // .env style: KEY=VALUE or export KEY=VALUE
                val clean = line.removePrefix("export ").trim()
                val eqIdx = clean.indexOf('=')
                if (eqIdx > 0) {
                    val k = clean.substring(0, eqIdx).trim()
                    var v = clean.substring(eqIdx + 1).trim()
                    if ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))) {
                        v = v.substring(1, v.length - 1)
                    }
                    val isSec = isSensitiveKey(k)
                    tableModel.addRow(arrayOf(k, v, if (isSec) "🔒 Secret" else "Text"))
                }
            } else if (line.contains(":")) {
                // simple YAML style: key: value
                val colonIdx = line.indexOf(':')
                if (colonIdx > 0) {
                    val k = line.substring(0, colonIdx).trim()
                    var v = line.substring(colonIdx + 1).trim()
                    if ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))) {
                        v = v.substring(1, v.length - 1)
                    }
                    if (v.isNotBlank()) {
                        val isSec = isSensitiveKey(k)
                        tableModel.addRow(arrayOf(k, v, if (isSec) "🔒 Secret" else "Text"))
                    }
                }
            }
        }
    }

    private fun syncTableToRaw() {
        if (isUpdatingContent) return
        val currentFileName = (fileComboBox.editor.item?.toString() ?: "").lowercase()
        val isYaml = currentFileName.endsWith(".yml") || currentFileName.endsWith(".yaml")

        val sb = StringBuilder()
        sb.append("# Generated / Updated via Remote Flow Config Manager\n")
        sb.append("# Server: ${profile.name} (${profile.host})\n\n")

        for (i in 0 until tableModel.rowCount) {
            val k = tableModel.getValueAt(i, 0)?.toString()?.trim() ?: continue
            val v = tableModel.getValueAt(i, 1)?.toString() ?: ""
            if (k.isBlank()) continue

            if (isYaml) {
                sb.append("$k: \"$v\"\n")
            } else {
                sb.append("$k=\"$v\"\n")
            }
        }

        isUpdatingContent = true
        rawTextArea.text = sb.toString()
        isUpdatingContent = false
        updateRawStatus()
    }

    private fun updateRawStatus() {
        val lines = rawTextArea.lineCount
        val chars = rawTextArea.text.length
        rawStatusLabel.text = "Lines: $lines | Characters: $chars"
    }

    private fun saveContentToRemote() {
        val targetPath = resolveSelectedPath()
        val newContent = rawTextArea.text

        btnSave.isEnabled = false
        fileStatusLabel.text = "Saqlanmoqda..."

        fun doActualSave() {
            fileManager.saveFileContent(profile, targetPath, newContent) { ok, err ->
                ApplicationManager.getApplication().invokeLater {
                    btnSave.isEnabled = true
                    if (ok) {
                        fileStatusLabel.text = "🟢 Saqlandi (${SimpleDateFormat("HH:mm:ss").format(Date())})"
                        fileStatusLabel.foreground = JBColor(Color(16, 185, 129), Color(16, 185, 129))
                        logService.log(
                            "[CONFIG SAVED] '$targetPath' serverda muvaffaqiyatli saqlandi!",
                            LogCategory.FILES,
                            profile.name
                        )
                        connectionManager.notifyUser(
                            "Remote Config Saqlandi ⚙",
                            "'$targetPath' serverda yangilandi!",
                            NotificationType.INFORMATION
                        )
                    } else {
                        fileStatusLabel.text = "❌ Saqlashda xatolik: $err"
                        fileStatusLabel.foreground = JBColor.RED
                        Messages.showErrorDialog(project, "Serverga saqlab bo'lmadi:\n$err", "Config Saqlash Xatosi")
                    }
                }
            }
        }

        if (chkCreateBackup.isSelected) {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
            val bakCmd = "if [ -f \"$targetPath\" ]; then cp \"$targetPath\" \"$targetPath.bak.$timestamp\"; fi"
            connectionManager.executeRemoteCommand(
                cmd = bakCmd,
                workingDir = "",
                onOutput = {},
                onComplete = {
                    doActualSave()
                }
            )
        } else {
            doActualSave()
        }
    }
}
