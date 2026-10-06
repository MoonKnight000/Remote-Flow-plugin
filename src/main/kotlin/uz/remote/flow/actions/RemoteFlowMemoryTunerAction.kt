package uz.remote.flow.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import uz.remote.flow.memory.RemoteMemoryTunerDialog
import uz.remote.flow.settings.RemoteFlowSettings

/**
 * Action to open the Remote Memory & JVM Allocation Tuner Dialog.
 */
class RemoteFlowMemoryTunerAction : AnAction(
    "Remote Memory & JVM Allocation Tuner...",
    "Tune remote JVM heap (-Xmx/-Xms), Node.js memory, and Docker container limits",
    AllIcons.Actions.Profile
) {

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null || project.isDisposed) {
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
        e.presentation.text = "Tune Remote Memory (${profile.name})..."
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val profile = RemoteFlowSettings.getInstance(project).activeProfile
        RemoteMemoryTunerDialog(project, profile).show()
    }
}
