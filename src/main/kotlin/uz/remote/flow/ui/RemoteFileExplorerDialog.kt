package uz.remote.flow.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import uz.remote.flow.ssh.ServerProfile
import java.awt.Dimension
import javax.swing.Action
import javax.swing.JComponent

class RemoteFileExplorerDialog(
    project: Project,
    profile: ServerProfile
) : DialogWrapper(project, true) {

    private val explorerPanel = RemoteFileExplorerPanel(project)

    init {
        title = "Remote Files & Folders — ${profile.name} (${profile.host}:${profile.port})"
        init()
    }

    override fun createCenterPanel(): JComponent {
        explorerPanel.preferredSize = Dimension(850, 550)
        return explorerPanel
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)
}
