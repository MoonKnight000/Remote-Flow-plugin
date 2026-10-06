package uz.remote.flow.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.io.File
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.table.DefaultTableModel

/**
 * Dialog to configure environment variables for remote execution,
 * with support for 1-click import from local .env files.
 */
class RemoteEnvVarsDialog(
    private val project: Project,
    private val envMap: MutableMap<String, String>
) : DialogWrapper(project, true) {

    private val tableModel = DefaultTableModel(arrayOf("Variable Name (KEY)", "Value"), 0)
    private val table = JBTable(tableModel)

    init {
        title = "Remote Environment Variables & Secrets Vault"
        setOKButtonText("Save Variables")

        envMap.forEach { (k, v) ->
            tableModel.addRow(arrayOf(k, v))
        }

        init()
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(0, 8))
        root.preferredSize = Dimension(520, 360)
        root.border = JBUI.Borders.empty(8)

        val decoratedTable = ToolbarDecorator.createDecorator(table)
            .setAddAction {
                tableModel.addRow(arrayOf("NEW_KEY", "value"))
                val newRow = tableModel.rowCount - 1
                table.setRowSelectionInterval(newRow, newRow)
            }
            .setRemoveAction {
                val selected = table.selectedRow
                if (selected >= 0) {
                    tableModel.removeRow(selected)
                }
            }
            .disableUpDownActions()
            .createPanel()

        root.add(decoratedTable, BorderLayout.CENTER)

        val bottomBar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        val btnImportEnv = JButton("Import from .env File...", AllIcons.Actions.Download)
        btnImportEnv.toolTipText = "Load key-value pairs from a local .env file"
        btnImportEnv.addActionListener {
            val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor()
                .withTitle("Select .env File")
                .withDescription("Choose a local .env file to import environment variables")
            val chooser = FileChooserFactory.getInstance().createFileChooser(descriptor, project, null)
            val files = chooser.choose(project)
            if (files.isNotEmpty()) {
                val file = File(files[0].path)
                var imported = 0
                file.forEachLine { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotBlank() && !trimmed.startsWith("#")) {
                        val idx = trimmed.indexOf('=')
                        if (idx > 0) {
                            val k = trimmed.substring(0, idx).trim()
                            val v = trimmed.substring(idx + 1).trim().removeSurrounding("\"").removeSurrounding("'")
                            // Check if key already exists, replace or add
                            var exists = false
                            for (i in 0 until tableModel.rowCount) {
                                if (tableModel.getValueAt(i, 0) == k) {
                                    tableModel.setValueAt(v, i, 1)
                                    exists = true
                                    break
                                }
                            }
                            if (!exists) {
                                tableModel.addRow(arrayOf(k, v))
                            }
                            imported++
                        }
                    }
                }
                Messages.showInfoMessage(root, "Successfully imported $imported environment variables from ${file.name}.", "Import Successful")
            }
        }
        bottomBar.add(btnImportEnv)

        val btnClear = JButton("Clear All", AllIcons.Actions.Cancel)
        btnClear.addActionListener {
            if (tableModel.rowCount > 0 && Messages.showYesNoDialog(root, "Are you sure you want to clear all variables?", "Clear Variables", Messages.getQuestionIcon()) == Messages.YES) {
                while (tableModel.rowCount > 0) {
                    tableModel.removeRow(0)
                }
            }
        }
        bottomBar.add(btnClear)

        root.add(bottomBar, BorderLayout.SOUTH)
        return root
    }

    override fun doOKAction() {
        if (table.isEditing) {
            table.cellEditor?.stopCellEditing()
        }

        envMap.clear()
        for (i in 0 until tableModel.rowCount) {
            val k = (tableModel.getValueAt(i, 0) as? String)?.trim() ?: ""
            val v = (tableModel.getValueAt(i, 1) as? String)?.trim() ?: ""
            if (k.isNotBlank()) {
                envMap[k] = v
            }
        }

        super.doOKAction()
    }
}
