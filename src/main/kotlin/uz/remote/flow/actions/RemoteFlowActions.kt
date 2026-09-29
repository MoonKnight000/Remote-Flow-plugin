package uz.remote.flow.actions

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.wm.ToolWindowManager
import uz.remote.flow.settings.RemoteFlowConfigurable
import uz.remote.flow.settings.RemoteFlowSettings
import uz.remote.flow.ssh.RemoteConnectionManager
import uz.remote.flow.sync.FastSyncManager
import uz.remote.flow.ui.RemoteFlowIcons

class RemoteFlowConnectAction : AnAction("Connect / Disconnect Server", "Connect or disconnect active remote server", RemoteFlowIcons.REMOTE_FLOW) {
    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val connMgr = RemoteConnectionManager.getInstance(project)
        val settings = RemoteFlowSettings.getInstance(project)
        val p = settings.activeProfile
        if (connMgr.isConnected) {
            e.presentation.text = "Disconnect from ${p.name}"
            e.presentation.description = "Disconnect active SSH connection to ${p.host}"
        } else {
            e.presentation.text = "Connect to ${p.name}"
            e.presentation.description = "Connect to ${p.host}:${p.port}"
        }
        e.presentation.isEnabledAndVisible = true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val connMgr = RemoteConnectionManager.getInstance(project)
        val settings = RemoteFlowSettings.getInstance(project)
        val p = settings.activeProfile

        if (connMgr.isConnected) {
            connMgr.disconnect()
            connMgr.notifyUser("Remote Flow", "Disconnected from server: ${p.name}")
        } else {
            connMgr.connect(
                profile = p,
                onSuccess = {
                    connMgr.notifyUser("Remote Flow", "Successfully connected to: ${p.name}")
                },
                onError = { err ->
                    connMgr.notifyUser("Remote Flow: Connection Error", err.message ?: err.toString(), NotificationType.ERROR)
                }
            )
        }
    }
}

class RemoteFlowSyncAction : AnAction("Sync Project to Remote", "Upload project files to active server", RemoteFlowIcons.REMOTE_FLOW) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val syncMgr = FastSyncManager(project)
        val connMgr = RemoteConnectionManager.getInstance(project)
        val settings = RemoteFlowSettings.getInstance(project)
        val p = settings.activeProfile

        connMgr.notifyUser("Remote Flow: Sync", "Uploading project files to ${p.name}...")
        syncMgr.syncSingleServer(
            profile = p,
            onLog = {},
            onComplete = { success ->
                if (success) {
                    connMgr.notifyUser("Remote Flow: Sync Ready", "All files synchronized to server successfully!", NotificationType.INFORMATION)
                } else {
                    connMgr.notifyUser("Remote Flow: Sync Error", "Synchronization encountered errors. Check execution logs.", NotificationType.WARNING)
                }
            }
        )
    }
}

class RemoteFlowSyncSelectionAction : AnAction("Sync Selected File/Folder to Remote", "Upload selected file or directory to remote server", RemoteFlowIcons.REMOTE_FLOW) {
    override fun update(e: AnActionEvent) {
        val project = e.project
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        if (project == null || file == null || project.basePath == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        val basePath = project.basePath!!
        val filePath = file.path
        if (!filePath.startsWith(basePath)) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        val relPath = filePath.removePrefix(basePath).trimStart('/', '\\')
        e.presentation.isEnabledAndVisible = true
        e.presentation.text = "Sync '$relPath' to Remote"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val basePath = project.basePath ?: return
        val relPath = file.path.removePrefix(basePath).trimStart('/', '\\')

        val syncMgr = FastSyncManager(project)
        val connMgr = RemoteConnectionManager.getInstance(project)
        val settings = RemoteFlowSettings.getInstance(project)
        val p = settings.activeProfile
        val logService = uz.remote.flow.logging.RemoteFlowLogService.getInstance(project)

        connMgr.notifyUser("Remote Flow", "Uploading '$relPath' to ${p.name}...")
        syncMgr.syncSpecificPath(
            profile = p,
            relativePath = relPath,
            isAutoSync = false,
            onLog = { line ->
                logService.log(
                    message = line,
                    category = uz.remote.flow.logging.LogCategory.SYNC,
                    serverName = p.name
                )
            },
            onComplete = { success ->
                if (success) {
                    connMgr.notifyUser("Remote Flow", "'$relPath' uploaded successfully!", NotificationType.INFORMATION)
                } else {
                    connMgr.notifyUser("Remote Flow: Error", "Failed to upload '$relPath'.", NotificationType.WARNING)
                }
            }
        )
    }
}

class RemoteFlowRunAction : AnAction("Remote Run", "Sync and run application on remote server", RemoteFlowIcons.REMOTE_RUN) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null || project.isDisposed) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val settings = RemoteFlowSettings.getInstance(project)
        val p = settings.activeProfileOrNull
        e.presentation.isEnabledAndVisible = true
        if (p != null) {
            e.presentation.text = "Remote Run"
            e.presentation.description = "Remote Run on ${p.name} (${p.host})"
        } else {
            e.presentation.text = "Remote Run"
            e.presentation.description = "Sync, build and run on active remote server"
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val connMgr = RemoteConnectionManager.getInstance(project)
        val syncMgr = FastSyncManager(project)
        val settings = RemoteFlowSettings.getInstance(project)
        val p = settings.activeProfileOrNull
        if (p == null || p.host.isBlank()) {
            connMgr.notifyUser("Remote Flow", "No active server profile configured!", NotificationType.WARNING)
            return
        }

        val logService = uz.remote.flow.logging.RemoteFlowLogService.getInstance(project)
        logService.showLogWindow()

        val doRun = {
            val rawCmd = p.runCommand.ifBlank { "./gradlew bootRun" }
            val cmd = uz.remote.flow.ssh.buildRemoteExecutionCommand(rawCmd, p.javaHome)

            logService.log("[REMOTE RUN] 1. Syncing latest code to server ${p.name}...\n", uz.remote.flow.logging.LogCategory.RUN, p.name)
            syncMgr.syncSingleServer(
                profile = p,
                onLog = { logService.log(it, uz.remote.flow.logging.LogCategory.SYNC, p.name) },
                onComplete = { success ->
                    if (!success) {
                        logService.log("[REMOTE RUN WARNING] Sync warning occurred, proceeding with command...\n", uz.remote.flow.logging.LogCategory.RUN, p.name, true)
                    }
                    val javaInfo = if (p.javaHome.isNotBlank()) " (Java: ${p.javaHome})" else ""
                    logService.log("[REMOTE RUN] 2. Executing remote command on server$javaInfo: $rawCmd\n", uz.remote.flow.logging.LogCategory.RUN, p.name)
                    connMgr.executeRemoteCommand(
                        cmd = cmd,
                        workingDir = p.remoteProjectPath,
                        onOutput = { logService.log(it, uz.remote.flow.logging.LogCategory.RUN, p.name) },
                        onComplete = { code ->
                            logService.log("[REMOTE RUN FINISHED] Exit code: $code\n", uz.remote.flow.logging.LogCategory.RUN, p.name)
                            connMgr.notifyUser("Remote Flow: Execution Finished", "Application completed on server ${p.name} (Exit code: $code)", NotificationType.INFORMATION)
                        }
                    )
                }
            )
        }

        if (!connMgr.isConnected) {
            connMgr.notifyUser("Remote Flow", "Connecting to ${p.name} (${p.host})...", NotificationType.INFORMATION)
            logService.log("[CONNECT] Connecting to ${p.name} (${p.host}:${p.port})...\n", uz.remote.flow.logging.LogCategory.SSH, p.name)
            connMgr.connect(
                profile = p,
                onSuccess = { doRun() },
                onError = { err ->
                    logService.log("[ERROR] Connection failed: ${err.message}\n", uz.remote.flow.logging.LogCategory.SSH, p.name, true)
                    connMgr.notifyUser("Remote Flow: Connection Failed", "Could not connect to ${p.name}: ${err.message}", NotificationType.ERROR)
                }
            )
        } else {
            doRun()
        }
    }
}

class RemoteFlowDebugAction : AnAction("Remote Debug", "Run on remote server in JVM debug mode (port 5005)", RemoteFlowIcons.REMOTE_DEBUG) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null || project.isDisposed) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val settings = RemoteFlowSettings.getInstance(project)
        val p = settings.activeProfileOrNull
        e.presentation.isEnabledAndVisible = true
        if (p != null) {
            e.presentation.text = "Remote Debug"
            e.presentation.description = "Remote Debug on ${p.name} (Port 5005)"
        } else {
            e.presentation.text = "Remote Debug"
            e.presentation.description = "Run application on remote server in JVM debug mode"
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val connMgr = RemoteConnectionManager.getInstance(project)
        val syncMgr = FastSyncManager(project)
        val settings = RemoteFlowSettings.getInstance(project)
        val p = settings.activeProfileOrNull
        if (p == null || p.host.isBlank()) {
            connMgr.notifyUser("Remote Flow", "No active server profile configured!", NotificationType.WARNING)
            return
        }

        val logService = uz.remote.flow.logging.RemoteFlowLogService.getInstance(project)
        logService.showLogWindow()

        val doDebug = {
            val rawCmd = p.debugCommand.ifBlank { "./gradlew bootRun --debug-jvm" }
            val cmd = uz.remote.flow.ssh.buildRemoteExecutionCommand(rawCmd, p.javaHome)

            logService.log("[REMOTE DEBUG] 1. Syncing code to remote server ${p.name}...\n", uz.remote.flow.logging.LogCategory.RUN, p.name)
            syncMgr.syncSingleServer(
                profile = p,
                onLog = { logService.log(it, uz.remote.flow.logging.LogCategory.SYNC, p.name) },
                onComplete = { _ ->
                    val javaInfo = if (p.javaHome.isNotBlank()) " (Java: ${p.javaHome})" else ""
                    logService.log("[REMOTE DEBUG] 2. Launching application in debug mode (port 5005) on server$javaInfo: $rawCmd\n", uz.remote.flow.logging.LogCategory.RUN, p.name)
                    connMgr.executeRemoteCommand(
                        cmd = cmd,
                        workingDir = p.remoteProjectPath,
                        onOutput = { logService.log(it, uz.remote.flow.logging.LogCategory.RUN, p.name) },
                        onComplete = { code ->
                            logService.log("[REMOTE DEBUG EXIT] Exit code: $code\n", uz.remote.flow.logging.LogCategory.RUN, p.name)
                        }
                    )
                    logService.log("[DEBUGGER READY] Server is listening on port 5005. Launch IntelliJ 'Remote JVM Debug' configuration!\n", uz.remote.flow.logging.LogCategory.RUN, p.name)
                    connMgr.notifyUser("Remote Flow: Debug Ready", "Server is listening on port 5005. Connect via IntelliJ 'Remote JVM Debug'!", NotificationType.INFORMATION)
                }
            )
        }

        if (!connMgr.isConnected) {
            connMgr.notifyUser("Remote Flow", "Connecting to ${p.name} (${p.host})...", NotificationType.INFORMATION)
            logService.log("[CONNECT] Connecting to ${p.name} (${p.host}:${p.port})...\n", uz.remote.flow.logging.LogCategory.SSH, p.name)
            connMgr.connect(
                profile = p,
                onSuccess = { doDebug() },
                onError = { err ->
                    logService.log("[ERROR] Connection failed: ${err.message}\n", uz.remote.flow.logging.LogCategory.SSH, p.name, true)
                    connMgr.notifyUser("Remote Flow: Connection Failed", "Could not connect to ${p.name}: ${err.message}", NotificationType.ERROR)
                }
            )
        } else {
            doDebug()
        }
    }
}

class RemoteFlowStopAction : AnAction("Remote Stop", "Stop running application on remote server", RemoteFlowIcons.REMOTE_STOP) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null || project.isDisposed) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val settings = RemoteFlowSettings.getInstance(project)
        val p = settings.activeProfileOrNull
        e.presentation.isEnabledAndVisible = true
        if (p != null) {
            e.presentation.text = "Remote Stop"
            e.presentation.description = "Stop application on ${p.name}"
        } else {
            e.presentation.text = "Remote Stop"
            e.presentation.description = "Stop application on active remote server"
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val connMgr = RemoteConnectionManager.getInstance(project)
        val settings = RemoteFlowSettings.getInstance(project)
        val p = settings.activeProfileOrNull
        if (p == null || p.host.isBlank()) return

        val logService = uz.remote.flow.logging.RemoteFlowLogService.getInstance(project)
        logService.showLogWindow()

        if (!connMgr.isConnected) {
            connMgr.notifyUser("Remote Flow", "Server is not connected.", NotificationType.WARNING)
            return
        }

        logService.log("[STOPPING] Sending stop command to remote application on ${p.name}...\n", uz.remote.flow.logging.LogCategory.RUN, p.name)
        val stopCmd = "pkill -f bootRun 2>/dev/null; pkill -f 'java.*jar' 2>/dev/null; echo 'App stopped.'"
        connMgr.executeRemoteCommand(
            cmd = stopCmd,
            workingDir = p.remoteProjectPath,
            onOutput = { logService.log(it, uz.remote.flow.logging.LogCategory.RUN, p.name) },
            onComplete = {
                logService.log("[STOPPED] Application stopped on ${p.name}.\n", uz.remote.flow.logging.LogCategory.RUN, p.name)
                connMgr.notifyUser("Remote Flow", "Application stopped on server: ${p.name}", NotificationType.INFORMATION)
            }
        )
    }
}

class RemoteFlowTerminalAction : AnAction("Open Remote Terminal", "Open interactive SSH terminal session", RemoteFlowIcons.REMOTE_FLOW) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val settings = RemoteFlowSettings.getInstance(project)
        val p = settings.activeProfileOrNull ?: return
        uz.remote.flow.terminal.RemoteTerminalHelper.openTerminal(project, p)
    }
}

class RemoteFlowBrowseFilesAction : AnAction("Browse Remote Files...", "Explore remote server files and directories", RemoteFlowIcons.REMOTE_FLOW) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val settings = RemoteFlowSettings.getInstance(project)
        val profile = settings.activeProfileOrNull
        if (profile == null || profile.host.isBlank()) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, RemoteFlowConfigurable::class.java)
            return
        }
        val tw = ToolWindowManager.getInstance(project).getToolWindow("RemoteFlow")
        if (tw != null) {
            tw.show {
                val content = tw.contentManager.getContent(0)
                val panel = content?.component as? uz.remote.flow.ui.RemoteFlowMainPanel
                panel?.selectTab("Files")
            }
        } else {
            val dialog = uz.remote.flow.ui.RemoteFileExplorerDialog(project, profile)
            dialog.show()
        }
    }
}

class RemoteFlowOpenLogAction : AnAction("Show Remote Flow Logs", "Open unified execution logs tool window", RemoteFlowIcons.REMOTE_FLOW) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        uz.remote.flow.logging.RemoteFlowLogService.getInstance(project).showLogWindow()
    }
}

class RemoteFlowOpenSettingsAction : AnAction("Configure Remote Flow...", "Manage servers and settings", RemoteFlowIcons.REMOTE_FLOW) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ShowSettingsUtil.getInstance().showSettingsDialog(project, RemoteFlowConfigurable::class.java)
    }
}

class RemoteFlowConfigAction : AnAction("Remote Configs (.env / .yml)...", "Manage remote .env and application.yml configurations", RemoteFlowIcons.REMOTE_FLOW) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val settings = RemoteFlowSettings.getInstance(project)
        val profile = settings.activeProfileOrNull
        if (profile == null || profile.host.isBlank()) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, RemoteFlowConfigurable::class.java)
            return
        }
        val dialog = uz.remote.flow.config.RemoteConfigManagerDialog(project, profile)
        dialog.show()
    }
}
