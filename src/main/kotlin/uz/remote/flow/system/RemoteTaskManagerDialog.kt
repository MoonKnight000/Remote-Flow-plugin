package uz.remote.flow.system

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import uz.remote.flow.ssh.RemoteConnectionManager
import uz.remote.flow.ssh.ServerProfile
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

class RemoteTaskManagerDialog(
    private val project: Project,
    private val profile: ServerProfile
) : DialogWrapper(project, true) {

    private val statsManager = ServerStatsManager(project)
    private val connectionManager = RemoteConnectionManager.getInstance(project)

    private val searchField = JBTextField()
    private val sortComboBox = JComboBox(arrayOf("Sort by CPU % (Highest)", "Sort by RAM % (Highest)", "Sort by Name (A-Z)"))
    private val btnRefresh = JButton("⟳ Refresh")
    private val chkAutoRefresh = JBCheckBox("Auto (5s)")
    private val lblStatus = JBLabel("Loading processes...")

    private val btnTerminate = JButton("🛑 Terminate (SIGTERM)")
    private val btnForceKill = JButton("⚡ Force Kill (SIGKILL)")

    private lateinit var tableModel: DefaultTableModel
    private lateinit var table: JBTable

    private var allProcesses: List<ProcessInfo> = emptyList()
    private var scheduledTask: ScheduledFuture<*>? = null
    private val executor = AppExecutorUtil.getAppScheduledExecutorService()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    init {
        title = "Remote Task Manager - ${profile.name} (${profile.host})"
        init()
        refreshProcesses()
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(0, 8))
        root.preferredSize = Dimension(820, 520)
        root.border = JBUI.Borders.empty(6)

        // 1. Toolbar
        val topPanel = JPanel(BorderLayout(6, 4))

        val leftToolbar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2))
        searchField.emptyText.text = "Filter by name, PID, or user..."
        searchField.preferredSize = Dimension(220, 26)
        searchField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                applyFilter()
            }
        })
        leftToolbar.add(searchField)

        sortComboBox.preferredSize = Dimension(190, 26)
        sortComboBox.addActionListener {
            refreshProcesses()
        }
        leftToolbar.add(sortComboBox)

        btnRefresh.addActionListener {
            refreshProcesses()
        }
        leftToolbar.add(btnRefresh)

        chkAutoRefresh.addActionListener {
            toggleAutoRefresh(chkAutoRefresh.isSelected)
        }
        leftToolbar.add(chkAutoRefresh)

        topPanel.add(leftToolbar, BorderLayout.WEST)

        val rightToolbar = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 2))
        lblStatus.font = lblStatus.font.deriveFont(Font.PLAIN, 11f)
        lblStatus.foreground = JBColor.GRAY
        rightToolbar.add(lblStatus)
        topPanel.add(rightToolbar, BorderLayout.EAST)

        root.add(topPanel, BorderLayout.NORTH)

        // 2. Table
        val columnNames = arrayOf("PID", "User", "CPU %", "MEM %", "RAM (MB)", "Command / Process")
        tableModel = object : DefaultTableModel(columnNames, 0) {
            override fun isCellEditable(row: Int, column: Int): Boolean = false
        }

        table = JBTable(tableModel)
        table.rowHeight = 24
        table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION

        table.columnModel.getColumn(0).preferredWidth = 70
        table.columnModel.getColumn(1).preferredWidth = 90
        table.columnModel.getColumn(2).preferredWidth = 75
        table.columnModel.getColumn(3).preferredWidth = 75
        table.columnModel.getColumn(4).preferredWidth = 85
        table.columnModel.getColumn(5).preferredWidth = 420

        // Custom Cell Renderer for CPU & MEM (Highlight high resource usage)
        val resourceRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                t: JTable?, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
            ): Component {
                val c = super.getTableCellRendererComponent(t, value, isSelected, hasFocus, row, column) as JLabel
                val str = value?.toString() ?: ""
                val num = str.removeSuffix("%").toDoubleOrNull() ?: 0.0

                if (!isSelected) {
                    when {
                        num >= 70.0 -> {
                            c.foreground = JBColor(Color(220, 38, 38), Color(248, 113, 113))
                            c.font = c.font.deriveFont(Font.BOLD)
                        }
                        num >= 30.0 -> {
                            c.foreground = JBColor(Color(217, 119, 6), Color(251, 191, 36))
                            c.font = c.font.deriveFont(Font.BOLD)
                        }
                        else -> {
                            c.foreground = JBColor.foreground()
                            c.font = c.font.deriveFont(Font.PLAIN)
                        }
                    }
                }
                return c
            }
        }
        table.columnModel.getColumn(2).cellRenderer = resourceRenderer
        table.columnModel.getColumn(3).cellRenderer = resourceRenderer

        // Context Menu
        val popup = JPopupMenu()
        val itemSigterm = JMenuItem("🛑 Terminate (SIGTERM - 15)")
        itemSigterm.addActionListener { killSelectedProcess(force = false) }
        popup.add(itemSigterm)

        val itemSigkill = JMenuItem("⚡ Force Kill (SIGKILL - 9)")
        itemSigkill.addActionListener { killSelectedProcess(force = true) }
        popup.add(itemSigkill)

        popup.addSeparator()

        val itemCopyPid = JMenuItem("📋 Copy PID")
        itemCopyPid.addActionListener {
            val pid = getSelectedPid() ?: return@addActionListener
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(pid), null)
        }
        popup.add(itemCopyPid)

        val itemCopyCmd = JMenuItem("📋 Copy Full Command")
        itemCopyCmd.addActionListener {
            val cmd = getSelectedCommand() ?: return@addActionListener
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(cmd), null)
        }
        popup.add(itemCopyCmd)

        table.componentPopupMenu = popup
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val cmd = getSelectedCommand()
                    if (!cmd.isNullOrBlank()) {
                        Messages.showInfoMessage(project, cmd, "Full Process Command")
                    }
                }
            }
        })

        val scrollPane = JBScrollPane(table)
        root.add(scrollPane, BorderLayout.CENTER)

        // 3. Bottom Action Bar
        val bottomPanel = JPanel(BorderLayout(8, 0))
        bottomPanel.border = JBUI.Borders.empty(4, 0, 0, 0)

        val actionsLeft = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))

        btnTerminate.toolTipText = "Send graceful termination signal (SIGTERM 15) to selected process"
        btnTerminate.addActionListener { killSelectedProcess(force = false) }
        actionsLeft.add(btnTerminate)

        btnForceKill.toolTipText = "Send uncatchable kill signal (SIGKILL 9) to forcefully terminate selected process"
        btnForceKill.foreground = JBColor.RED
        btnForceKill.addActionListener { killSelectedProcess(force = true) }
        actionsLeft.add(btnForceKill)

        bottomPanel.add(actionsLeft, BorderLayout.WEST)

        root.add(bottomPanel, BorderLayout.SOUTH)
        return root
    }

    override fun createActions(): Array<Action> {
        return arrayOf(okAction)
    }

    init {
        okAction.putValue(Action.NAME, "Close")
    }

    private fun getSelectedPid(): String? {
        val row = table.selectedRow
        if (row < 0 || row >= tableModel.rowCount) return null
        return tableModel.getValueAt(row, 0) as? String
    }

    private fun getSelectedCommand(): String? {
        val row = table.selectedRow
        if (row < 0 || row >= tableModel.rowCount) return null
        return tableModel.getValueAt(row, 5) as? String
    }

    private fun killSelectedProcess(force: Boolean) {
        val pid = getSelectedPid()
        if (pid.isNullOrBlank()) {
            Messages.showWarningDialog(project, "Please select a process from the table first.", "Task Manager")
            return
        }
        val cmd = getSelectedCommand() ?: ""
        val shortCmd = if (cmd.length > 60) cmd.substring(0, 57) + "..." else cmd
        val actionName = if (force) "Force Kill (SIGKILL -9)" else "Terminate (SIGTERM -15)"

        val confirm = Messages.showYesNoDialog(
            project,
            "Are you sure you want to $actionName for process:\n\nPID: $pid\nCommand: $shortCmd\n\nThis will stop the process on remote server ${profile.name}.",
            "Confirm Process Termination",
            Messages.getWarningIcon()
        )
        if (confirm != Messages.YES) return

        lblStatus.text = "Signaling process $pid..."
        statsManager.terminateProcess(pid, force) { success, message ->
            ApplicationManager.getApplication().invokeLater {
                lblStatus.text = message
                if (success) {
                    refreshProcesses()
                } else {
                    Messages.showErrorDialog(project, message, "Failed to Terminate Process")
                }
            }
        }
    }

    private fun toggleAutoRefresh(enable: Boolean) {
        scheduledTask?.cancel(true)
        scheduledTask = null
        if (enable) {
            scheduledTask = executor.scheduleWithFixedDelay({
                if (isShowing && connectionManager.isConnected) {
                    refreshProcesses(silent = true)
                }
            }, 5, 5, TimeUnit.SECONDS)
        }
    }

    private fun refreshProcesses(silent: Boolean = false) {
        if (!connectionManager.isConnected) {
            lblStatus.text = "Server offline"
            return
        }

        if (!silent) {
            lblStatus.text = "Fetching processes..."
            btnRefresh.isEnabled = false
        }

        val sortKey = when (sortComboBox.selectedIndex) {
            1 -> "MEM"
            2 -> "NAME"
            else -> "CPU"
        }

        statsManager.fetchTopProcesses(
            sortBy = sortKey,
            limit = 50,
            onSuccess = { procs ->
                allProcesses = procs
                ApplicationManager.getApplication().invokeLater {
                    btnRefresh.isEnabled = true
                    applyFilter()
                    val time = timeFormat.format(Date())
                    lblStatus.text = "${procs.size} processes (Updated $time)"
                }
            },
            onError = { error ->
                ApplicationManager.getApplication().invokeLater {
                    btnRefresh.isEnabled = true
                    lblStatus.text = error
                }
            }
        )
    }

    private fun applyFilter() {
        val query = searchField.text.trim().lowercase(Locale.US)
        val filtered = if (query.isEmpty()) {
            allProcesses
        } else {
            allProcesses.filter {
                it.command.lowercase(Locale.US).contains(query) ||
                it.pid.contains(query) ||
                it.user.lowercase(Locale.US).contains(query)
            }
        }

        val selectedPid = getSelectedPid()
        tableModel.rowCount = 0
        var newSelectedRow = -1

        for ((index, proc) in filtered.withIndex()) {
            tableModel.addRow(arrayOf(
                proc.pid,
                proc.user,
                String.format("%.1f%%", proc.cpuPercent),
                String.format("%.1f%%", proc.memPercent),
                String.format("%.1f MB", proc.rssMb),
                proc.command
            ))
            if (proc.pid == selectedPid) {
                newSelectedRow = index
            }
        }

        if (newSelectedRow != -1) {
            table.setRowSelectionInterval(newSelectedRow, newSelectedRow)
        }
    }

    override fun dispose() {
        scheduledTask?.cancel(true)
        scheduledTask = null
        super.dispose()
    }
}
