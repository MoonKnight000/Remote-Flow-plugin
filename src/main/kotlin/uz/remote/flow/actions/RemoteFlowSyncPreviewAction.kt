package uz.remote.flow.actions

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import uz.remote.flow.settings.RemoteFlowSettings
import uz.remote.flow.ssh.RemoteConnectionManager
import uz.remote.flow.ssh.RemoteSafetyHelper
import uz.remote.flow.sync.FastSyncManager
import uz.remote.flow.sync.RemoteSyncPreviewDialog

class RemoteFlowSyncPreviewAction : AnAction(
    "Preview Sync Diff (Dry Run)...",
    "Preview file changes and differences before uploading to remote server",
    AllIcons.Actions.Diff
) {

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null || project.isDisposed) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val settings = RemoteFlowSettings.getInstance(project)
        val p = settings.activeProfileOrNull
        e.presentation.isEnabledAndVisible = p != null && p.host.isNotBlank()
        if (p != null) {
            e.presentation.text = "Preview Sync Diff (${p.name})..."
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        openPreviewDialog(project)
    }

    companion object {
        fun openPreviewDialog(project: Project) {
            val settings = RemoteFlowSettings.getInstance(project)
            val profile = settings.activeProfileOrNull ?: return
            val connMgr = RemoteConnectionManager.getInstance(project)
            val syncMgr = FastSyncManager(project)
            val logService = uz.remote.flow.logging.RemoteFlowLogService.getInstance(project)

            connMgr.notifyUser("Remote Flow: Sync Preview", "Analyzing diff with server ${profile.name} (Dry Run)...", NotificationType.INFORMATION)
            logService.log("[SYNC PREVIEW] Running itemized dry-run comparison with ${profile.name}...\n", uz.remote.flow.logging.LogCategory.SYNC, profile.name)

            syncMgr.previewDryRunDiff(
                profile = profile,
                onLog = { logService.log(it, uz.remote.flow.logging.LogCategory.SYNC, profile.name) },
                onResult = { diffItems ->
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed) return@invokeLater
                        val dlg = RemoteSyncPreviewDialog(project, profile, diffItems) {
                            if (!RemoteSafetyHelper.checkProductionSafe(project, profile, "Sync Project Files")) {
                                return@RemoteSyncPreviewDialog
                            }
                            connMgr.notifyUser("Remote Flow: Sync", "Uploading changes to ${profile.name}...")
                            syncMgr.syncSingleServer(
                                profile = profile,
                                onLog = { logService.log(it, uz.remote.flow.logging.LogCategory.SYNC, profile.name) },
                                onComplete = { success ->
                                    if (success) {
                                        connMgr.notifyUser("Remote Flow: Sync Ready", "Files synchronized successfully to ${profile.name}!", NotificationType.INFORMATION)
                                    } else {
                                        connMgr.notifyUser("Remote Flow: Sync Warning", "Sync completed with warnings. Check logs.", NotificationType.WARNING)
                                    }
                                }
                            )
                        }
                        dlg.show()
                    }
                }
            )
        }
    }
}
