package uz.remote.flow.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import uz.remote.flow.ssh.RemoteConnectionManager
import uz.remote.flow.ssh.ServerProfile
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

class RemoteDirectoryChooserDialog(
    private val project: Project,
    private val initialPath: String,
    private val profile: ServerProfile
) : DialogWrapper(project, true) {

    private val connectionManager = RemoteConnectionManager.getInstance(project)

    private val pathField = JBTextField(if (initialPath.isBlank()) "/opt" else initialPath)
    private val dirListModel = DefaultListModel<String>()
    private val dirList = JBList(dirListModel)
    private val statusLabel = JLabel("Connecting to " + profile.host + "...")

    var selectedPath: String = initialPath
        private set

    init {
        title = "Remote Server Folder Browser — " + profile.name + " (" + profile.host + ")"
        init()
        loadDirectory(pathField.text.trim())
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(0, 8))
        root.preferredSize = Dimension(480, 360)
        root.border = JBUI.Borders.empty(8)

        // Top Toolbar
        val topPanel = JPanel(BorderLayout(6, 0))
        val btnUp = JButton("⬆ Up")
        btnUp.toolTipText = "Go to parent directory"
        btnUp.addActionListener { navigateUp() }

        val btnGo = JButton("Go")
        btnGo.addActionListener { loadDirectory(pathField.text.trim()) }

        val btnNewFolder = JButton("➕ New Folder")
        btnNewFolder.addActionListener { createNewFolder() }

        val pathControls = JPanel(BorderLayout(4, 0))
        pathControls.add(pathField, BorderLayout.CENTER)

        val rightBtns = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))
        rightBtns.add(btnGo)
        rightBtns.add(btnNewFolder)
        pathControls.add(rightBtns, BorderLayout.EAST)

        topPanel.add(btnUp, BorderLayout.WEST)
        topPanel.add(pathControls, BorderLayout.CENTER)

        // Center List
        dirList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        dirList.font = Font("Monospaced", Font.PLAIN, 13)
        dirList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val selected = dirList.selectedValue
                    if (!selected.isNullOrBlank()) {
                        val current = pathField.text.trim().trimEnd('/')
                        val newPath = if (current.isEmpty()) "/" + selected else current + "/" + selected
                        loadDirectory(newPath)
                    }
                }
            }
        })

        // Bottom Status
        statusLabel.font = statusLabel.font.deriveFont(Font.ITALIC, 11f)

        root.add(topPanel, BorderLayout.NORTH)
        root.add(JBScrollPane(dirList), BorderLayout.CENTER)
        root.add(statusLabel, BorderLayout.SOUTH)

        return root
    }

    private fun navigateUp() {
        val current = pathField.text.trim().trimEnd('/')
        if (current.isEmpty() || current == "/") return
        val lastSlash = current.lastIndexOf('/')
        val parent = if (lastSlash <= 0) "/" else current.substring(0, lastSlash)
        loadDirectory(parent)
    }

    private fun createNewFolder() {
        val folderName = Messages.showInputDialog(
            project,
            "Yangi papka nomini kiriting:",
            "New Remote Folder",
            Messages.getQuestionIcon()
        ) ?: return

        val cleanName = folderName.trim()
        if (cleanName.isEmpty() || cleanName.contains('/') || cleanName.contains(' ')) {
            Messages.showErrorDialog(project, "Papka nomida bo'sh joy yoki / belgisi bo'lmasligi kerak!", "Xato")
            return
        }

        val current = pathField.text.trim().trimEnd('/')
        val fullPath = (if (current.isEmpty()) "" else current) + "/" + cleanName

        statusLabel.text = "Creating folder: " + fullPath + "..."
        connectionManager.executeRemoteCommand(
            cmd = "mkdir -p \"" + fullPath + "\"",
            workingDir = "",
            onOutput = {},
            onComplete = { code ->
                ApplicationManager.getApplication().invokeLater {
                    if (code == 0) {
                        loadDirectory(fullPath)
                    } else {
                        Messages.showErrorDialog(project, "Papkani yaratib bo'lmadi! Huquqlar (permissions)ni tekshiring.", "Xato")
                    }
                }
            }
        )
    }

    private fun loadDirectory(targetPath: String) {
        val cleanPath = if (targetPath.isBlank()) "/" else targetPath
        pathField.text = cleanPath
        dirListModel.clear()
        statusLabel.text = "Loading folders from " + cleanPath + "..."

        val cmd = "find \"" + cleanPath + "\" -maxdepth 1 -mindepth 1 -type d -printf '%f\\n' 2>/dev/null || ls -d \"" + cleanPath + "\"/*/ 2>/dev/null"

        val dirs = mutableListOf<String>()
        connectionManager.executeRemoteCommand(
            cmd = cmd,
            workingDir = "",
            onOutput = { line ->
                val trimmed = line.trim().trimEnd('/').substringAfterLast('/')
                if (trimmed.isNotBlank() && !dirs.contains(trimmed)) {
                    dirs.add(trimmed)
                }
            },
            onComplete = { code ->
                ApplicationManager.getApplication().invokeLater {
                    if (code == 0 || dirs.isNotEmpty()) {
                        dirs.sort()
                        dirs.forEach { dirListModel.addElement(it) }
                        statusLabel.text = "Directories found: " + dirs.size + " (Double click to enter)"
                    } else {
                        statusLabel.text = "Folder bo'sh yoki o'qish huquqi yo'q (permission denied)."
                    }
                }
            }
        )
    }

    override fun doOKAction() {
        val selectedSub = dirList.selectedValue
        val current = pathField.text.trim()
        selectedPath = if (selectedSub != null && dirList.isSelectionEmpty.not()) {
            val base = current.trimEnd('/')
            if (base.isEmpty()) "/" + selectedSub else base + "/" + selectedSub
        } else {
            current
        }
        super.doOKAction()
    }
}
