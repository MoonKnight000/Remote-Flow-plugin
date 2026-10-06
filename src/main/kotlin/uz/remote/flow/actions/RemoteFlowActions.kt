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
import com.intellij.icons.AllIcons
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

class RemoteFlowPullAction : AnAction("Pull from Remote Server", "Download latest files from remote server into local workspace", AllIcons.Actions.Download) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val syncMgr = FastSyncManager(project)
        val connMgr = RemoteConnectionManager.getInstance(project)
        val settings = RemoteFlowSettings.getInstance(project)
        val p = settings.activeProfile

        connMgr.notifyUser("Remote Flow: Pull", "Downloading files from ${p.name} to local workspace...")
        syncMgr.pullFromRemote(
            profile = p,
            onLog = {},
            onComplete = { success ->
                if (success) {
                    connMgr.notifyUser("Remote Flow: Pull Complete", "Downloaded changes from remote server!", NotificationType.INFORMATION)
                } else {
                    connMgr.notifyUser("Remote Flow: Pull Error", "Failed to pull files from server. Check logs.", NotificationType.WARNING)
                }
            }
        )
    }
}

class RemoteFlowGitSyncAction : AnAction("Git-Aware Fast Sync", "Sync only git modified and untracked files in sub-second time", AllIcons.Actions.Upload) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val syncMgr = FastSyncManager(project)
        val connMgr = RemoteConnectionManager.getInstance(project)
        val settings = RemoteFlowSettings.getInstance(project)
        val p = settings.activeProfile

        if (!uz.remote.flow.ssh.RemoteSafetyHelper.checkProductionSafe(project, p, "Git-Aware Sync")) {
            return
        }

        connMgr.notifyUser("Remote Flow: Git Sync", "Scanning and fast-syncing git modified files...")
        syncMgr.syncGitModified(
            profile = p,
            onLog = {},
            onComplete = { success ->
                if (success) {
                    connMgr.notifyUser("Remote Flow: Git Sync Done", "Git changes synchronized to ${p.name}!", NotificationType.INFORMATION)
                } else {
                    connMgr.notifyUser("Remote Flow: Git Sync Error", "Error syncing git changes. Check logs.", NotificationType.WARNING)
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
        val connMgr = RemoteConnectionManager.getInstance(project)
        val isRunning = connMgr.isProcessRunning
        e.presentation.isEnabledAndVisible = true
        val selConfig = try {
            com.intellij.execution.RunManager.getInstance(project).selectedConfiguration?.name
        } catch (_: Throwable) { null }
        val configStr = if (selConfig.isNullOrBlank()) "" else " ['$selConfig']"

        if (isRunning) {
            e.presentation.text = "Rerun"
            e.presentation.icon = AllIcons.Actions.Restart
            e.presentation.description = "Rerun application$configStr on ${p?.name ?: "remote server"} (restart and sync)"
        } else {
            e.presentation.text = "Remote Run"
            e.presentation.icon = RemoteFlowIcons.REMOTE_RUN
            if (p != null) {
                val httpPort = p.forwardedPorts.firstOrNull { fp -> fp.direction == uz.remote.flow.ssh.ForwardDirection.LOCAL_TO_REMOTE }?.localPort ?: 8080
                e.presentation.description = "Remote Run$configStr on ${p.name} (Port $httpPort forwarded to localhost)"
            } else {
                e.presentation.description = "Sync, build and run on active remote server"
            }
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        runDirectly(project)
    }

    companion object {
        fun runDirectly(project: Project, runConfig: uz.remote.flow.run.RemoteFlowRunConfiguration? = null) {
            val settings = RemoteFlowSettings.getInstance(project)
            val runManager = com.intellij.execution.RunManager.getInstance(project)
            val type = uz.remote.flow.run.RemoteFlowConfigurationType.getInstance()
            val connMgr = uz.remote.flow.ssh.RemoteConnectionManager.getInstance(project)
            var configSettings = runConfig?.let { rc ->
                runManager.allSettings.find { it.configuration == rc }
            } ?: runManager.selectedConfiguration?.takeIf {
                it.configuration is uz.remote.flow.run.RemoteFlowRunConfiguration ||
                (settings.routeStandardRunToRemote && connMgr.isConnected && uz.remote.flow.execution.RemoteFlowProgramRunner.isSupportedStandardApplication(it.configuration))
            } ?: runManager.getConfigurationSettingsList(type).firstOrNull()

            if (configSettings == null) {
                uz.remote.flow.run.RemoteFlowStartupActivity.ensureDefaultRunConfiguration(project)
                configSettings = runManager.getConfigurationSettingsList(type).firstOrNull() ?: run {
                    val factory = type.configurationFactories.firstOrNull() ?: return
                    val newSettings = runManager.createConfiguration("Remote Flow", factory)
                    runManager.addConfiguration(newSettings)
                    runManager.selectedConfiguration = newSettings
                    newSettings
                }
            }

            uz.remote.flow.logging.RemoteFlowLogService.getInstance(project).showLogWindow()

            com.intellij.execution.ProgramRunnerUtil.executeConfiguration(
                configSettings,
                com.intellij.execution.executors.DefaultRunExecutor.getRunExecutorInstance()
            )
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
            val settings = RemoteFlowSettings.getInstance(project)
            val runManager = com.intellij.execution.RunManager.getInstance(project)
            val type = uz.remote.flow.run.RemoteFlowConfigurationType.getInstance()
            val connMgr = uz.remote.flow.ssh.RemoteConnectionManager.getInstance(project)
            var configSettings = runConfig?.let { rc ->
                runManager.allSettings.find { it.configuration == rc }
            } ?: runManager.selectedConfiguration?.takeIf {
                it.configuration is uz.remote.flow.run.RemoteFlowRunConfiguration ||
                (settings.routeStandardRunToRemote && connMgr.isConnected && uz.remote.flow.execution.RemoteFlowProgramRunner.isSupportedStandardApplication(it.configuration))
            } ?: runManager.getConfigurationSettingsList(type).firstOrNull()

            if (configSettings == null) {
                uz.remote.flow.run.RemoteFlowStartupActivity.ensureDefaultRunConfiguration(project)
                configSettings = runManager.getConfigurationSettingsList(type).firstOrNull() ?: run {
                    val factory = type.configurationFactories.firstOrNull() ?: return
                    val newSettings = runManager.createConfiguration("Remote Flow", factory)
                    runManager.addConfiguration(newSettings)
                    runManager.selectedConfiguration = newSettings
                    newSettings
                }
            }

            uz.remote.flow.logging.RemoteFlowLogService.getInstance(project).showLogWindow()

            com.intellij.execution.ProgramRunnerUtil.executeConfiguration(
                configSettings,
                com.intellij.execution.executors.DefaultDebugExecutor.getDebugExecutorInstance()
            )
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

        val isRunning = connMgr.isConnected && connMgr.isProcessRunning
        e.presentation.isVisible = isRunning
        e.presentation.isEnabled = isRunning

        if (p != null) {
            e.presentation.text = if (isRunning) "Stop ${p.name} App" else "Remote Stop"
            e.presentation.description = if (isRunning) "Stop application running on ${p.name}" else "No application is currently running"
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
