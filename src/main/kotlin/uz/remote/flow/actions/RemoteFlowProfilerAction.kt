package uz.remote.flow.actions

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import uz.remote.flow.profiler.ProfilerState
import uz.remote.flow.profiler.RemoteProfilerManager
import uz.remote.flow.ssh.RemoteConnectionManager
import uz.remote.flow.ui.RemoteProfilerResultsDialog

/**
 * Action to toggle performance recording or open the latest CPU & Heap allocation snapshot.
 * Default Shortcut: Alt + Shift + P
 */
class RemoteFlowProfilerAction : AnAction(
    "Remote Performance Profiler",
    "Toggle remote CPU and Memory performance profiling or view latest snapshot",
    AllIcons.Actions.Profile
) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val profiler = RemoteProfilerManager.getInstance(project)
        val connMgr = RemoteConnectionManager.getInstance(project)

        when (profiler.currentState) {
            ProfilerState.IDLE -> {
                if (connMgr.isProcessRunning) {
                    profiler.startRecording(profiler.selectedMode)
                } else if (profiler.lastSnapshot != null) {
                    RemoteProfilerResultsDialog(project, profiler.lastSnapshot!!).show()
                } else {
                    connMgr.notifyUser(
                        "Remote Profiler",
                        "No remote application is running and no previous snapshot is available. Run the app first via Remote Run (Alt+Shift+R).",
                        NotificationType.INFORMATION
                    )
                }
            }
            ProfilerState.RECORDING -> {
                profiler.stopRecording()
            }
            ProfilerState.ANALYZING -> {
                connMgr.notifyUser(
                    "Remote Profiler",
                    "Analyzing remote performance snapshot. Please wait...",
                    NotificationType.INFORMATION
                )
            }
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val profiler = RemoteProfilerManager.getInstance(project)
        val connMgr = RemoteConnectionManager.getInstance(project)

        e.presentation.isEnabledAndVisible = true
        when (profiler.currentState) {
            ProfilerState.RECORDING -> {
                e.presentation.text = "Stop Performance Recording"
                e.presentation.icon = AllIcons.Actions.Suspend
            }
            ProfilerState.ANALYZING -> {
                e.presentation.text = "Analyzing Snapshot..."
                e.presentation.icon = AllIcons.Process.ProgressPause
            }
            ProfilerState.IDLE -> {
                if (connMgr.isProcessRunning) {
                    e.presentation.text = "Start Performance Recording"
                    e.presentation.icon = AllIcons.Actions.Execute
                } else if (profiler.lastSnapshot != null) {
                    e.presentation.text = "View Performance Profile Results"
                    e.presentation.icon = AllIcons.Actions.Profile
                } else {
                    e.presentation.text = "Remote Performance Profiler"
                    e.presentation.icon = AllIcons.Actions.Profile
                }
            }
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
