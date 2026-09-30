package uz.remote.flow.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import uz.remote.flow.reload.RemoteHotReloadManager
import uz.remote.flow.settings.RemoteFlowSettings
import uz.remote.flow.ssh.RemoteConnectionManager

class RemoteFlowHotReloadAction : AnAction(
    "Remote Hot Reload / Fast Recompile",
    "Fast recompile and reload current class on remote server without full application restart",
    AllIcons.Actions.Compile
) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        if (project == null || project.isDisposed || file == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        val settings = RemoteFlowSettings.getInstance(project)
        val p = settings.activeProfileOrNull
        val connMgr = RemoteConnectionManager.getInstance(project)

        e.presentation.isEnabledAndVisible = p != null && connMgr.isConnected
        if (p != null) {
            e.presentation.text = "⚡ Hot Reload '${file.name}' (${p.name})"
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        RemoteHotReloadManager.hotReloadCurrentFile(project, file)
    }
}
