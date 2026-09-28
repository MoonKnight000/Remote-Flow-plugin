package uz.remote.flow.actions

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.wm.ToolWindowManager
import uz.remote.flow.docker.DockerComposeManager
import uz.remote.flow.settings.RemoteFlowConfigurable
import uz.remote.flow.settings.RemoteFlowSettings
import uz.remote.flow.ssh.RemoteConnectionManager
import uz.remote.flow.sync.FastSyncManager
import uz.remote.flow.ui.RemoteFlowIcons

class RemoteFlowConnectAction : AnAction("Connect / Disconnect Server", "Serverga ulanish yoki aloqani uzish", RemoteFlowIcons.REMOTE_FLOW) {
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
            connMgr.notifyUser("Remote Flow", "Server bilan aloqa uzildi: ${p.name}")
        } else {
            connMgr.connect(
                profile = p,
                onSuccess = {
                    connMgr.notifyUser("Remote Flow", "Muvaffaqiyatli ulandi: ${p.name}")
                },
                onError = { err ->
                    connMgr.notifyUser("Remote Flow: Ulanish xatosi", err.message ?: err.toString(), NotificationType.ERROR)
                }
            )
        }
    }
}

class RemoteFlowSyncAction : AnAction("Sync Project to Remote", "Loyiha kodlarini faol serverga yuklash", RemoteFlowIcons.REMOTE_FLOW) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val syncMgr = FastSyncManager(project)
        val connMgr = RemoteConnectionManager.getInstance(project)
        val settings = RemoteFlowSettings.getInstance(project)
        val p = settings.activeProfile

        connMgr.notifyUser("Remote Flow: Sinxronizatsiya", "${p.name} serveriga kodlar yuklanmoqda...")
        syncMgr.syncSingleServer(
            profile = p,
            onLog = {},
            onComplete = { success ->
                if (success) {
                    connMgr.notifyUser("Remote Flow: Tayyor", "Barcha fayllar serverga muvaffaqiyatli yuklandi!", NotificationType.INFORMATION)
                } else {
                    connMgr.notifyUser("Remote Flow: Xato", "Sinxronizatsiyada xatolik yuz berdi. Console jurnalini ko'ring.", NotificationType.WARNING)
                }
            }
        )
    }
}

class RemoteFlowSyncSelectionAction : AnAction("Sync Selected File/Folder to Remote", "Tanlangan fayl yoki papkani serverga yuklash", RemoteFlowIcons.REMOTE_FLOW) {
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

        connMgr.notifyUser("Remote Flow", "'$relPath' fayli ${p.name} serveriga yuklanmoqda...")
        syncMgr.syncSpecificPath(
            profile = p,
            relativePath = relPath,
            onLog = {},
            onComplete = { success ->
                if (success) {
                    connMgr.notifyUser("Remote Flow", "'$relPath' serverga muvaffaqiyatli yuklandi!", NotificationType.INFORMATION)
                } else {
                    connMgr.notifyUser("Remote Flow: Xato", "'$relPath' yuklashda xatolik bo'ldi.", NotificationType.WARNING)
                }
            }
        )
    }
}

class RemoteFlowRunAction : AnAction("Run on Remote Server", "Masofaviy serverda build qilib ishga tushirish", RemoteFlowIcons.REMOTE_FLOW) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val tw = ToolWindowManager.getInstance(project).getToolWindow("RemoteFlow")
        tw?.show {
            val connMgr = RemoteConnectionManager.getInstance(project)
            val syncMgr = FastSyncManager(project)
            val settings = RemoteFlowSettings.getInstance(project)
            val p = settings.activeProfile

            if (!connMgr.isConnected) {
                connMgr.notifyUser("Remote Flow", "Serverga ulanilmagan! Avval ulaning.", NotificationType.WARNING)
                return@show
            }

            connMgr.notifyUser("Remote Flow", "Kodlar yuklanmoqda va serverda ishga tushirilmoqda...")
            syncMgr.syncSingleServer(
                profile = p,
                onLog = {},
                onComplete = {
                    connMgr.executeRemoteCommand(
                        cmd = p.runCommand,
                        workingDir = p.remoteProjectPath,
                        onOutput = {},
                        onComplete = { code ->
                            connMgr.notifyUser("Remote Flow", "Dastur bajarildi (Exit code: $code)")
                        }
                    )
                }
            )
        }
    }
}

class RemoteFlowDebugAction : AnAction("Debug on Remote Server", "Masofaviy serverda Debug rejimida (port 5005) ishga tushirish", RemoteFlowIcons.REMOTE_FLOW) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val tw = ToolWindowManager.getInstance(project).getToolWindow("RemoteFlow")
        tw?.show {
            val connMgr = RemoteConnectionManager.getInstance(project)
            val syncMgr = FastSyncManager(project)
            val settings = RemoteFlowSettings.getInstance(project)
            val p = settings.activeProfile

            if (!connMgr.isConnected) {
                connMgr.notifyUser("Remote Flow", "Serverga ulanilmagan! Avval ulaning.", NotificationType.WARNING)
                return@show
            }

            connMgr.notifyUser("Remote Flow", "Debug rejimida ishga tushirilmoqda (Port: 5005)...")
            syncMgr.syncSingleServer(
                profile = p,
                onLog = {},
                onComplete = {
                    connMgr.executeRemoteCommand(
                        cmd = p.debugCommand,
                        workingDir = p.remoteProjectPath,
                        onOutput = {},
                        onComplete = { code ->
                            connMgr.notifyUser("Remote Flow", "Debug sessiyasi tugadi (Exit code: $code)")
                        }
                    )
                    connMgr.notifyUser("Remote Flow: Debug Tayyor", "Server 5005 portda kutmoqda. IntelliJ Remote JVM Debug ni ishga tushiring!")
                }
            )
        }
    }
}

class RemoteFlowTerminalAction : AnAction("Open Remote Terminal", "Masofaviy server SSH terminalini ochish", RemoteFlowIcons.REMOTE_FLOW) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val settings = RemoteFlowSettings.getInstance(project)
        val p = settings.activeProfile
        val cmd = "start powershell -NoExit -Command \"Write-Host 'Connecting to " + p.name + "...' -ForegroundColor Cyan; ssh -p " + p.port + " " + p.user + "@" + p.host + "\""
        try {
            Runtime.getRuntime().exec(arrayOf("cmd.exe", "/c", cmd))
        } catch (_: Exception) {}
    }
}

class RemoteFlowDockerUpAction : AnAction("Docker Compose Up", "Serverda docker compose up -d --build", RemoteFlowIcons.REMOTE_FLOW) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val connMgr = RemoteConnectionManager.getInstance(project)
        if (!connMgr.isConnected) {
            connMgr.notifyUser("Remote Flow", "Serverga ulanilmagan! Avval ulaning.", NotificationType.WARNING)
            return
        }
        val dockerMgr = DockerComposeManager(project)
        connMgr.notifyUser("Remote Flow", "Docker Compose ishga tushirilmoqda...")
        dockerMgr.composeUp(
            build = true,
            onOutput = {},
            onComplete = { code ->
                connMgr.notifyUser("Remote Flow", "Docker Compose Up yakunlandi (Exit code: $code)")
            }
        )
    }
}

class RemoteFlowDockerDownAction : AnAction("Docker Compose Down", "Serverda docker compose down", RemoteFlowIcons.REMOTE_FLOW) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val connMgr = RemoteConnectionManager.getInstance(project)
        if (!connMgr.isConnected) {
            connMgr.notifyUser("Remote Flow", "Serverga ulanilmagan! Avval ulaning.", NotificationType.WARNING)
            return
        }
        val dockerMgr = DockerComposeManager(project)
        connMgr.notifyUser("Remote Flow", "Docker Compose to'xtatilmoqda...")
        dockerMgr.composeDown(
            onOutput = {},
            onComplete = { code ->
                connMgr.notifyUser("Remote Flow", "Docker Compose Down yakunlandi (Exit code: $code)")
            }
        )
    }
}

class RemoteFlowOpenSettingsAction : AnAction("Configure Remote Flow...", "Serverlar va sozlamalarni boshqarish", RemoteFlowIcons.REMOTE_FLOW) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ShowSettingsUtil.getInstance().showSettingsDialog(project, RemoteFlowConfigurable::class.java)
    }
}
