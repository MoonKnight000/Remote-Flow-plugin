package uz.remote.flow.ui

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBColor
import com.intellij.util.concurrency.AppExecutorUtil
import uz.remote.flow.settings.RemoteFlowConfigurable
import uz.remote.flow.settings.RemoteFlowSettings
import uz.remote.flow.ssh.RemoteConnectionListener
import uz.remote.flow.ssh.RemoteConnectionManager
import uz.remote.flow.ssh.ServerProfile
import uz.remote.flow.sync.FastSyncManager
import uz.remote.flow.system.MachineMetrics
import uz.remote.flow.system.RemoteTaskManagerDialog
import uz.remote.flow.system.ServerStatsManager
import java.awt.Color
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

class RemoteFlowStatusBarWidget(private val project: Project) : CustomStatusBarWidget {

    companion object {
        const val ID = "RemoteFlowStatusBar"
    }

    private var statusBar: StatusBar? = null
    private val panel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
    private val iconLabel = JLabel(RemoteFlowIcons.REMOTE_FLOW)
    private val textLabel = JLabel("Remote Flow: Disconnected")

    private var latestMetrics: MachineMetrics? = null
    private var statsPollerTask: ScheduledFuture<*>? = null
    private val statsManager = ServerStatsManager(project)

    init {
        panel.isOpaque = false
        panel.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        panel.add(iconLabel)
        panel.add(textLabel)
        updateStatus()

        panel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                showQuickPopup(e)
            }
        })

        project.messageBus.connect(this).subscribe(RemoteConnectionListener.TOPIC, object : RemoteConnectionListener {
            override fun connectionStateChanged(connected: Boolean, profile: ServerProfile) {
                ApplicationManager.getApplication().invokeLater {
                    updateStatus()
                    manageStatsPoller(connected)
                }
            }

            override fun profileChanged(profile: ServerProfile) {
                ApplicationManager.getApplication().invokeLater {
                    updateStatus()
                }
            }

            override fun processStateChanged(running: Boolean, command: String?) {
                ApplicationManager.getApplication().invokeLater {
                    updateStatus()
                }
            }
        })

        val connMgr = RemoteConnectionManager.getInstance(project)
        if (connMgr.isConnected) {
            manageStatsPoller(true)
        }
    }

    override fun ID(): String = ID

    override fun getComponent(): JComponent = panel

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
    }

    override fun dispose() {
        statsPollerTask?.cancel(true)
        statsPollerTask = null
        statusBar = null
    }

    private fun manageStatsPoller(start: Boolean) {
        statsPollerTask?.cancel(true)
        statsPollerTask = null

        if (start) {
            fetchStatsNow()
            statsPollerTask = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({
                val connMgr = RemoteConnectionManager.getInstance(project)
                if (connMgr.isConnected) {
                    fetchStatsNow()
                }
            }, 25, 25, TimeUnit.SECONDS)
        } else {
            latestMetrics = null
            updateStatus()
        }
    }

    private fun fetchStatsNow() {
        val connMgr = RemoteConnectionManager.getInstance(project)
        if (!connMgr.isConnected) return

        statsManager.fetchMetrics(
            onParsed = { metrics ->
                latestMetrics = metrics
                ApplicationManager.getApplication().invokeLater {
                    updateStatus()
                }
            },
            onLog = {},
            onComplete = {}
        )
    }

    private fun updateStatus() {
        val connMgr = RemoteConnectionManager.getInstance(project)
        val settings = RemoteFlowSettings.getInstance(project)
        val active = settings.activeProfileOrNull

        if (active == null) {
            iconLabel.icon = RemoteFlowIcons.CLOUD_DISCONNECTED
            textLabel.text = "Remote Flow: No server"
            textLabel.foreground = JBColor.GRAY
            panel.toolTipText = "Remote Flow: No server configured. Click to configure."
            statusBar?.updateWidget(ID)
            return
        }

        if (connMgr.isConnected) {
            iconLabel.icon = RemoteFlowIcons.CLOUD_CONNECTED
            val m = latestMetrics
            val statsInfo = if (m != null && m.ramPercent > 0) {
                " | CPU: ${m.cpuPercent}% | RAM: ${m.ramPercent}%"
            } else ""

            if (connMgr.isProcessRunning) {
                textLabel.text = "${active.name} [Running]$statsInfo"
                textLabel.foreground = JBColor(Color(13, 148, 136), Color(52, 211, 153)) // Vivid Emerald Green
                panel.toolTipText = "Remote Flow: Application is running on ${active.name}\n" +
                        (if (m != null) "CPU: ${m.cpuText} | RAM: ${m.ramText} (${m.ramPercent}%) | Disk: ${m.diskText}\n" else "") +
                        "Click for quick actions (Stop, Terminal, Logs)."
            } else {
                textLabel.text = "${active.name} [Connected]$statsInfo"
                textLabel.foreground = JBColor(Color(16, 185, 129), Color(16, 185, 129))
                panel.toolTipText = "Remote Flow: Connected to ${active.name} (${active.host}:${active.port})\n" +
                        (if (m != null) "CPU: ${m.cpuText} | RAM: ${m.ramText} (${m.ramPercent}%) | Disk: ${m.diskText}\n" else "") +
                        "Click for quick actions (Run, Debug, Sync)."
            }
        } else {
            iconLabel.icon = RemoteFlowIcons.CLOUD_DISCONNECTED
            textLabel.text = "${active.name} [Offline]"
            textLabel.foreground = JBColor.GRAY
            panel.toolTipText = "Remote Flow: Disconnected (${active.name}). Click to connect or switch server."
        }
        statusBar?.updateWidget(ID)
    }

    private fun showQuickPopup(e: MouseEvent) {
        val group = DefaultActionGroup()
        val connMgr = RemoteConnectionManager.getInstance(project)
        val settings = RemoteFlowSettings.getInstance(project)
        val syncMgr = FastSyncManager(project)

        if (settings.profiles.isNotEmpty()) {
            group.add(Separator.create("Active Server Switcher"))
            for ((idx, p) in settings.profiles.withIndex()) {
                val isActive = idx == settings.activeProfileIndex
                val prefix = if (isActive) "✓ " else "   "
                group.add(object : AnAction("$prefix${p.name} (${p.host})") {
                    override fun actionPerformed(e: AnActionEvent) {
                        if (settings.activeProfileIndex != idx) {
                            settings.activeProfileIndex = idx
                            updateStatus()
                            project.messageBus.syncPublisher(RemoteConnectionListener.TOPIC).profileChanged(p)
                        }
                    }
                })
            }

            val active = settings.activeProfile
            group.add(Separator.create("Process & Execution"))

            if (connMgr.isConnected) {
                if (connMgr.isProcessRunning) {
                    group.add(object : AnAction("🛑 Stop Running App (${active.name})", "Terminate current remote application", RemoteFlowIcons.REMOTE_STOP) {
                        override fun actionPerformed(e: AnActionEvent) {
                            connMgr.stopRemoteProcess(active)
                        }
                    })
                } else {
                    group.add(object : AnAction("▶ Remote Run (${active.name})", "Sync and run application", RemoteFlowIcons.REMOTE_RUN) {
                        override fun actionPerformed(e: AnActionEvent) {
                            ActionManager.getInstance().getAction("RemoteFlow.RunAction")?.actionPerformed(e)
                        }
                    })
                    group.add(object : AnAction("🐞 Remote Debug (Port 5005)", "Run application with JVM debug attach", RemoteFlowIcons.REMOTE_DEBUG) {
                        override fun actionPerformed(e: AnActionEvent) {
                            ActionManager.getInstance().getAction("RemoteFlow.DebugAction")?.actionPerformed(e)
                        }
                    })
                }

                latestMetrics?.let { m ->
                    group.add(Separator.create("Server Health: CPU ${m.cpuPercent}% | RAM ${m.ramPercent}% | Disk ${m.diskPercent}%"))
                }

                group.add(object : AnAction("📊 Remote Task Manager (CPU/RAM/Processes)...") {
                    override fun actionPerformed(e: AnActionEvent) {
                        RemoteTaskManagerDialog(project, active).show()
                    }
                })

                group.add(Separator.create("Connection Control"))
                group.add(object : AnAction("⏹ Disconnect (${active.name})") {
                    override fun actionPerformed(e: AnActionEvent) {
                        connMgr.disconnect()
                    }
                })
            } else {
                group.add(object : AnAction("⚡ Connect to ${active.name}") {
                    override fun actionPerformed(e: AnActionEvent) {
                        connMgr.connect(active, onSuccess = {
                            manageStatsPoller(true)
                        }, onError = {})
                    }
                })
            }

            group.add(Separator.create("Run Delegation"))
            val routeText = if (settings.routeStandardRunToRemote) "✓ Standard Run/Debug ➔ Remote Server" else "○ Standard Run/Debug ➔ Local PC"
            group.add(object : AnAction(routeText, "Toggle whether standard IDE Run/Debug (Shift+F10 / Shift+F9) runs on remote server or locally", null) {
                override fun actionPerformed(e: AnActionEvent) {
                    settings.routeStandardRunToRemote = !settings.routeStandardRunToRemote
                    val stateMsg = if (settings.routeStandardRunToRemote) "Standard Run will now execute on Remote Server (${active.name})" else "Standard Run will now execute locally on PC"
                    connMgr.notifyUser("Remote Flow", stateMsg, com.intellij.notification.NotificationType.INFORMATION)
                }
            })

            group.add(object : AnAction("🔄 Fast Sync All Files") {
                override fun actionPerformed(e: AnActionEvent) {
                    syncMgr.syncSingleServer(active, {}, { ok ->
                        if (ok) connMgr.notifyUser("Remote Flow", "Fast Sync completed!")
                    })
                }
            })
        } else {
            group.add(object : AnAction("➕ Add Server Profile...") {
                override fun actionPerformed(e: AnActionEvent) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, RemoteFlowConfigurable::class.java)
                }
            })
        }

        group.add(object : AnAction("🖥 Open Remote Flow Dashboard") {
            override fun actionPerformed(e: AnActionEvent) {
                ToolWindowManager.getInstance(project).getToolWindow("RemoteFlow")?.show()
            }
        })

        group.add(object : AnAction("📋 Show Execution Logs") {
            override fun actionPerformed(e: AnActionEvent) {
                uz.remote.flow.logging.RemoteFlowLogService.getInstance(project).showLogWindow()
            }
        })

        group.add(Separator.create())
        group.add(object : AnAction("⚙ Remote Flow Settings...") {
            override fun actionPerformed(e: AnActionEvent) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, RemoteFlowConfigurable::class.java)
            }
        })

        val popup = JBPopupFactory.getInstance().createActionGroupPopup(
            "Remote Flow Control",
            group,
            DataContext.EMPTY_CONTEXT,
            JBPopupFactory.ActionSelectionAid.MNEMONICS,
            true
        )
        popup.showUnderneathOf(panel)
    }
}
