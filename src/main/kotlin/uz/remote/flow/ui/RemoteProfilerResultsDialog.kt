package uz.remote.flow.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import uz.remote.flow.profiler.CpuHotspot
import uz.remote.flow.profiler.MemoryAllocation
import uz.remote.flow.profiler.ProfilingSnapshot
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.swing.Action
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableRowSorter

class RemoteProfilerResultsDialog(
    val project: Project,
    val snapshot: ProfilingSnapshot
) : DialogWrapper(project) {

    init {
        title = "Remote Performance Profile Results (${snapshot.durationSeconds}s Recording)"
        setOKButtonText("Close")
        init()
    }

    override fun createActions(): Array<Action> {
        val actions = mutableListOf<Action>()
        if (snapshot.jfrFile != null && snapshot.jfrFile.exists()) {
            actions.add(object : DialogWrapperAction("Open in IntelliJ Profiler") {
                init {
                    putValue(SMALL_ICON, AllIcons.Actions.Profile)
                }
                override fun doAction(e: java.awt.event.ActionEvent?) {
                    openInIntelliJProfiler()
                    close(OK_EXIT_CODE)
                }
            })
        }
        actions.add(okAction)
        return actions.toTypedArray()
    }

    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout(0, 10)).apply {
            preferredSize = Dimension(820, 520)
            minimumSize = Dimension(600, 400)
        }

        // 1. Metric Badges Banner
        mainPanel.add(buildSummaryBanner(), BorderLayout.NORTH)

        // 2. Tabs: CPU Hotspots, Memory Allocations, JFR Details
        val tabbedPane = JBTabbedPane()
        tabbedPane.addTab("🔥 CPU Hotspots (${snapshot.hotspots.size})", buildCpuHotspotsTab())
        tabbedPane.addTab("💾 Memory Allocations (${snapshot.memoryAllocations.size})", buildMemoryAllocationsTab())
        tabbedPane.addTab("⚙️ Snapshot Details", buildSnapshotDetailsTab())

        mainPanel.add(tabbedPane, BorderLayout.CENTER)
        return mainPanel
    }

    private fun buildSummaryBanner(): JPanel {
        val banner = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = BorderFactory.createCompoundBorder(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(8, 12)
            )
            background = UIUtil.getPanelBackground()
        }

        banner.add(createMetricCard("Duration", "${snapshot.durationSeconds}s", "⏱️"))
        banner.add(Box.createHorizontalStrut(10))
        banner.add(createMetricCard("Peak CPU", "${String.format("%.1f", snapshot.peakCpuPercent)}%", "⚡"))
        banner.add(Box.createHorizontalStrut(10))
        banner.add(createMetricCard("Avg CPU", "${String.format("%.1f", snapshot.averageCpuPercent)}%", "📈"))
        banner.add(Box.createHorizontalStrut(10))
        banner.add(createMetricCard("Peak Heap", "${snapshot.peakHeapUsedMb} MB", "💾"))
        banner.add(Box.createHorizontalStrut(10))
        banner.add(createMetricCard("Samples", "${snapshot.totalSamples}", "🎯"))
        if (snapshot.gcCount > 0) {
            banner.add(Box.createHorizontalStrut(10))
            banner.add(createMetricCard("GC Pauses", "${snapshot.gcCount} (${snapshot.gcTotalPauseMs}ms)", "♻️"))
        }

        return banner
    }

    private fun createMetricCard(label: String, value: String, iconStr: String): JPanel {
        return JPanel(BorderLayout(4, 2)).apply {
            border = BorderFactory.createCompoundBorder(
                JBUI.Borders.customLine(JBColor(Color(220, 220, 220), Color(60, 60, 60)), 1),
                JBUI.Borders.empty(4, 8)
            )
            val lbl = JBLabel("$iconStr $label").apply {
                font = font.deriveFont(Font.PLAIN, 10f)
                foreground = JBColor.GRAY
            }
            val valLbl = JBLabel(value).apply {
                font = font.deriveFont(Font.BOLD, 12f)
            }
            add(lbl, BorderLayout.NORTH)
            add(valLbl, BorderLayout.CENTER)
        }
    }

    private fun buildCpuHotspotsTab(): JPanel {
        val panel = JPanel(BorderLayout(0, 6)).apply {
            border = JBUI.Borders.empty(8)
        }

        val hintLabel = JBLabel("Double-click any method to navigate directly to source code.").apply {
            font = font.deriveFont(Font.ITALIC, 11f)
            foreground = JBColor.GRAY
        }

        val searchField = SearchTextField().apply {
            textEditor.emptyText.text = "Filter methods or classes..."
        }

        val topBar = JPanel(BorderLayout(8, 0)).apply {
            add(searchField, BorderLayout.CENTER)
            add(hintLabel, BorderLayout.EAST)
        }
        panel.add(topBar, BorderLayout.NORTH)

        val columnNames = arrayOf("#", "Method", "Class / Package", "Samples", "CPU %")
        val model = object : DefaultTableModel(columnNames, 0) {
            override fun isCellEditable(row: Int, column: Int): Boolean = false
        }

        for (h in snapshot.hotspots) {
            model.addRow(arrayOf<Any>(h.rank, "${h.methodName}()", h.className, h.sampleCount, h.cpuPercent))
        }

        val table = JBTable(model).apply {
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            rowHeight = 24
            columnModel.getColumn(0).preferredWidth = 35
            columnModel.getColumn(1).preferredWidth = 220
            columnModel.getColumn(2).preferredWidth = 320
            columnModel.getColumn(3).preferredWidth = 70
            columnModel.getColumn(4).preferredWidth = 110

            // Custom renderer for CPU % progress bar
            columnModel.getColumn(4).cellRenderer = object : DefaultTableCellRenderer() {
                private val bar = JProgressBar(0, 100).apply {
                    isStringPainted = true
                    foreground = JBColor(Color(76, 175, 80), Color(90, 195, 95))
                }
                override fun getTableCellRendererComponent(
                    table: JTable?, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
                ): Component {
                    val pct = (value as? Double) ?: 0.0
                    bar.value = pct.toInt()
                    bar.string = "${String.format("%.1f", pct)}%"
                    return bar
                }
            }
        }

        val sorter = TableRowSorter(model)
        table.rowSorter = sorter

        searchField.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) {
                val q = searchField.text.trim()
                if (q.isBlank()) {
                    sorter.rowFilter = null
                } else {
                    sorter.rowFilter = javax.swing.RowFilter.regexFilter("(?i)$q")
                }
            }
        })

        // Double-click navigation
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val row = table.selectedRow
                    if (row >= 0) {
                        val modelRow = table.convertRowIndexToModel(row)
                        val className = model.getValueAt(modelRow, 2) as? String ?: ""
                        navigateToClass(className)
                    }
                }
            }
        })

        panel.add(JBScrollPane(table), BorderLayout.CENTER)
        return panel
    }

    private fun buildMemoryAllocationsTab(): JPanel {
        val panel = JPanel(BorderLayout(0, 6)).apply {
            border = JBUI.Borders.empty(8)
        }

        val searchField = SearchTextField().apply {
            textEditor.emptyText.text = "Filter classes..."
        }
        panel.add(searchField, BorderLayout.NORTH)

        val columnNames = arrayOf("#", "Class Name", "Allocations", "Total Size")
        val model = object : DefaultTableModel(columnNames, 0) {
            override fun isCellEditable(row: Int, column: Int): Boolean = false
        }

        for (m in snapshot.memoryAllocations) {
            val sizeStr = if (m.totalBytes >= 1024 * 1024) {
                "${String.format("%.2f", m.totalMb)} MB"
            } else {
                "${m.totalBytes / 1024} KB"
            }
            model.addRow(arrayOf<Any>(m.rank, m.className, m.allocationCount, sizeStr))
        }

        val table = JBTable(model).apply {
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            rowHeight = 24
            columnModel.getColumn(0).preferredWidth = 35
            columnModel.getColumn(1).preferredWidth = 480
            columnModel.getColumn(2).preferredWidth = 100
            columnModel.getColumn(3).preferredWidth = 110
        }

        val sorter = TableRowSorter(model)
        table.rowSorter = sorter

        searchField.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) {
                val q = searchField.text.trim()
                if (q.isBlank()) {
                    sorter.rowFilter = null
                } else {
                    sorter.rowFilter = javax.swing.RowFilter.regexFilter("(?i)$q")
                }
            }
        })

        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val row = table.selectedRow
                    if (row >= 0) {
                        val modelRow = table.convertRowIndexToModel(row)
                        val className = model.getValueAt(modelRow, 1) as? String ?: ""
                        navigateToClass(className)
                    }
                }
            }
        })

        panel.add(JBScrollPane(table), BorderLayout.CENTER)
        return panel
    }

    private fun buildSnapshotDetailsTab(): JPanel {
        val panel = JPanel(BorderLayout(0, 10)).apply {
            border = JBUI.Borders.empty(12)
        }

        val infoBox = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(JBLabel("Snapshot ID: ${snapshot.id}"))
            add(Box.createVerticalStrut(6))
            add(JBLabel("Target Process PID: ${snapshot.appPid}"))
            add(Box.createVerticalStrut(6))
            add(JBLabel("JFR File: ${snapshot.jfrFile?.absolutePath ?: "Not generated (Continuous Sampling used)"}"))
            add(Box.createVerticalStrut(6))
            add(JBLabel("File Size: ${snapshot.jfrFile?.length()?.let { "${it / 1024} KB" } ?: "N/A"}"))
            add(Box.createVerticalStrut(14))
        }

        val btnPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            if (snapshot.jfrFile != null && snapshot.jfrFile.exists()) {
                val openBtn = JButton("Open in IntelliJ Profiler").apply {
                    icon = AllIcons.Actions.Profile
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    addActionListener { openInIntelliJProfiler() }
                }
                add(openBtn)

                val exportBtn = JButton("Export JFR File...").apply {
                    icon = AllIcons.Actions.MenuSaveall
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    addActionListener { exportJfrFile() }
                }
                add(exportBtn)
            }
        }

        panel.add(infoBox, BorderLayout.NORTH)
        panel.add(btnPanel, BorderLayout.CENTER)
        return panel
    }

    private fun navigateToClass(rawClassName: String) {
        val cleanName = rawClassName.substringBefore("$").substringBefore("[")
        if (cleanName.isBlank() || cleanName.startsWith("java.") || cleanName.startsWith("jdk.")) return

        try {
            val psiClass = JavaPsiFacade.getInstance(project).findClass(cleanName, GlobalSearchScope.projectScope(project))
            if (psiClass != null && psiClass.canNavigate()) {
                psiClass.navigate(true)
                return
            }
        } catch (_: Throwable) {}

        // Fallback: search virtual files
        val simpleName = cleanName.substringAfterLast(".")
        val files = com.intellij.psi.search.FilenameIndex.getVirtualFilesByName(
            "$simpleName.java",
            GlobalSearchScope.projectScope(project)
        ).ifEmpty {
            com.intellij.psi.search.FilenameIndex.getVirtualFilesByName(
                "$simpleName.kt",
                GlobalSearchScope.projectScope(project)
            )
        }

        val target = files.firstOrNull() ?: return
        FileEditorManager.getInstance(project).openFile(target, true)
    }

    private fun openInIntelliJProfiler() {
        val jfr = snapshot.jfrFile ?: return
        if (!jfr.exists()) return

        val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(jfr) ?: return
        try {
            FileEditorManager.getInstance(project).openFile(vf, true)
        } catch (_: Throwable) {}
    }

    private fun exportJfrFile() {
        val jfr = snapshot.jfrFile ?: return
        val descriptor = FileSaverDescriptor("Export JFR Snapshot", "Choose destination for .jfr recording", "jfr")
        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        val target = dialog.save(null as com.intellij.openapi.vfs.VirtualFile?, "remote_profile_${snapshot.durationSeconds}s.jfr")
        if (target != null) {
            val destFile = target.file
            Files.copy(jfr.toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
