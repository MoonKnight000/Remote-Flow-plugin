package uz.remote.flow.sync

import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.project.Project
import uz.remote.flow.settings.RemoteFlowSettings
import uz.remote.flow.ssh.RemoteConnectionManager
import uz.remote.flow.ssh.isPathExcluded
import uz.remote.flow.ssh.parseExcludeList
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class RemoteFlowAutoSyncService(private val project: Project) : FileDocumentManagerListener {

    private val debounceMap = ConcurrentHashMap<String, Long>()

    override fun beforeDocumentSaving(document: Document) {
        if (project.isDisposed) return
        val settings = RemoteFlowSettings.getInstance(project)
        val profile = settings.activeProfileOrNull ?: return
        val connManager = RemoteConnectionManager.getInstance(project)
        if (!connManager.isConnected) return

        val file = FileDocumentManager.getInstance().getFile(document) ?: return
        val filePath = file.path.replace('\\', '/')

        // Case 1: File was opened directly from Remote Explorer ("Open in IDE")
        val safeHost = profile.host.replace(":", "_").replace("/", "_")
        val cachePrefix = java.io.File(System.getProperty("java.io.tmpdir"), "remote-flow-cache/$safeHost").path.replace('\\', '/').trimEnd('/')
        if (filePath.startsWith(cachePrefix)) {
            val remotePath = "/" + filePath.removePrefix(cachePrefix).trimStart('/')
            val now = System.currentTimeMillis()
            val lastSync = debounceMap[remotePath] ?: 0L
            if (now - lastSync < 600) return
            debounceMap[remotePath] = now

            val content = document.text
            val startTime = System.currentTimeMillis()
            ApplicationManager.getApplication().executeOnPooledThread {
                val logService = uz.remote.flow.logging.RemoteFlowLogService.getInstance(project)
                uz.remote.flow.files.RemoteFileManager(project).saveFileContent(profile, remotePath, content) { ok, err ->
                    val duration = System.currentTimeMillis() - startTime
                    if (ok) {
                        connManager.notifyUser(
                            title = "Remote Flow: Saqlandi ⚡",
                            message = "'${file.name}' serverda yangilandi: $remotePath",
                            type = NotificationType.INFORMATION
                        )
                        logService.log(
                            message = "[REMOTE SAVE SUCCESS] ✅ '${file.name}' saved to remote server: $remotePath (${duration}ms)\n",
                            category = uz.remote.flow.logging.LogCategory.FILES,
                            serverName = profile.name
                        )
                    } else {
                        connManager.notifyUser(
                            title = "Remote Flow: Error",
                            message = "Failed to save '${file.name}' to server: $err",
                            type = NotificationType.ERROR
                        )
                        logService.log(
                            message = "[REMOTE SAVE ERROR] ❌ Failed to save '${file.name}' to server: $err\n",
                            category = uz.remote.flow.logging.LogCategory.FILES,
                            serverName = profile.name,
                            isError = true
                        )
                    }
                }
            }
            return
        }

        if (!profile.autoSyncOnSave) return

        val basePath = profile.localProjectPath.ifBlank { project.basePath ?: "" }
        if (basePath.isBlank()) return

        val cleanBase = basePath.replace('\\', '/').trimEnd('/')
        if (!filePath.startsWith(cleanBase)) return

        val relPath = filePath.removePrefix(cleanBase).trimStart('/')
        if (relPath.isEmpty()) return

        val excludes = parseExcludeList(profile.excludePatterns)
        if (isPathExcluded(relPath, file.name, excludes)) return

        val now = System.currentTimeMillis()
        val lastSync = debounceMap[relPath] ?: 0L
        if (now - lastSync < 500) return
        debounceMap[relPath] = now

        ApplicationManager.getApplication().executeOnPooledThread {
            val syncManager = FastSyncManager(project)
            syncManager.syncSpecificPath(
                profile = profile,
                relativePath = relPath,
                isAutoSync = true,
                onLog = { line ->
                    uz.remote.flow.logging.RemoteFlowLogService.getInstance(project).log(
                        message = line,
                        category = uz.remote.flow.logging.LogCategory.SYNC,
                        serverName = profile.name
                    )
                },
                onComplete = { success ->
                    if (success) {
                        connManager.notifyUser(
                            title = "Auto-Sync ⚡",
                            message = "'$relPath' automatically uploaded to server!",
                            type = NotificationType.INFORMATION
                        )
                    } else {
                        connManager.notifyUser(
                            title = "Auto-Sync ❌",
                            message = "Failed to upload '$relPath' to server!",
                            type = NotificationType.ERROR
                        )
                    }
                }
            )
        }
    }
}
