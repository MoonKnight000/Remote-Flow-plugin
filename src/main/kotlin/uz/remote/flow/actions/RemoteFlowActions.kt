package uz.remote.flow.actions

import com.intellij.notification.NotificationType
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

        connMgr.notifyUser("Remote Flow", "Uploading '$relPath' to ${p.name}...")
        syncMgr.syncSpecificPath(
            profile = p,
            relativePath = relPath,
            onLog = {},
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

class RemoteFlowRunAction : AnAction("Run on Remote Server", "Build and run application on remote server", RemoteFlowIcons.REMOTE_FLOW) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val tw = ToolWindowManager.getInstance(project).getToolWindow("RemoteFlow")
        tw?.show {
            val connMgr = RemoteConnectionManager.getInstance(project)
            val syncMgr = FastSyncManager(project)
            val settings = RemoteFlowSettings.getInstance(project)
            val p = settings.activeProfile

            if (!connMgr.isConnected) {
                connMgr.notifyUser("Remote Flow", "Not connected to server! Please connect first.", NotificationType.WARNING)
                return@show
            }

            connMgr.notifyUser("Remote Flow", "Syncing code and executing on remote server...")
            syncMgr.syncSingleServer(
                profile = p,
                onLog = {},
                onComplete = {
                    val rawCmd = p.runCommand
                    val runCmd = "sed -i 's/\\r$//' ./gradlew ./mvnw *.sh 2>/dev/null || true; chmod +x ./gradlew ./mvnw *.sh 2>/dev/null || true; $rawCmd"
                    connMgr.executeRemoteCommand(
                        cmd = runCmd,
                        workingDir = p.remoteProjectPath,
                        onOutput = {},
                        onComplete = { code ->
                            connMgr.notifyUser("Remote Flow", "Application execution finished (Exit code: $code)")
                        }
                    )
                }
            )
        }
    }
}

class RemoteFlowDebugAction : AnAction("Debug on Remote Server", "Run on remote server in JVM debug mode (port 5005)", RemoteFlowIcons.REMOTE_FLOW) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val tw = ToolWindowManager.getInstance(project).getToolWindow("RemoteFlow")
        tw?.show {
            val connMgr = RemoteConnectionManager.getInstance(project)
            val syncMgr = FastSyncManager(project)
            val settings = RemoteFlowSettings.getInstance(project)
            val p = settings.activeProfile

            if (!connMgr.isConnected) {
                connMgr.notifyUser("Remote Flow", "Not connected to server! Please connect first.", NotificationType.WARNING)
                return@show
            }

            connMgr.notifyUser("Remote Flow", "Starting application in debug mode (Port: 5005)...")
            syncMgr.syncSingleServer(
                profile = p,
                onLog = {},
                onComplete = {
                    val rawCmd = p.debugCommand
                    val debugCmd = "sed -i 's/\\r$//' ./gradlew ./mvnw *.sh 2>/dev/null || true; chmod +x ./gradlew ./mvnw *.sh 2>/dev/null || true; $rawCmd"
                    connMgr.executeRemoteCommand(
                        cmd = debugCmd,
                        workingDir = p.remoteProjectPath,
                        onOutput = {},
                        onComplete = { code ->
                            connMgr.notifyUser("Remote Flow", "Debug session ended (Exit code: $code)")
                        }
                    )
                    connMgr.notifyUser("Remote Flow: Debug Ready", "Server is listening on port 5005. Launch IntelliJ Remote JVM Debug!")
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
        val tw = ToolWindowManager.getInstance(project).getToolWindow("Remote Flow Log")
        tw?.show(null)
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
