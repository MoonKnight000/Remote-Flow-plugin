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
import uz.remote.flow.settings.RemoteFlowConfigurable
import uz.remote.flow.settings.RemoteFlowSettings
import uz.remote.flow.ssh.RemoteConnectionListener
import uz.remote.flow.ssh.RemoteConnectionManager
import uz.remote.flow.ssh.ServerProfile
import uz.remote.flow.sync.FastSyncManager
import java.awt.Color
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
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
                ApplicationManager.getApplication().invokeLater { updateStatus() }
            }

            override fun profileChanged(profile: ServerProfile) {
                ApplicationManager.getApplication().invokeLater { updateStatus() }
            }
        })
    }

    override fun ID(): String = ID

    override fun getComponent(): JComponent = panel

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
    }

    override fun dispose() {
        statusBar = null
    }

    private fun updateStatus() {
        val connMgr = RemoteConnectionManager.getInstance(project)
        val settings = RemoteFlowSettings.getInstance(project)
        val active = settings.activeProfile

        if (connMgr.isConnected) {
            textLabel.text = "${active.name} ●"
            textLabel.foreground = JBColor(Color(16, 185, 129), Color(16, 185, 129))
            panel.toolTipText = "Remote Flow: Connected to ${active.name} (${active.host}:${active.port}). Click for quick actions."
        } else {
            textLabel.text = "${active.name} ○"
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

        group.add(Separator.create("Quick Control"))
        if (connMgr.isConnected) {
            group.add(object : AnAction("⏹ Disconnect (${settings.activeProfile.name})") {
                override fun actionPerformed(e: AnActionEvent) {
                    connMgr.disconnect()
                }
            })
        } else {
            group.add(object : AnAction("⚡ Connect to ${settings.activeProfile.name}") {
                override fun actionPerformed(e: AnActionEvent) {
                    connMgr.connect(settings.activeProfile, {}, {})
                }
            })
        }

        group.add(object : AnAction("🔄 Fast Sync All Files") {
            override fun actionPerformed(e: AnActionEvent) {
                syncMgr.syncSingleServer(settings.activeProfile, {}, { ok ->
                    if (ok) connMgr.notifyUser("Remote Flow", "Fast Sync yakunlandi!")
                })
            }
        })

        group.add(object : AnAction("🖥 Open Remote Flow Dashboard") {
            override fun actionPerformed(e: AnActionEvent) {
                ToolWindowManager.getInstance(project).getToolWindow("RemoteFlow")?.show()
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
