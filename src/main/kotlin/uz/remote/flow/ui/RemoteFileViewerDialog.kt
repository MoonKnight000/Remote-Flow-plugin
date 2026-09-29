package uz.remote.flow.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import uz.remote.flow.files.RemoteFileManager
import uz.remote.flow.ssh.ServerProfile
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.io.File
import javax.swing.*

class RemoteFileViewerDialog(
    private val project: Project,
    private val profile: ServerProfile,
    private val remotePath: String,
    initialContent: String
) : DialogWrapper(project, true) {

    private val fileManager = RemoteFileManager(project)
    private val textArea = JBTextArea(initialContent)
    private val statusLabel = JBLabel("Loaded: $remotePath")
    private val fileName = remotePath.substringAfterLast('/')

    private var originalContent: String = initialContent

    init {
        title = "Remote File: $fileName — ${profile.name} (${profile.host})"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(0, 6))
        root.preferredSize = Dimension(720, 520)
        root.border = JBUI.Borders.empty(8)

        // Top info bar
        val topBar = JPanel(BorderLayout(6, 0))
        val pathLabel = JBLabel("Path: $remotePath")
        pathLabel.font = pathLabel.font.deriveFont(Font.BOLD, 12f)
        topBar.add(pathLabel, BorderLayout.CENTER)

        val reloadBtn = JButton("⟳ Reload")
        reloadBtn.toolTipText = "Reload latest version from server"
        reloadBtn.addActionListener { reloadContent() }
        topBar.add(reloadBtn, BorderLayout.EAST)
        root.add(topBar, BorderLayout.NORTH)

        // Center Text Area
        textArea.font = Font("Monospaced", Font.PLAIN, 12)
        textArea.tabSize = 4
        textArea.lineWrap = false
        val scrollPane = JBScrollPane(textArea)
        root.add(scrollPane, BorderLayout.CENTER)

        // Bottom status & buttons
        val bottomBar = JPanel(BorderLayout(6, 0))
        statusLabel.font = statusLabel.font.deriveFont(Font.ITALIC, 11f)
        bottomBar.add(statusLabel, BorderLayout.WEST)

        val actionButtons = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0))

        val btnCopy = JButton("📋 Copy All")
        btnCopy.addActionListener {
            val sel = StringSelection(textArea.text)
            Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
            statusLabel.text = "Text copied to clipboard!"
        }
        actionButtons.add(btnCopy)

        val btnDownload = JButton("📥 Download")
        btnDownload.addActionListener { downloadToLocal() }
        actionButtons.add(btnDownload)

        val btnSave = JButton("💾 Save to Remote")
        btnSave.font = btnSave.font.deriveFont(Font.BOLD)
        btnSave.foreground = JBColor(Color(16, 185, 129), Color(16, 185, 129))
        btnSave.addActionListener { saveContentToRemote() }
        actionButtons.add(btnSave)

        bottomBar.add(actionButtons, BorderLayout.EAST)
        root.add(bottomBar, BorderLayout.SOUTH)

        return root
    }

    private fun reloadContent() {
        statusLabel.text = "Reloading from server..."
        fileManager.readFileContent(profile, remotePath) { content, err ->
            ApplicationManager.getApplication().invokeLater {
                if (content != null) {
                    originalContent = content
                    textArea.text = content
                    statusLabel.text = "Successfully reloaded: $remotePath"
                } else {
                    statusLabel.text = "Error: $err"
                }
            }
        }
    }

    private fun saveContentToRemote() {
        val newText = textArea.text
        statusLabel.text = "Saving to server..."
        fileManager.saveFileContent(profile, remotePath, newText) { success, err ->
            ApplicationManager.getApplication().invokeLater {
                if (success) {
                    originalContent = newText
                    statusLabel.text = "Successfully saved to server! ($remotePath)"
                    Messages.showInfoMessage(project, "File saved to server: $remotePath", "Saved")
                } else {
                    statusLabel.text = "Error saving: $err"
                    Messages.showErrorDialog(project, "Failed to save to server: $err", "Error")
                }
            }
        }
    }

    private fun downloadToLocal() {
        val descriptor = FileSaverDescriptor("Download Remote File", "Choose destination to save file", fileName.substringAfterLast('.'))
        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        val baseVirtualDir = project.basePath?.let { com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(it) }
        val target = dialog.save(baseVirtualDir, fileName)
        if (target != null) {
            val file = target.file
            fileManager.downloadFile(profile, remotePath, file) { success, err ->
                ApplicationManager.getApplication().invokeLater {
                    if (success) {
                        Messages.showInfoMessage(project, "File downloaded successfully:\n${file.absolutePath}", "Download Complete")
                    } else {
                        Messages.showErrorDialog(project, "Error downloading file: $err", "Error")
                    }
                }
            }
        }
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)
}
