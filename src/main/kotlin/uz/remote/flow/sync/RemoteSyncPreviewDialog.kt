package uz.remote.flow.sync

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import uz.remote.flow.ssh.ServerEnvironment
import uz.remote.flow.ssh.ServerProfile
import java.awt.*
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

enum class SyncChangeType(val displayName: String, val icon: Icon, val color: Color) {
    ADDED("New", AllIcons.General.Add, Color(34, 197, 94)),
    MODIFIED("Modified", AllIcons.Actions.Edit, Color(59, 130, 246)),
    DELETED("Deleted", AllIcons.General.Remove, Color(239, 68, 68))
}

data class SyncDiffItem(
    val relativePath: String,
    val changeType: SyncChangeType,
    val details: String = ""
)

class RemoteSyncPreviewDialog(
    project: Project,
    private val profile: ServerProfile,
    private val allItems: List<SyncDiffItem>,
    private val onConfirmSync: () -> Unit
) : DialogWrapper(project, true) {

    private var currentFilterType: SyncChangeType? = null
    private var searchQuery: String = ""
    private var filteredItems: List<SyncDiffItem> = allItems

    private val searchField = SearchTextField()
    private val diffTableModel = DiffTableModel()
    private val diffTable = JBTable(diffTableModel)

    private val btnFilterAll = JToggleButton("All (${allItems.size})", true)
    private val addedCount = allItems.count { it.changeType == SyncChangeType.ADDED }
    private val modifiedCount = allItems.count { it.changeType == SyncChangeType.MODIFIED }
    private val deletedCount = allItems.count { it.changeType == SyncChangeType.DELETED }

    private val btnFilterAdded = JToggleButton("Added ($addedCount)")
    private val btnFilterModified = JToggleButton("Modified ($modifiedCount)")
    private val btnFilterDeleted = JToggleButton("Deleted ($deletedCount)")

    init {
        title = "Sync Preview (Dry Run): ${profile.name}"
        setOKButtonText("🚀 Sync Now (${allItems.size} Changes)")
        setCancelButtonText("Cancel")
        init()
        applyFilters()
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(0, 8))
        root.preferredSize = Dimension(650, 450)

        // 1. Header Banner
        val headerPanel = JPanel(BorderLayout(6, 4))
        headerPanel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(JBColor(Color(229, 231, 235), Color(60, 63, 65)), 1, true),
            JBUI.Borders.empty(8, 10)
        )
        headerPanel.background = JBColor(Color(249, 250, 251), Color(40, 42, 44))

        val titlePanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        titlePanel.isOpaque = false

        val serverLabel = JBLabel("Server: ${profile.name} (${profile.host})")
        serverLabel.font = serverLabel.font.deriveFont(Font.BOLD, 13f)
        titlePanel.add(serverLabel)

        // Environment Tag
        val envBadge = JLabel(" ${profile.environment.displayName} ")
        envBadge.isOpaque = true
        envBadge.background = Color(profile.environment.tagColorRgb)
        envBadge.foreground = Color.WHITE
        envBadge.font = envBadge.font.deriveFont(Font.BOLD, 10f)
        envBadge.border = BorderFactory.createEmptyBorder(2, 6, 2, 6)
        titlePanel.add(envBadge)

        headerPanel.add(titlePanel, BorderLayout.NORTH)

        val pathsLabel = JBLabel("Path: ${profile.localProjectPath.ifBlank { "." }} ➔ ${profile.remoteProjectPath}")
        pathsLabel.foreground = JBColor.GRAY
        headerPanel.add(pathsLabel, BorderLayout.SOUTH)

        root.add(headerPanel, BorderLayout.NORTH)

        // 2. Toolbar (Filter buttons & Search)
        val toolbar = JPanel(BorderLayout(8, 0))
        val filterGroup = ButtonGroup()
        filterGroup.add(btnFilterAll)
        filterGroup.add(btnFilterAdded)
        filterGroup.add(btnFilterModified)
        filterGroup.add(btnFilterDeleted)

        val btnBox = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        btnBox.add(btnFilterAll)
        btnBox.add(btnFilterAdded)
        btnBox.add(btnFilterModified)
        btnBox.add(btnFilterDeleted)

        btnFilterAll.addActionListener { currentFilterType = null; applyFilters() }
        btnFilterAdded.addActionListener { currentFilterType = SyncChangeType.ADDED; applyFilters() }
        btnFilterModified.addActionListener { currentFilterType = SyncChangeType.MODIFIED; applyFilters() }
        btnFilterDeleted.addActionListener { currentFilterType = SyncChangeType.DELETED; applyFilters() }

        searchField.addKeyboardListener(object : KeyAdapter() {
            override fun keyReleased(e: KeyEvent?) {
                searchQuery = searchField.text.trim()
                applyFilters()
            }
        })

        toolbar.add(btnBox, BorderLayout.WEST)
        toolbar.add(searchField, BorderLayout.EAST)

        // 3. Diff Table
        diffTable.setShowGrid(false)
        diffTable.rowHeight = 24
        diffTable.columnModel.getColumn(0).maxWidth = 110
        diffTable.columnModel.getColumn(0).minWidth = 90
        diffTable.columnModel.getColumn(0).cellRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                table: JTable?,
                value: Any?,
                isSelected: Boolean,
                hasFocus: Boolean,
                row: Int,
                column: Int
            ): Component {
                val comp = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column) as JLabel
                val type = value as? SyncChangeType
                if (type != null) {
                    comp.icon = type.icon
                    comp.text = type.displayName
                    comp.foreground = if (isSelected) table?.selectionForeground else type.color
                    comp.font = comp.font.deriveFont(Font.BOLD)
                }
                return comp
            }
        }

        val scrollPane = JBScrollPane(diffTable)
        scrollPane.border = BorderFactory.createLineBorder(JBColor(Color(229, 231, 235), Color(60, 63, 65)))

        val centerPanel = JPanel(BorderLayout(0, 6))
        centerPanel.add(toolbar, BorderLayout.NORTH)
        centerPanel.add(scrollPane, BorderLayout.CENTER)

        root.add(centerPanel, BorderLayout.CENTER)
        return root
    }

    private fun applyFilters() {
        filteredItems = allItems.filter { item ->
            val matchesType = currentFilterType == null || item.changeType == currentFilterType
            val matchesSearch = searchQuery.isBlank() || item.relativePath.contains(searchQuery, ignoreCase = true)
            matchesType && matchesSearch
        }
        diffTableModel.fireTableDataChanged()
    }

    override fun doOKAction() {
        super.doOKAction()
        onConfirmSync()
    }

    private inner class DiffTableModel : AbstractTableModel() {
        private val columnNames = arrayOf("Change", "Relative Path")

        override fun getRowCount(): Int = filteredItems.size
        override fun getColumnCount(): Int = columnNames.size
        override fun getColumnName(column: Int): String = columnNames[column]

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val item = filteredItems[rowIndex]
            return when (columnIndex) {
                0 -> item.changeType
                1 -> item.relativePath
                else -> ""
            }
        }
    }
}
