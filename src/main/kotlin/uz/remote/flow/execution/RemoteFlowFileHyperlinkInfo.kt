package uz.remote.flow.execution

import com.intellij.execution.filters.FileHyperlinkInfo
import com.intellij.notification.NotificationType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import uz.remote.flow.ssh.RemoteConnectionManager

/**
 * Hyperlink navigation handler for remote files.
 * Directly opens local project files in the editor at the specified line and column.
 * If the file is not found locally, alerts the user to pull it from the server rather than redirecting to a browser.
 */
class RemoteFlowFileHyperlinkInfo(
    private val project: Project,
    private val rawPath: String,
    private val initialFile: VirtualFile?,
    private val line: Int = 0,
    private val column: Int = 0,
    private val hintServerName: String? = null
) : FileHyperlinkInfo {

    override fun getDescriptor(): OpenFileDescriptor? {
        val vf = resolveFile() ?: return null
        val docLine = if (line > 0) line - 1 else 0
        val docCol = if (column > 0) column - 1 else 0
        return OpenFileDescriptor(project, vf, docLine, docCol)
    }

    override fun navigate(project: Project) {
        val desc = getDescriptor()
        if (desc != null && desc.file.isValid) {
            FileEditorManager.getInstance(project).openEditor(desc, true)
            return
        }

        RemoteConnectionManager.getInstance(project).notifyUser(
            "Remote Flow: File Not Found Locally",
            "File '$rawPath' was not found in your local project workspace. Use 'Pull Changes from Remote Server' to download it.",
            NotificationType.WARNING
        )
    }

    private fun resolveFile(): VirtualFile? {
        if (initialFile != null && initialFile.isValid) {
            return initialFile
        }
        return RemoteFlowPathResolver.resolve(project, rawPath, hintServerName)
    }
}
