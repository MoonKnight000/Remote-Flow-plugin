package uz.remote.flow.reload

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import uz.remote.flow.logging.LogCategory
import uz.remote.flow.logging.RemoteFlowLogService
import uz.remote.flow.settings.RemoteFlowSettings
import uz.remote.flow.ssh.RemoteConnectionManager
import uz.remote.flow.sync.FastSyncManager
import java.io.File

object RemoteHotReloadManager {

    fun hotReloadCurrentFile(project: Project, specificFile: VirtualFile? = null) {
        val startTime = System.currentTimeMillis()
        val settings = RemoteFlowSettings.getInstance(project)
        val profile = settings.activeProfileOrNull ?: return
        val connMgr = RemoteConnectionManager.getInstance(project)
        val logService = RemoteFlowLogService.getInstance(project)

        if (!connMgr.isConnected) {
            connMgr.notifyUser("Remote Flow: Hot Reload", "Server is not connected. Please connect first.", NotificationType.WARNING)
            return
        }

        // Save all modified documents first
        FileDocumentManager.getInstance().saveAllDocuments()

        val targetFile = specificFile ?: FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
        if (targetFile == null || project.basePath == null) {
            connMgr.notifyUser("Remote Flow: Hot Reload", "No active file selected for hot reload.", NotificationType.WARNING)
            return
        }

        val basePath = project.basePath!!
        val filePath = targetFile.path
        val isSourceCode = targetFile.extension?.lowercase() in setOf("java", "kt", "groovy", "scala")

        logService.log("[HOT RELOAD] ⚡ Initiating hot reload for '${targetFile.name}' on ${profile.name}...\n", LogCategory.RUN, profile.name)

        if (isSourceCode) {
            // Trigger compile via IntelliJ native compile action
            ApplicationManager.getApplication().invokeLater {
                try {
                    val actionManager = ActionManager.getInstance()
                    val compileAction = actionManager.getAction("Compile")
                        ?: actionManager.getAction("CompileDirty")
                        ?: actionManager.getAction("Make")

                    if (compileAction != null) {
                        val dataContext = DataContext { dataId ->
                            when (dataId) {
                                CommonDataKeys.PROJECT.name -> project
                                CommonDataKeys.VIRTUAL_FILE.name -> targetFile
                                else -> null
                            }
                        }
                        val event = AnActionEvent.createFromAnAction(compileAction, null, "RemoteFlowHotReload", dataContext)
                        compileAction.actionPerformed(event)
                    }
                } catch (_: Throwable) {}

                // Execute synchronization after quick compilation delay
                ApplicationManager.getApplication().executeOnPooledThread {
                    try {
                        Thread.sleep(300)
                    } catch (_: InterruptedException) {}
                    syncCompiledClassesAndTrigger(project, profile, targetFile, startTime)
                }
            }
        } else {
            // For templates, static resources, properties, yml: fast sync directly
            val relPath = filePath.removePrefix(basePath).trimStart('/', '\\')
            val syncMgr = FastSyncManager(project)
            syncMgr.syncSpecificPath(
                profile = profile,
                relativePath = relPath,
                isAutoSync = false,
                onLog = { logService.log(it, LogCategory.SYNC, profile.name) },
                onComplete = { success ->
                    val elapsed = System.currentTimeMillis() - startTime
                    if (success) {
                        touchRemoteDevTools(project, profile)
                        val msg = "Resource '${targetFile.name}' synced and reloaded in ${elapsed}ms!"
                        logService.log("[HOT RELOAD SUCCESS] ⚡ $msg\n", LogCategory.RUN, profile.name)
                        connMgr.notifyUser("Remote Flow: Hot Reload", msg, NotificationType.INFORMATION)
                    } else {
                        connMgr.notifyUser("Remote Flow: Hot Reload Warning", "Failed to sync resource.", NotificationType.WARNING)
                    }
                }
            )
        }
    }

    private fun syncCompiledClassesAndTrigger(
        project: Project,
        profile: uz.remote.flow.ssh.ServerProfile,
        sourceFile: VirtualFile,
        startTime: Long
    ) {
        val connMgr = RemoteConnectionManager.getInstance(project)
        val logService = RemoteFlowLogService.getInstance(project)
        val basePath = project.basePath ?: return

        // 1. Sync the modified source file
        val relPath = sourceFile.path.removePrefix(basePath).trimStart('/', '\\')
        val syncMgr = FastSyncManager(project)

        syncMgr.syncSpecificPath(
            profile = profile,
            relativePath = relPath,
            isAutoSync = false,
            onLog = { logService.log(it, LogCategory.SYNC, profile.name) },
            onComplete = { _ ->
                // 2. Look for compiled class files
                findAndSyncClassFiles(project, profile, sourceFile)

                // 3. Trigger debugger hotswap if attached
                triggerDebuggerHotSwap(project)

                // 4. Touch remote devtools trigger
                touchRemoteDevTools(project, profile)

                val elapsed = System.currentTimeMillis() - startTime
                val msg = "Class '${sourceFile.nameWithoutExtension}' hot-reloaded on ${profile.name} in ${elapsed}ms!"
                logService.log("[HOT RELOAD READY] ⚡ $msg\n", LogCategory.RUN, profile.name)
                connMgr.notifyUser("Remote Flow: Hot Reload", msg, NotificationType.INFORMATION)
            }
        )
    }

    private fun findAndSyncClassFiles(
        project: Project,
        profile: uz.remote.flow.ssh.ServerProfile,
        sourceFile: VirtualFile
    ) {
        val basePath = project.basePath ?: return
        val baseDir = File(basePath)
        val className = sourceFile.nameWithoutExtension

        // Search build/classes or target/classes for matching class files
        val candidateDirs = listOf(
            File(baseDir, "build/classes"),
            File(baseDir, "target/classes"),
            File(baseDir, "out/production")
        )

        val classFiles = mutableListOf<File>()
        for (dir in candidateDirs) {
            if (dir.exists() && dir.isDirectory) {
                dir.walkTopDown().filter { it.isFile && it.name.startsWith(className) && it.extension == "class" }.forEach {
                    classFiles.add(it)
                }
            }
        }

        val syncMgr = FastSyncManager(project)
        for (cls in classFiles) {
            val rel = cls.relativeTo(baseDir).path.replace('\\', '/')
            syncMgr.syncSpecificPath(
                profile = profile,
                relativePath = rel,
                isAutoSync = false,
                onLog = {},
                onComplete = {}
            )
        }
    }

    private fun triggerDebuggerHotSwap(project: Project) {
        ApplicationManager.getApplication().invokeLater {
            try {
                // Try IntelliJ native Hotswap action
                val actionManager = ActionManager.getInstance()
                val hotswapAction = actionManager.getAction("Hotswap")
                    ?: actionManager.getAction("Debugger.ReloadFile")
                    ?: actionManager.getAction("CompileDirty")
                if (hotswapAction != null) {
                    val dataContext = DataContext { dataId ->
                        if (dataId == CommonDataKeys.PROJECT.name) project else null
                    }
                    val event = AnActionEvent.createFromAnAction(hotswapAction, null, "RemoteFlowHotReload", dataContext)
                    hotswapAction.actionPerformed(event)
                }
            } catch (_: Throwable) {}
        }
    }

    private fun touchRemoteDevTools(project: Project, profile: uz.remote.flow.ssh.ServerProfile) {
        val connMgr = RemoteConnectionManager.getInstance(project)
        val remoteDir = profile.remoteProjectPath.trimEnd('/')
        // Touch classpath / trigger files so Spring Boot DevTools or Quarkus / Micronaut detects change
        val touchCmd = "cd '$remoteDir' 2>/dev/null && (touch .reload build/classes/java/main/.reload target/classes/.reload 2>/dev/null || true)"
        connMgr.executeRemoteCommand(touchCmd, remoteDir, false, {}, {})
    }
}
