package uz.remote.flow.actions

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
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

        if (!uz.remote.flow.ssh.RemoteSafetyHelper.checkProductionSafe(project, p, "Sync Project Files")) {
            return
        }

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
        val selConfig = try {
            com.intellij.execution.RunManager.getInstance(project).selectedConfiguration?.name
        } catch (_: Throwable) { null }
        val configStr = if (selConfig.isNullOrBlank()) "" else " ['$selConfig']"

        if (p != null) {
            val httpPort = p.forwardedPorts.firstOrNull { fp -> fp.direction == uz.remote.flow.ssh.ForwardDirection.LOCAL_TO_REMOTE }?.localPort ?: 8080
            e.presentation.text = "Remote Run"
            e.presentation.description = "Remote Run$configStr on ${p.name} (Port $httpPort forwarded to localhost)"
        } else {
            e.presentation.text = "Remote Run"
            e.presentation.description = "Sync, build and run on active remote server"
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        runDirectly(project)
    }

    companion object {
        fun runDirectly(project: Project, runConfig: uz.remote.flow.run.RemoteFlowRunConfiguration? = null) {
            val connMgr = RemoteConnectionManager.getInstance(project)
            val syncMgr = FastSyncManager(project)
            val settings = RemoteFlowSettings.getInstance(project)
            val p = if (runConfig != null && runConfig.serverProfileName.isNotBlank() && runConfig.serverProfileName != "[Active Server Profile]") {
                settings.profiles.find { it.name.equals(runConfig.serverProfileName, ignoreCase = true) } ?: settings.activeProfileOrNull
            } else {
                settings.activeProfileOrNull
            }
            if (p == null || p.host.isBlank()) {
                connMgr.notifyUser("Remote Flow", "No active server profile configured!", NotificationType.WARNING)
                return
            }

            if (!uz.remote.flow.ssh.RemoteSafetyHelper.checkProductionSafe(project, p, "Remote Run")) {
                return
            }

            val logService = uz.remote.flow.logging.RemoteFlowLogService.getInstance(project)
            logService.showLogWindow()

            val workingDir = if (runConfig != null && runConfig.remoteWorkingDir.isNotBlank()) {
                runConfig.remoteWorkingDir.trim()
            } else {
                p.remoteProjectPath
            }
            val shouldSync = runConfig?.autoSync ?: true
            val shouldForward = runConfig?.forwardPorts ?: true

            val doRun = {
                // 1. Ensure configured port forwards are active
                if (shouldForward) {
                    connMgr.startPortForwarding(p)
                }

                val rawCmd = if (runConfig != null && runConfig.runCommand.isNotBlank()) {
                    runConfig.runCommand.trim()
                } else {
                    p.runCommand.ifBlank { "./gradlew bootRun" }
                }
                val cmd = uz.remote.flow.ssh.buildRemoteExecutionCommand(rawCmd, p.javaHome)

                val executeCmd = {
                    val javaInfo = if (p.javaHome.isNotBlank()) " (Java: ${p.javaHome})" else ""
                    logService.log("[REMOTE RUN] Executing remote command on server$javaInfo: $rawCmd\n", uz.remote.flow.logging.LogCategory.RUN, p.name)
                    val readyNotified = java.util.concurrent.atomic.AtomicBoolean(false)
                    connMgr.executeRemoteCommand(
                        cmd = cmd,
                        workingDir = workingDir,
                        isLongRunning = true,
                        onOutput = { line ->
                            logService.log(line, uz.remote.flow.logging.LogCategory.RUN, p.name)
                            if (!readyNotified.get() && (line.contains("Tomcat started on port(s):") || line.contains("Netty started on port") || (line.contains("Started ") && line.contains(" in ") && line.contains("seconds")))) {
                                readyNotified.set(true)
                                val httpPort = p.forwardedPorts.firstOrNull { fp -> fp.direction == uz.remote.flow.ssh.ForwardDirection.LOCAL_TO_REMOTE }?.localPort ?: 8080
                                val targetUrl = if (p.browserUrl.isNotBlank()) p.browserUrl else "http://localhost:$httpPort"
                                logService.log("[APP READY] 🚀 Application is ready! Accessible locally at: $targetUrl\n", uz.remote.flow.logging.LogCategory.RUN, p.name)
                                if (p.openBrowserOnReady) {
                                    ApplicationManager.getApplication().invokeLater {
                                        try {
                                            com.intellij.ide.BrowserUtil.browse(targetUrl)
                                        } catch (_: Throwable) {}
                                    }
                                }
                                if (p.postRunCommand.isNotBlank()) {
                                    logService.log("[POST-RUN HOOK] Running post-run hook: ${p.postRunCommand}\n", uz.remote.flow.logging.LogCategory.RUN, p.name)
                                    val postCmd = uz.remote.flow.ssh.buildRemoteExecutionCommand(p.postRunCommand, p.javaHome)
                                    connMgr.executeRemoteCommand(postCmd, workingDir, false, { logService.log(it, uz.remote.flow.logging.LogCategory.RUN, p.name) }, {})
                                }
                            }
                        },
                        onComplete = { code ->
                            logService.log("[REMOTE RUN FINISHED] Exit code: $code\n", uz.remote.flow.logging.LogCategory.RUN, p.name)
                            connMgr.notifyUser("Remote Flow: Execution Finished", "Application completed on server ${p.name} (Exit code: $code)", NotificationType.INFORMATION)
                        }
                    )
                }

                val runWithPreHook = {
                    if (p.preRunCommand.isNotBlank()) {
                        logService.log("[PRE-RUN HOOK] Executing pre-run hook on ${p.name}: ${p.preRunCommand}\n", uz.remote.flow.logging.LogCategory.RUN, p.name)
                        val preCmd = uz.remote.flow.ssh.buildRemoteExecutionCommand(p.preRunCommand, p.javaHome)
                        connMgr.executeRemoteCommand(
                            cmd = preCmd,
                            workingDir = workingDir,
                            isLongRunning = false,
                            onOutput = { logService.log(it, uz.remote.flow.logging.LogCategory.RUN, p.name) },
                            onComplete = { code ->
                                if (code == 0) {
                                    logService.log("[PRE-RUN HOOK] Completed successfully.\n", uz.remote.flow.logging.LogCategory.RUN, p.name)
                                } else {
                                    logService.log("[PRE-RUN HOOK WARNING] Finished with exit code $code, proceeding with run...\n", uz.remote.flow.logging.LogCategory.RUN, p.name, true)
                                }
                                executeCmd()
                            }
                        )
                    } else {
                        executeCmd()
                    }
                }

                if (shouldSync) {
                    logService.log("[REMOTE RUN] 1. Syncing latest code to server ${p.name}...\n", uz.remote.flow.logging.LogCategory.RUN, p.name)
                    syncMgr.syncSingleServer(
                        profile = p,
                        onLog = { logService.log(it, uz.remote.flow.logging.LogCategory.SYNC, p.name) },
                        onComplete = { success ->
                            if (!success) {
                                logService.log("[REMOTE RUN WARNING] Sync warning occurred, proceeding with command...\n", uz.remote.flow.logging.LogCategory.RUN, p.name, true)
                            }
                            runWithPreHook()
                        }
                    )
                } else {
                    logService.log("[REMOTE RUN] Skipping code sync (disabled in configuration)...\n", uz.remote.flow.logging.LogCategory.RUN, p.name)
                    runWithPreHook()
                }
            }

            val startOrRestart = {
                if (connMgr.isProcessRunning) {
                    logService.log("[REMOTE RUN] Restarting application: stopping existing instance...\n", uz.remote.flow.logging.LogCategory.RUN, p.name)
                    connMgr.stopRemoteProcess(p, onOutput = { logService.log(it, uz.remote.flow.logging.LogCategory.RUN, p.name) }) {
                        doRun()
                    }
                } else {
                    doRun()
                }
            }

            if (!connMgr.isConnected) {
                connMgr.notifyUser("Remote Flow", "Connecting to ${p.name} (${p.host})...", NotificationType.INFORMATION)
                logService.log("[CONNECT] Connecting to ${p.name} (${p.host}:${p.port})...\n", uz.remote.flow.logging.LogCategory.SSH, p.name)
                connMgr.connect(
                    profile = p,
                    onSuccess = { startOrRestart() },
                    onError = { err ->
                        logService.log("[ERROR] Connection failed: ${err.message}\n", uz.remote.flow.logging.LogCategory.SSH, p.name, true)
                        connMgr.notifyUser("Remote Flow: Connection Failed", "Could not connect to ${p.name}: ${err.message}", NotificationType.ERROR)
                    }
                )
            } else {
                startOrRestart()
            }
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
        val selConfig = try {
            com.intellij.execution.RunManager.getInstance(project).selectedConfiguration?.name
        } catch (_: Throwable) { null }
        val configStr = if (selConfig.isNullOrBlank()) "" else " ['$selConfig']"

        if (p != null) {
            e.presentation.text = "Remote Debug"
            e.presentation.description = "Remote Debug$configStr on ${p.name} (Port 5005)"
        } else {
            e.presentation.text = "Remote Debug"
            e.presentation.description = "Run application on remote server in JVM debug mode"
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        runDebugDirectly(project)
    }

    companion object {
        fun runDebugDirectly(project: Project, runConfig: uz.remote.flow.run.RemoteFlowRunConfiguration? = null) {
            val connMgr = RemoteConnectionManager.getInstance(project)
            val syncMgr = FastSyncManager(project)
            val settings = RemoteFlowSettings.getInstance(project)
            val p = if (runConfig != null && runConfig.serverProfileName.isNotBlank() && runConfig.serverProfileName != "[Active Server Profile]") {
                settings.profiles.find { it.name.equals(runConfig.serverProfileName, ignoreCase = true) } ?: settings.activeProfileOrNull
            } else {
                settings.activeProfileOrNull
            }
            if (p == null || p.host.isBlank()) {
                connMgr.notifyUser("Remote Flow", "No active server profile configured!", NotificationType.WARNING)
                return
            }

            if (!uz.remote.flow.ssh.RemoteSafetyHelper.checkProductionSafe(project, p, "Remote Debug")) {
                return
            }

            val logService = uz.remote.flow.logging.RemoteFlowLogService.getInstance(project)
            logService.showLogWindow()

            val workingDir = if (runConfig != null && runConfig.remoteWorkingDir.isNotBlank()) {
                runConfig.remoteWorkingDir.trim()
            } else {
                p.remoteProjectPath
            }
            val shouldSync = runConfig?.autoSync ?: true
            val shouldForward = runConfig?.forwardPorts ?: true

            val doDebug = {
                // 1. Ensure port 5005 and user ports are forwarded
                connMgr.startSingleForward(uz.remote.flow.ssh.PortMapping(5005, 5005, "JVM Debug", direction = uz.remote.flow.ssh.ForwardDirection.LOCAL_TO_REMOTE))
                if (shouldForward) {
                    connMgr.startPortForwarding(p)
                }

                val rawCmd = if (runConfig != null && runConfig.debugCommand.isNotBlank()) {
                    runConfig.debugCommand.trim()
                } else {
                    p.debugCommand.ifBlank { "./gradlew bootRun --debug-jvm" }
                }
                val cmd = uz.remote.flow.ssh.buildRemoteExecutionCommand(rawCmd, p.javaHome)

                val executeCmd = {
                    val javaInfo = if (p.javaHome.isNotBlank()) " (Java: ${p.javaHome})" else ""
                    logService.log("[REMOTE DEBUG] Launching application in debug mode (port 5005) on server$javaInfo: $rawCmd\n", uz.remote.flow.logging.LogCategory.RUN, p.name)

                    val debuggerAttached = java.util.concurrent.atomic.AtomicBoolean(false)
                    val readyNotified = java.util.concurrent.atomic.AtomicBoolean(false)
                    connMgr.executeRemoteCommand(
                        cmd = cmd,
                        workingDir = workingDir,
                        isLongRunning = true,
                        onOutput = { line ->
                            logService.log(line, uz.remote.flow.logging.LogCategory.RUN, p.name)
                            if (!debuggerAttached.get() && (line.contains("Listening for transport dt_socket at address:") || line.contains("dt_socket") || line.contains("5005"))) {
                                if (debuggerAttached.compareAndSet(false, true)) {
                                    uz.remote.flow.debug.RemoteDebugHelper.attachRemoteDebugger(project, "localhost", 5005, p.name)
                                }
                            }
                            if (!readyNotified.get() && (line.contains("Tomcat started on port(s):") || line.contains("Netty started on port") || (line.contains("Started ") && line.contains(" in ") && line.contains("seconds")))) {
                                readyNotified.set(true)
                                val httpPort = p.forwardedPorts.firstOrNull { fp -> fp.direction == uz.remote.flow.ssh.ForwardDirection.LOCAL_TO_REMOTE }?.localPort ?: 8080
                                val targetUrl = if (p.browserUrl.isNotBlank()) p.browserUrl else "http://localhost:$httpPort"
                                logService.log("[APP READY] 🚀 Application is ready! Accessible locally at: $targetUrl\n", uz.remote.flow.logging.LogCategory.RUN, p.name)
                                if (p.openBrowserOnReady) {
                                    ApplicationManager.getApplication().invokeLater {
                                        try {
                                            com.intellij.ide.BrowserUtil.browse(targetUrl)
                                        } catch (_: Throwable) {}
                                    }
                                }
                                if (p.postRunCommand.isNotBlank()) {
                                    logService.log("[POST-RUN HOOK] Running post-run hook: ${p.postRunCommand}\n", uz.remote.flow.logging.LogCategory.RUN, p.name)
                                    val postCmd = uz.remote.flow.ssh.buildRemoteExecutionCommand(p.postRunCommand, p.javaHome)
                                    connMgr.executeRemoteCommand(postCmd, workingDir, false, { logService.log(it, uz.remote.flow.logging.LogCategory.RUN, p.name) }, {})
                                }
                            }
                        },
                        onComplete = { code ->
                            logService.log("[REMOTE DEBUG EXIT] Exit code: $code\n", uz.remote.flow.logging.LogCategory.RUN, p.name)
                        }
                    )

                    // Scheduled fallback: attach debugger if socket is listening after 3.5 seconds
                    com.intellij.util.concurrency.AppExecutorUtil.getAppScheduledExecutorService().schedule({
                        if (connMgr.isProcessRunning && debuggerAttached.compareAndSet(false, true)) {
                            uz.remote.flow.debug.RemoteDebugHelper.attachRemoteDebugger(project, "localhost", 5005, p.name)
                        }
                    }, 3500, java.util.concurrent.TimeUnit.MILLISECONDS)
                }

                val runWithPreHook = {
                    if (p.preRunCommand.isNotBlank()) {
                        logService.log("[PRE-RUN HOOK] Executing pre-run hook on ${p.name}: ${p.preRunCommand}\n", uz.remote.flow.logging.LogCategory.RUN, p.name)
                        val preCmd = uz.remote.flow.ssh.buildRemoteExecutionCommand(p.preRunCommand, p.javaHome)
                        connMgr.executeRemoteCommand(
                            cmd = preCmd,
                            workingDir = workingDir,
                            isLongRunning = false,
                            onOutput = { logService.log(it, uz.remote.flow.logging.LogCategory.RUN, p.name) },
                            onComplete = { code ->
                                if (code == 0) {
                                    logService.log("[PRE-RUN HOOK] Completed successfully.\n", uz.remote.flow.logging.LogCategory.RUN, p.name)
                                } else {
                                    logService.log("[PRE-RUN HOOK WARNING] Finished with exit code $code, proceeding with debug...\n", uz.remote.flow.logging.LogCategory.RUN, p.name, true)
                                }
                                executeCmd()
                            }
                        )
                    } else {
                        executeCmd()
                    }
                }

                if (shouldSync) {
                    logService.log("[REMOTE DEBUG] 1. Syncing code to remote server ${p.name}...\n", uz.remote.flow.logging.LogCategory.RUN, p.name)
                    syncMgr.syncSingleServer(
                        profile = p,
                        onLog = { logService.log(it, uz.remote.flow.logging.LogCategory.SYNC, p.name) },
                        onComplete = { _ ->
                            runWithPreHook()
                        }
                    )
                } else {
                    logService.log("[REMOTE DEBUG] Skipping code sync (disabled in configuration)...\n", uz.remote.flow.logging.LogCategory.RUN, p.name)
                    runWithPreHook()
                }
            }

            val startOrRestart = {
                if (connMgr.isProcessRunning) {
                    logService.log("[REMOTE DEBUG] Restarting debug session: stopping existing instance...\n", uz.remote.flow.logging.LogCategory.RUN, p.name)
                    connMgr.stopRemoteProcess(p, onOutput = { logService.log(it, uz.remote.flow.logging.LogCategory.RUN, p.name) }) {
                        doDebug()
                    }
                } else {
                    doDebug()
                }
            }

            if (!connMgr.isConnected) {
                connMgr.notifyUser("Remote Flow", "Connecting to ${p.name} (${p.host})...", NotificationType.INFORMATION)
                logService.log("[CONNECT] Connecting to ${p.name} (${p.host}:${p.port})...\n", uz.remote.flow.logging.LogCategory.SSH, p.name)
                connMgr.connect(
                    profile = p,
                    onSuccess = { startOrRestart() },
                    onError = { err ->
                        logService.log("[ERROR] Connection failed: ${err.message}\n", uz.remote.flow.logging.LogCategory.SSH, p.name, true)
                        connMgr.notifyUser("Remote Flow: Connection Failed", "Could not connect to ${p.name}: ${err.message}", NotificationType.ERROR)
                    }
                )
            } else {
                startOrRestart()
            }
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
        val connMgr = RemoteConnectionManager.getInstance(project)
        val settings = RemoteFlowSettings.getInstance(project)
        val p = settings.activeProfileOrNull

        e.presentation.isVisible = true
        // Only enabled when connected AND process is actually running!
        e.presentation.isEnabled = connMgr.isConnected && connMgr.isProcessRunning

        if (p != null) {
            e.presentation.text = if (connMgr.isProcessRunning) "Stop ${p.name} App" else "Remote Stop"
            e.presentation.description = if (connMgr.isProcessRunning) "Stop application running on ${p.name}" else "No application is currently running"
        } else {
            e.presentation.text = "Remote Stop"
            e.presentation.description = "Stop application on active remote server"
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        stopDirectly(project)
    }

    companion object {
        fun stopDirectly(project: Project) {
            val connMgr = RemoteConnectionManager.getInstance(project)
            val settings = RemoteFlowSettings.getInstance(project)
            val p = settings.activeProfileOrNull
            if (p == null || p.host.isBlank()) return

            if (!uz.remote.flow.ssh.RemoteSafetyHelper.checkProductionSafe(project, p, "Stop Remote Application")) {
                return
            }

            val logService = uz.remote.flow.logging.RemoteFlowLogService.getInstance(project)
            logService.showLogWindow()

            if (!connMgr.isConnected) {
                connMgr.notifyUser("Remote Flow", "Server is not connected.", NotificationType.WARNING)
                return
            }

            logService.log("[STOPPING] Stopping remote application on ${p.name}...\n", uz.remote.flow.logging.LogCategory.RUN, p.name)
            connMgr.stopRemoteProcess(
                profile = p,
                onOutput = { logService.log(it, uz.remote.flow.logging.LogCategory.RUN, p.name) },
                onComplete = {
                    logService.log("[STOPPED] Application stopped on ${p.name}.\n", uz.remote.flow.logging.LogCategory.RUN, p.name)
                    connMgr.notifyUser("Remote Flow", "Application stopped on server: ${p.name}", NotificationType.INFORMATION)
                }
            )
        }
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
