package uz.remote.flow.diff

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import uz.remote.flow.files.RemoteFileManager
import uz.remote.flow.logging.LogCategory
import uz.remote.flow.logging.RemoteFlowLogService
import uz.remote.flow.settings.RemoteFlowSettings
import uz.remote.flow.ssh.RemoteConnectionManager
import uz.remote.flow.ssh.RemoteSafetyHelper
import uz.remote.flow.ssh.ServerProfile
import uz.remote.flow.sync.FastSyncManager
import uz.remote.flow.sync.RemoteSyncPreviewDialog
import java.io.File

@Service(Service.Level.PROJECT)
class RemoteDiffManager(private val project: Project) {

    private val connectionManager get() = RemoteConnectionManager.getInstance(project)
    private val settings get() = RemoteFlowSettings.getInstance(project)
    private val fileManager = RemoteFileManager(project)
    private val syncManager = FastSyncManager(project)
    private val logService get() = RemoteFlowLogService.getInstance(project)

    fun compareWithRemote(
        virtualFile: VirtualFile,
        profile: ServerProfile = settings.activeProfile
    ) {
        if (virtualFile.isDirectory) {
            compareDirectoryWithRemote(virtualFile, profile)
        } else {
            compareFileWithRemote(virtualFile, profile)
        }
    }

    fun compareFileWithRemote(
        virtualFile: VirtualFile,
        profile: ServerProfile = settings.activeProfile
    ) {
        val basePath = project.basePath ?: return
        val relPath = virtualFile.path.removePrefix(basePath).trimStart('/', '\\').replace('\\', '/')
        val remotePath = "${profile.remoteProjectPath.trimEnd('/')}/$relPath"

        connectionManager.ensureConnected(profile) { connected, errMsg ->
            if (!connected) {
                connectionManager.notifyUser(
                    "Remote Flow: Connection Failed",
                    "Could not connect to ${profile.name}: $errMsg",
                    NotificationType.ERROR
                )
                return@ensureConnected
            }

            ProgressManager.getInstance().run(object : Task.Backgroundable(
                project,
                "Remote Flow: Fetching ${virtualFile.name} from ${profile.name}...",
                true
            ) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.text = "Reading remote file: $remotePath"

                    fileManager.readFileContent(profile, remotePath) { remoteContent, err ->
                        ApplicationManager.getApplication().invokeLater {
                            if (project.isDisposed) return@invokeLater

                            if (remoteContent == null || err != null) {
                                val choice = Messages.showYesNoDialog(
                                    project,
                                    "File not found on remote server:\n  $remotePath\n\n" +
                                        "Server: ${profile.name} (${profile.host})\n\n" +
                                        "This file has not been uploaded to the remote server yet. Would you like to upload it now?",
                                    "Remote Flow - File Not Found on Server",
                                    "Upload to Server",
                                    "Cancel",
                                    Messages.getQuestionIcon()
                                )

                                if (choice == Messages.YES) {
                                    connectionManager.notifyUser("Remote Flow: Sync", "Uploading '${virtualFile.name}' to ${profile.name}...")
                                    syncManager.syncSpecificPath(profile, relPath) { success ->
                                        if (success) {
                                            connectionManager.notifyUser("Remote Flow: Sync Ready", "File uploaded to server: ${virtualFile.name}", NotificationType.INFORMATION)
                                        }
                                    }
                                }
                                return@invokeLater
                            }

                            try {
                                val diffContentFactory = DiffContentFactory.getInstance()
                                val localDiffContent = diffContentFactory.create(project, virtualFile)
                                val remoteDiffContent = diffContentFactory.create(project, remoteContent, virtualFile.fileType)

                                val request = SimpleDiffRequest(
                                    "Remote Flow Diff: ${virtualFile.name} [Local vs ${profile.name}]",
                                    localDiffContent,
                                    remoteDiffContent,
                                    "Local: ${virtualFile.name}",
                                    "Remote (${profile.name}): $remotePath"
                                )

                                DiffManager.getInstance().showDiff(project, request)
                                logService.log("[DIFF] Opened visual diff viewer for $relPath against ${profile.name}.", LogCategory.RUN, profile.name)
                            } catch (ex: Exception) {
                                Messages.showErrorDialog(project, "Failed to open diff viewer: ${ex.message}", "Diff Error")
                            }
                        }
                    }
                }
            })
        }
    }

    fun compareDirectoryWithRemote(
        folderVirtualFile: VirtualFile,
        profile: ServerProfile = settings.activeProfile
    ) {
        val basePath = project.basePath ?: return
        val relFolder = folderVirtualFile.path.removePrefix(basePath).trimStart('/', '\\').replace('\\', '/')
        val folderLabel = if (relFolder.isBlank()) "/" else "/$relFolder"

        connectionManager.notifyUser(
            "Remote Flow: Directory Diff",
            "Analyzing differences for $folderLabel with server ${profile.name}...",
            NotificationType.INFORMATION
        )
        logService.log("[DIRECTORY DIFF] Analyzing differences for $folderLabel against ${profile.name}...\n", LogCategory.SYNC, profile.name)

        syncManager.previewDryRunDiff(
            profile = profile,
            onLog = { logService.log(it, LogCategory.SYNC, profile.name) },
            onResult = { allDiffItems ->
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater

                    val scopedItems = if (relFolder.isBlank()) {
                        allDiffItems
                    } else {
                        allDiffItems.filter { it.relativePath.startsWith("$relFolder/") || it.relativePath == relFolder }
                    }

                    if (scopedItems.isEmpty()) {
                        Messages.showInfoMessage(
                            project,
                            "No differences found between local directory and remote server!\n\n" +
                                "Directory: $folderLabel\nServer: ${profile.name} (${profile.host})\n\n" +
                                "All files are up to date and identical.",
                            "Remote Flow - No Differences Found"
                        )
                        return@invokeLater
                    }

                    val dlg = RemoteSyncPreviewDialog(project, profile, scopedItems) {
                        if (!RemoteSafetyHelper.checkProductionSafe(project, profile, "Sync Scoped Directory")) {
                            return@RemoteSyncPreviewDialog
                        }

                        connectionManager.notifyUser("Remote Flow: Sync", "Uploading $folderLabel to ${profile.name}...")
                        if (relFolder.isBlank()) {
                            syncManager.syncSingleServer(
                                profile = profile,
                                onLog = { logService.log(it, LogCategory.SYNC, profile.name) },
                                onComplete = { success ->
                                    val type = if (success) NotificationType.INFORMATION else NotificationType.WARNING
                                    connectionManager.notifyUser("Remote Flow: Sync", if (success) "Project files synchronized!" else "Sync completed with warnings.", type)
                                }
                            )
                        } else {
                            syncManager.syncSpecificPath(profile, relFolder) { success ->
                                val type = if (success) NotificationType.INFORMATION else NotificationType.WARNING
                                connectionManager.notifyUser("Remote Flow: Sync", if (success) "Folder '$relFolder' synchronized!" else "Sync failed. Check logs.", type)
                            }
                        }
                    }
                    dlg.title = "Directory Diff Preview: $folderLabel vs ${profile.name}"
                    dlg.show()
                }
            }
        )
    }

    companion object {
        fun getInstance(project: Project): RemoteDiffManager =
            project.getService(RemoteDiffManager::class.java)
    }
}
