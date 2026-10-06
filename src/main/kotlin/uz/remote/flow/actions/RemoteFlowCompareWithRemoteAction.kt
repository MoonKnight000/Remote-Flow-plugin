package uz.remote.flow.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import uz.remote.flow.diff.RemoteDiffManager
import uz.remote.flow.settings.RemoteFlowSettings

/**
 * Action triggered on a file or folder (via Project View, Editor, Editor Tab, or Tools Menu)
 * to compare the local version against the active remote server in IntelliJ IDEA's native visual Diff viewer.
 */
class RemoteFlowCompareWithRemoteAction : AnAction(
    "Compare with Remote (Diff)",
    "Compare selected file or folder with active remote server version",
    AllIcons.Actions.Diff
) {

    override fun update(e: AnActionEvent) {
        val project = e.project
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
            ?: e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.firstOrNull()

        if (project == null || project.isDisposed || file == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        val basePath = project.basePath
        if (basePath == null || !file.path.startsWith(basePath)) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        val settings = RemoteFlowSettings.getInstance(project)
        val profile = settings.activeProfileOrNull
        if (profile == null || profile.host.isBlank()) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        e.presentation.isEnabledAndVisible = true
        e.presentation.icon = AllIcons.Actions.Diff

        val relPath = file.path.removePrefix(basePath).trimStart('/', '\\')
        if (file.isDirectory) {
            val label = if (relPath.isBlank()) "Project" else file.name
            e.presentation.text = "Compare Directory with Remote ($label)..."
            e.presentation.description = "Compare directory '$label' with server ${profile.name} and review changed files"
        } else {
            e.presentation.text = "Compare File with Remote (${file.name})"
            e.presentation.description = "Compare '${file.name}' with server ${profile.name} in visual side-by-side diff viewer"
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
            ?: e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.firstOrNull()
            ?: return

        RemoteDiffManager.getInstance(project).compareWithRemote(file)
    }
}
