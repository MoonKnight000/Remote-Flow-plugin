package uz.remote.flow.execution

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.openapi.ui.Messages
import uz.remote.flow.ssh.RemoteConnectionManager

/**
 * Intercepts project / IDE closing events.
 * If a remote application is actively running on the server, prompts the user to either:
 * 1. Stop on Server (terminates the remote process and port forwarding)
 * 2. Keep Running in Background (detaches cleanly, leaves process running on server)
 * 3. Cancel (aborts project closing, keeps IDE open)
 */
class RemoteFlowProjectCloseListener : ProjectManagerListener {

    @Deprecated("Overrides deprecated member in ProjectManagerListener")
    @Suppress("DEPRECATION")
    override fun canCloseProject(project: Project): Boolean {
        val connMgr = try {
            RemoteConnectionManager.getInstance(project)
        } catch (_: Exception) {
            return true
        }

        if (!connMgr.isProcessRunning) {
            return true
        }

        val profileName = connMgr.config.activeProfile.name
        val runningCmd = connMgr.runningCommand ?: "Application"

        val options = arrayOf(
            "Stop on Server",
            "Keep Running in Background",
            "Cancel"
        )

        val choice = Messages.showDialog(
            project,
            "Remote Flow: An application is currently running on remote server '$profileName'.\n\n" +
                "  Command: $runningCmd\n\n" +
                "IntelliJ IDEA or the project is closing. What would you like to do with this remote process?",
            "Remote Flow - Active Remote Process",
            options,
            0, // Default option: Stop on Server
            Messages.getQuestionIcon()
        )

        return when (choice) {
            0 -> {
                // Stop on Server: cleanly kill remote process before closing IDE
                connMgr.stopRemoteProcessSync(timeoutSeconds = 5)
                true
            }
            1 -> {
                // Keep Running in Background: detach cleanly, leave process running on Linux server
                connMgr.detachRemoteProcess()
                true
            }
            else -> {
                // Cancel: abort closing, keep IDE open
                false
            }
        }
    }
}
