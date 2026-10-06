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
                    manageStatsPoller(RemoteConnectionManager.getInstance(project).isConnected)
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

        val settings = RemoteFlowSettings.getInstance(project)
        val active = settings.activeProfileOrNull
        val isMonitoringEnabled = active != null && active.monitorMode != "OFF"

        if (start && isMonitoringEnabled) {
            fetchStatsNow()
            val isLive = active?.monitorMode != "MANUAL"
            if (isLive) {
                val interval = if (active?.monitorMode == "REALTIME_5S") 15L else 10L
                statsPollerTask = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({
                    val connMgr = RemoteConnectionManager.getInstance(project)
                    val curActive = RemoteFlowSettings.getInstance(project).activeProfileOrNull
                    if (connMgr.isConnected && curActive?.monitorMode != "OFF" && curActive?.monitorMode != "MANUAL") {
                        fetchStatsNow()
                    }
                }, interval, interval, TimeUnit.SECONDS)
            }
        } else {
            latestMetrics = null
            updateStatus()
        }
    }

    private fun fetchStatsNow() {
        val connMgr = RemoteConnectionManager.getInstance(project)
        val settings = RemoteFlowSettings.getInstance(project)
        val active = settings.activeProfileOrNull
        if (!connMgr.isConnected || active == null || active.monitorMode == "OFF") {
            latestMetrics = null
            ApplicationManager.getApplication().invokeLater { updateStatus() }
            return
        }

        statsManager.fetchMetrics(
            onParsed = { metrics ->
                val curActive = RemoteFlowSettings.getInstance(project).activeProfileOrNull
                if (curActive?.monitorMode != "OFF") {
                    latestMetrics = metrics
                } else {
                    latestMetrics = null
                }
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

        val isMonitoringEnabled = active.monitorMode != "OFF"
        if (!isMonitoringEnabled) {
            latestMetrics = null
        }

        if (connMgr.isConnected) {
            iconLabel.icon = RemoteFlowIcons.CLOUD_CONNECTED
            val m = if (isMonitoringEnabled) latestMetrics else null
            val statsInfo = if (m != null && m.ramPercent > 0) {
                " | CPU: ${m.cpuPercent}% | RAM: ${m.ramPercent}%"
            } else ""

            val isCritical = uz.remote.flow.system.ServerHealthAlertManager.isCritical(m)
            val alertPrefix = if (isCritical) "⚠️ " else ""
            val envBadge = "[${active.environment.displayName}]"

            val metricsTip = if (m != null) "CPU: ${m.cpuText} | RAM: ${m.ramText} (${m.ramPercent}%) | Disk: ${m.diskText}\n" else ""
            val alertTip = uz.remote.flow.system.ServerHealthAlertManager.getAlertSummary(m)?.let { "$it\n" } ?: ""

            if (connMgr.isProcessRunning) {
                textLabel.text = "$alertPrefix$envBadge ${active.name} [Running]$statsInfo"
                textLabel.foreground = if (isCritical) JBColor(Color(239, 68, 68), Color(248, 113, 113)) else JBColor(Color(13, 148, 136), Color(52, 211, 153))
                panel.toolTipText = alertTip + "Remote Flow: Application is running on ${active.name}\n" +
                        metricsTip +
                        "Click for quick actions (Stop, Terminal, Logs)."
            } else {
                textLabel.text = "$alertPrefix$envBadge ${active.name} [Connected]$statsInfo"
                textLabel.foreground = if (isCritical) JBColor(Color(239, 68, 68), Color(248, 113, 113)) else JBColor(Color(16, 185, 129), Color(16, 185, 129))
                panel.toolTipText = alertTip + "Remote Flow: Connected to ${active.name} (${active.host}:${active.port})\n" +
                        metricsTip +
                        "Click for quick actions (Run, Debug, Sync)."
            }
        } else {
            iconLabel.icon = RemoteFlowIcons.CLOUD_DISCONNECTED
            val envBadge = "[${active.environment.displayName}]"
            textLabel.text = "$envBadge ${active.name} [Offline]"
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
                    group.add(object : AnAction("🔄 Rerun App (${active.name})", "Restart running application (stop, sync and run again)", com.intellij.icons.AllIcons.Actions.Restart) {
                        override fun actionPerformed(e: AnActionEvent) {
                            ActionManager.getInstance().getAction("RemoteFlow.RunAction")?.actionPerformed(e)
                        }
                    })
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

                group.add(object : AnAction("⚡ Hot Reload Current File (Alt+Shift+H)", "Fast recompile and reload class on server without restart", com.intellij.icons.AllIcons.Actions.Compile) {
                    override fun actionPerformed(e: AnActionEvent) {
                        uz.remote.flow.reload.RemoteHotReloadManager.hotReloadCurrentFile(project)
                    }
                })

                group.add(object : AnAction("💻 Open Remote SSH Terminal", "Open interactive SSH shell in IntelliJ Terminal", com.intellij.icons.AllIcons.Nodes.Console) {
                    override fun actionPerformed(e: AnActionEvent) {
                        uz.remote.flow.terminal.RemoteTerminalHelper.openTerminal(project, active)
                    }
                })

                val memLabel = if (active.maxHeapSize.isNotBlank()) " (-Xmx${active.maxHeapSize})" else ""
                group.add(object : AnAction("🧠 Tune Remote Memory$memLabel...", "Configure remote JVM heap and memory limits", com.intellij.icons.AllIcons.Actions.Profile) {
                    override fun actionPerformed(e: AnActionEvent) {
                        uz.remote.flow.memory.RemoteMemoryTunerDialog(project, active).show()
                    }
                })

                group.add(object : AnAction("⚡ Performance Profiler (Alt+Shift+P)...", "Profile CPU & Memory performance on remote server", com.intellij.icons.AllIcons.Actions.Profile) {
                    override fun actionPerformed(e: AnActionEvent) {
                        val profiler = uz.remote.flow.profiler.RemoteProfilerManager.getInstance(project)
                        if (connMgr.isProcessRunning) {
                            if (profiler.currentState == uz.remote.flow.profiler.ProfilerState.RECORDING) {
                                profiler.stopRecording()
                            } else {
                                profiler.startRecording(profiler.selectedMode)
                            }
                        } else if (profiler.lastSnapshot != null) {
                            uz.remote.flow.ui.RemoteProfilerResultsDialog(project, profiler.lastSnapshot!!).show()
                        } else {
                            connMgr.notifyUser(
                                "Remote Profiler",
                                "Run application first (Alt+Shift+R) to monitor live performance and record profiles.",
                                com.intellij.notification.NotificationType.INFORMATION
                            )
                        }
                    }
                })

                val isMonitoring = active.monitorMode != "OFF"
                if (isMonitoring) {
                    latestMetrics?.let { m ->
                        group.add(Separator.create("Server Health: CPU ${m.cpuPercent}% | RAM ${m.ramPercent}% | Disk ${m.diskPercent}%"))
                    }
                }

                group.add(object : AnAction(if (isMonitoring) "🚫 Turn Resource Monitoring OFF" else "⚡ Turn Resource Monitoring ON") {
                    override fun actionPerformed(e: AnActionEvent) {
                        active.monitorMode = if (isMonitoring) "OFF" else "REALTIME_3S"
                        project.messageBus.syncPublisher(RemoteConnectionListener.TOPIC).profileChanged(active)
                    }
                })

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

            group.add(Separator.create("Code Synchronization"))
            group.add(object : AnAction("🔍 Preview Sync Diff (Dry Run)...", "Review changed files before uploading", com.intellij.icons.AllIcons.Actions.Diff) {
                override fun actionPerformed(e: AnActionEvent) {
                    uz.remote.flow.actions.RemoteFlowSyncPreviewAction.openPreviewDialog(project)
                }
            })
            group.add(object : AnAction("🔄 Fast Sync All Files") {
                override fun actionPerformed(e: AnActionEvent) {
                    if (!uz.remote.flow.ssh.RemoteSafetyHelper.checkProductionSafe(project, active, "Full Project Sync")) {
                        return
                    }
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
