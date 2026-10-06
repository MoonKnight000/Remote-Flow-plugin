package uz.remote.flow.sync

import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import uz.remote.flow.logging.LogCategory
import uz.remote.flow.logging.RemoteFlowLogService
import uz.remote.flow.settings.RemoteFlowSettings
import uz.remote.flow.ssh.RemoteConnectionManager
import uz.remote.flow.ssh.ServerProfile
import uz.remote.flow.ssh.isPathExcluded
import uz.remote.flow.ssh.parseExcludeList
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@Service(Service.Level.PROJECT)
class RemoteFlowAutoSyncService(private val project: Project) : FileDocumentManagerListener, BulkFileListener, Disposable {

    private val lastTypingTimestamp = AtomicLong(0L)
    private val isSyncRunning = AtomicBoolean(false)
    private val pendingDiskChanges = ConcurrentHashMap.newKeySet<String>()
    private val pendingDeletions = ConcurrentHashMap.newKeySet<String>()
    private val syncScheduler = Executors.newSingleThreadScheduledExecutor()
    private val syncLock = Any()
    private var debounceFuture: ScheduledFuture<*>? = null
    private var vfsRefreshTask: ScheduledFuture<*>? = null
    private val isDisposed = AtomicBoolean(false)
    private val isStarted = AtomicBoolean(false)

    fun start() {
        if (isStarted.compareAndSet(false, true)) {
            val bus = project.messageBus.connect(this)
            bus.subscribe(FileDocumentManagerListener.TOPIC, this)
            bus.subscribe(VirtualFileManager.VFS_CHANGES, this)

            // Listen to every typing keystroke inside IntelliJ IDEA editor
            EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    if (project.isDisposed || isDisposed.get()) return
                    val settings = RemoteFlowSettings.getInstance(project)
                    val profile = settings.activeProfileOrNull ?: return
                    if (!profile.autoSyncOnSave) return

                    val doc = event.document
                    val vFile = FileDocumentManager.getInstance().getFile(doc) ?: return
                    val filePath = vFile.path.replace('\\', '/')
                    val basePath = profile.localProjectPath.ifBlank { project.basePath ?: "" }
                    if (basePath.isBlank()) return
                    val cleanBase = basePath.replace('\\', '/').trimEnd('/')
                    if (!filePath.startsWith(cleanBase)) return

                    val relPath = filePath.removePrefix(cleanBase).trimStart('/')
                    if (relPath.isEmpty()) return

                    val fileName = relPath.substringAfterLast('/')
                    val excludes = parseExcludeList(profile.excludePatterns)
                    if (isIgnoredSystemPath(relPath, fileName) || isPathExcluded(relPath, fileName, excludes)) return

                    // Mark that the user is actively typing right now
                    lastTypingTimestamp.set(System.currentTimeMillis())

                    // Enqueue changed file and defer/reset debounce timer
                    synchronized(syncLock) {
                        pendingDiskChanges.add(relPath)
                    }
                    scheduleDebouncedBatch(profile)
                }
            }, this)

            // Periodic light background VFS refresh every 2.5s so external changes from AI tools
            // (Antigravity, Cursor, Claude Code, Copilot, etc.) are detected immediately even when
            // the IntelliJ IDEA window is unfocused or minimized.
            vfsRefreshTask = syncScheduler.scheduleWithFixedDelay({
                if (project.isDisposed || isDisposed.get()) return@scheduleWithFixedDelay
                val settings = RemoteFlowSettings.getInstance(project)
                val profile = settings.activeProfileOrNull ?: return@scheduleWithFixedDelay
                val connManager = RemoteConnectionManager.getInstance(project)
                if (profile.autoSyncOnSave && connManager.isConnected) {
                    try {
                        VirtualFileManager.getInstance().asyncRefresh()
                    } catch (_: Exception) {}
                }
            }, 2, 2, TimeUnit.SECONDS)
        }
    }

    override fun beforeDocumentSaving(document: Document) {
        if (project.isDisposed || isDisposed.get()) return
        val settings = RemoteFlowSettings.getInstance(project)
        val profile = settings.activeProfileOrNull ?: return
        val connManager = RemoteConnectionManager.getInstance(project)
        if (!connManager.isConnected) return

        val file = FileDocumentManager.getInstance().getFile(document) ?: return
        val filePath = file.path.replace('\\', '/')

        // Case 1: File was opened directly from Remote Explorer ("Open in IDE")
        val safeHost = profile.host.replace(":", "_").replace("/", "_")
        val cachePrefix = File(System.getProperty("java.io.tmpdir"), "remote-flow-cache/$safeHost").path.replace('\\', '/').trimEnd('/')
        if (filePath.startsWith(cachePrefix)) {
            val remotePath = "/" + filePath.removePrefix(cachePrefix).trimStart('/')
            val content = document.text
            val startTime = System.currentTimeMillis()
            ApplicationManager.getApplication().executeOnPooledThread {
                val logService = RemoteFlowLogService.getInstance(project)
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
                            category = LogCategory.FILES,
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
                            category = LogCategory.FILES,
                            serverName = profile.name,
                            isError = true
                        )
                    }
                }
            }
            return
        }

        // Case 2: Document saved locally in IntelliJ editor
        if (!profile.autoSyncOnSave) return
        val basePath = profile.localProjectPath.ifBlank { project.basePath ?: "" }
        if (basePath.isBlank()) return
        val cleanBase = basePath.replace('\\', '/').trimEnd('/')
        if (!filePath.startsWith(cleanBase)) return
        val relPath = filePath.removePrefix(cleanBase).trimStart('/')
        if (relPath.isEmpty()) return

        val excludes = parseExcludeList(profile.excludePatterns)
        if (isIgnoredSystemPath(relPath, file.name) || isPathExcluded(relPath, file.name, excludes)) return

        synchronized(syncLock) {
            pendingDiskChanges.add(relPath)
        }
        scheduleDebouncedBatch(profile)
    }

    override fun after(events: List<VFileEvent>) {
        if (project.isDisposed || isDisposed.get()) return
        val settings = RemoteFlowSettings.getInstance(project)
        val profile = settings.activeProfileOrNull ?: return
        if (!profile.autoSyncOnSave) return
        val connManager = RemoteConnectionManager.getInstance(project)
        if (!connManager.isConnected) return

        val basePath = profile.localProjectPath.ifBlank { project.basePath ?: "" }
        if (basePath.isBlank()) return
        val cleanBase = basePath.replace('\\', '/').trimEnd('/')
        val excludes = parseExcludeList(profile.excludePatterns)

        val newChanges = mutableSetOf<String>()
        val newDeletes = mutableSetOf<String>()

        for (event in events) {
            val rawPath = (event.file?.path ?: event.path).replace('\\', '/')
            if (!rawPath.startsWith(cleanBase)) continue
            val relPath = rawPath.removePrefix(cleanBase).trimStart('/')
            if (relPath.isEmpty()) continue

            val fileName = relPath.substringAfterLast('/')
            if (isIgnoredSystemPath(relPath, fileName) || isPathExcluded(relPath, fileName, excludes)) {
                continue
            }

            if (event is VFileDeleteEvent) {
                newDeletes.add(relPath)
            } else {
                newChanges.add(relPath)
            }
        }

        if (newChanges.isEmpty() && newDeletes.isEmpty()) return

        synchronized(syncLock) {
            pendingDiskChanges.addAll(newChanges)
            pendingDeletions.addAll(newDeletes)
        }
        scheduleDebouncedBatch(profile)
    }

    private fun scheduleDebouncedBatch(profile: ServerProfile) {
        val delayMs = profile.autoSyncDelayMs.coerceIn(500, 10000).toLong()
        synchronized(syncLock) {
            debounceFuture?.cancel(false)
            debounceFuture = syncScheduler.schedule({
                checkAndExecuteBatchSync(profile)
            }, delayMs, TimeUnit.MILLISECONDS)
        }
    }

    private fun checkAndExecuteBatchSync(profile: ServerProfile) {
        if (project.isDisposed || isDisposed.get()) return
        val delayMs = profile.autoSyncDelayMs.coerceIn(500, 10000).toLong()
        val now = System.currentTimeMillis()
        val timeSinceLastType = now - lastTypingTimestamp.get()

        // 💡 SMART TYPING-AWARE SILENCE CHECK:
        // If the user typed a character less than delayMs ago (e.g. they just typed "integ"),
        // DO NOT SYNC YET! Wait until they finish typing the whole word/statement!
        if (timeSinceLastType < delayMs) {
            val remaining = delayMs - timeSinceLastType
            synchronized(syncLock) {
                debounceFuture?.cancel(false)
                debounceFuture = syncScheduler.schedule({
                    checkAndExecuteBatchSync(profile)
                }, remaining.coerceAtLeast(200), TimeUnit.MILLISECONDS)
            }
            return
        }

        // Single-flight check: If a sync is already running, wait until it finishes
        if (!isSyncRunning.compareAndSet(false, true)) {
            synchronized(syncLock) {
                debounceFuture?.cancel(false)
                debounceFuture = syncScheduler.schedule({
                    checkAndExecuteBatchSync(profile)
                }, 500, TimeUnit.MILLISECONDS)
            }
            return
        }

        try {
            val filesToSync: Set<String>
            val filesToDelete: Set<String>
            synchronized(syncLock) {
                filesToSync = pendingDiskChanges.toSet()
                filesToDelete = pendingDeletions.toSet()
                pendingDiskChanges.clear()
                pendingDeletions.clear()
            }

            if (filesToSync.isEmpty() && filesToDelete.isEmpty()) {
                isSyncRunning.set(false)
                return
            }

            val connManager = RemoteConnectionManager.getInstance(project)
            if (!connManager.isConnected) {
                isSyncRunning.set(false)
                return
            }

            // Flush in-memory documents to disk so Rsync/SFTP transfers the complete typed text
            ApplicationManager.getApplication().invokeAndWait {
                try {
                    FileDocumentManager.getInstance().saveAllDocuments()
                } catch (_: Exception) {}
            }

            val syncManager = FastSyncManager(project)
            val logService = RemoteFlowLogService.getInstance(project)

            // 1. Process server deletions if any
            if (filesToDelete.isNotEmpty()) {
                for (delPath in filesToDelete) {
                    val remoteTarget = profile.remoteProjectPath.trimEnd('/') + "/" + delPath
                    connManager.executeRemoteCommand("rm -rf \"$remoteTarget\"", "", {}, { _ -> })
                    logService.log("[AUTO-SYNC] 🗑️ '$delPath' deleted from server\n", LogCategory.SYNC, profile.name)
                }
            }

            if (filesToSync.isEmpty()) {
                isSyncRunning.set(false)
                return
            }

            val count = filesToSync.size
            val sample = if (count <= 3) filesToSync.joinToString(", ") else "${filesToSync.take(2).joinToString(", ")} +${count - 2} more"

            logService.log(
                message = "[AUTO-SYNC -> ${profile.name}] Typing paused. Synchronizing $count changed file(s): $sample...\n",
                category = LogCategory.SYNC,
                serverName = profile.name
            )

            val startTime = System.currentTimeMillis()

            if (syncManager.checkRsync(profile)) {
                syncManager.syncSingleServer(
                    profile = profile,
                    onLog = { msg ->
                        logService.log(msg, LogCategory.SYNC, profile.name)
                    },
                    onComplete = { success ->
                        val duration = System.currentTimeMillis() - startTime
                        isSyncRunning.set(false)
                        if (success) {
                            for (filePath in filesToSync) {
                                logService.log(
                                    message = "[AUTO-SYNC] ⚡ '$filePath' uploaded to server (synced)\n",
                                    category = LogCategory.SYNC,
                                    serverName = profile.name
                                )
                            }
                            val summary = if (count == 1) "'${filesToSync.first()}' synced to server (${duration}ms)"
                                          else "$count files synced to server (${duration}ms)"
                            connManager.notifyUser(
                                title = "Real-Time Auto-Sync ⚡",
                                message = summary,
                                type = NotificationType.INFORMATION
                            )
                            logService.log(
                                message = "[AUTO-SYNC SUCCESS] ✅ $count file(s) successfully synced to server ${profile.name} (${duration}ms)\n",
                                category = LogCategory.SYNC,
                                serverName = profile.name
                            )
                        } else {
                            logService.log("[AUTO-SYNC ERROR] ❌ Rsync auto-sync failed.\n", LogCategory.SYNC, profile.name, isError = true)
                        }

                        // If user typed more while sync was in flight, schedule next batch
                        synchronized(syncLock) {
                            if (pendingDiskChanges.isNotEmpty() || pendingDeletions.isNotEmpty()) {
                                scheduleDebouncedBatch(profile)
                            }
                        }
                    }
                )
            } else {
                // SFTP fallback
                var remaining = filesToSync.size
                for (relPath in filesToSync) {
                    syncManager.syncSpecificPath(
                        profile = profile,
                        relativePath = relPath,
                        isAutoSync = true,
                        onLog = { msg -> logService.log(msg, LogCategory.SYNC, profile.name) },
                        onComplete = { fileSuccess ->
                            if (fileSuccess) {
                                logService.log(
                                    message = "[AUTO-SYNC] ⚡ '$relPath' uploaded to server (synced)\n",
                                    category = LogCategory.SYNC,
                                    serverName = profile.name
                                )
                            } else {
                                logService.log(
                                    message = "[AUTO-SYNC ERROR] ❌ Failed to upload '$relPath' to server!\n",
                                    category = LogCategory.SYNC,
                                    serverName = profile.name,
                                    isError = true
                                )
                            }
                            synchronized(syncLock) {
                                remaining--
                                if (remaining <= 0) {
                                    isSyncRunning.set(false)
                                    if (pendingDiskChanges.isNotEmpty() || pendingDeletions.isNotEmpty()) {
                                        scheduleDebouncedBatch(profile)
                                    }
                                }
                            }
                        }
                    )
                }
            }
        } catch (e: Exception) {
            isSyncRunning.set(false)
        }
    }

    private fun isIgnoredSystemPath(relPath: String, fileName: String): Boolean {
        return relPath.startsWith(".remote-flow") ||
                relPath.startsWith(".git/") || relPath == ".git" ||
                relPath.startsWith(".idea/") || relPath == ".idea" ||
                relPath.startsWith(".gradle/") || relPath == ".gradle" ||
                relPath.startsWith("build/") || relPath == "build" ||
                relPath.startsWith("target/") || relPath == "target" ||
                relPath.startsWith("out/") || relPath == "out" ||
                relPath.startsWith(".antigravity/") ||
                fileName.endsWith(".tmp") ||
                fileName.endsWith(".swp") ||
                fileName.endsWith("~") ||
                fileName.endsWith(".port") ||
                fileName.endsWith(".exitcode")
    }

    override fun dispose() {
        isDisposed.set(true)
        vfsRefreshTask?.cancel(true)
        debounceFuture?.cancel(true)
        syncScheduler.shutdownNow()
    }

    companion object {
        fun getInstance(project: Project): RemoteFlowAutoSyncService =
            project.getService(RemoteFlowAutoSyncService::class.java)
    }
}
