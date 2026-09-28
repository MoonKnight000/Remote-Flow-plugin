package uz.remote.flow.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.IdeBorderFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import uz.remote.flow.logging.LogCategory
import uz.remote.flow.logging.RemoteFlowLogService
import uz.remote.flow.settings.RemoteFlowConfigurable
import uz.remote.flow.settings.RemoteFlowSettings
import uz.remote.flow.ssh.RemoteConnectionListener
import uz.remote.flow.ssh.ServerProfile
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.event.DocumentEvent

class RemoteFlowLogPanel(private val project: Project) : JPanel(BorderLayout(0, 4)), Disposable {

    private val logService = RemoteFlowLogService.getInstance(project)
    private val settings = RemoteFlowSettings.getInstance(project)

    private val btnClearLogs = JButton("Clear Logs")
    private val filterTextField = JBTextField()
    private val serverComboBox = JComboBox<String>()
    private val categoryComboBox = JComboBox<LogCategory>(LogCategory.values())
    private val btnEditConfig = JButton("Edit Config")

    init {
        border = JBUI.Borders.empty(4)

        add(createToolbarPanel(), BorderLayout.NORTH)
        add(logService.consoleView.component, BorderLayout.CENTER)

        setupListeners()
        refreshServerComboBox()

        // Subscribe to profile changes to keep server dropdown in sync
        project.messageBus.connect(this).subscribe(RemoteConnectionListener.TOPIC, object : RemoteConnectionListener {
            override fun connectionStateChanged(connected: Boolean, profile: ServerProfile) {
                // Connection state changed event
            }

            override fun profileChanged(profile: ServerProfile) {
                ApplicationManager.getApplication().invokeLater {
                    refreshServerComboBox()
                }
            }
        })
    }

    override fun dispose() {
        // Disposer handled by IntelliJ Content
    }

    private fun createToolbarPanel(): JPanel {
        val root = JPanel(BorderLayout(4, 0))
        root.border = JBUI.Borders.empty(2, 4, 4, 4)

        // Title row (similar to GitHub Copilot MCP Log)
        val titleLabel = JBLabel("Remote Flow Unified Log")
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 13f)
        titleLabel.border = JBUI.Borders.empty(2, 4, 4, 4)

        // Controls toolbar
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2))

        // 1. Clear Logs button
        btnClearLogs.toolTipText = "Barcha loglarni tozalash"
        toolbar.add(btnClearLogs)

        // 2. Filter Search Field
        toolbar.add(JBLabel("Filter:"))
        filterTextField.emptyText.text = "Search logs..."
        filterTextField.preferredSize = Dimension(180, 26)
        toolbar.add(filterTextField)

        // 3. Server selector
        toolbar.add(JBLabel("Server:"))
        serverComboBox.preferredSize = Dimension(140, 26)
        toolbar.add(serverComboBox)

        // 4. Category selector
        toolbar.add(JBLabel("Category:"))
        categoryComboBox.preferredSize = Dimension(130, 26)
        toolbar.add(categoryComboBox)

        // 5. Edit Config button
        btnEditConfig.toolTipText = "Remote Flow sozlamalarini ochish"
        btnEditConfig.addActionListener {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, RemoteFlowConfigurable::class.java)
        }
        toolbar.add(btnEditConfig)

        root.add(titleLabel, BorderLayout.NORTH)
        root.add(toolbar, BorderLayout.CENTER)
        return root
    }

    private fun setupListeners() {
        btnClearLogs.addActionListener {
            logService.clearLogs()
        }

        filterTextField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                triggerFilterUpdate()
            }
        })

        serverComboBox.addActionListener {
            triggerFilterUpdate()
        }

        categoryComboBox.addActionListener {
            triggerFilterUpdate()
        }
    }

    private fun triggerFilterUpdate() {
        val selectedCat = categoryComboBox.selectedItem as? LogCategory ?: LogCategory.ALL
        val selectedServer = serverComboBox.selectedItem as? String ?: "All Servers"
        val query = filterTextField.text.trim()
        logService.applyFilters(selectedCat, selectedServer, query)
    }

    private fun refreshServerComboBox() {
        val current = serverComboBox.selectedItem as? String
        serverComboBox.removeAllItems()
        serverComboBox.addItem("All Servers")
        for (p in settings.profiles) {
            serverComboBox.addItem(p.name)
        }
        if (current != null && settings.profiles.any { it.name == current }) {
            serverComboBox.selectedItem = current
        } else {
            serverComboBox.selectedIndex = 0
        }
    }
}
