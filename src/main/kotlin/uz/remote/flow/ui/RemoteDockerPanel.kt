package uz.remote.flow.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import uz.remote.flow.ssh.DockerContainer
import uz.remote.flow.ssh.ForwardDirection
import uz.remote.flow.ssh.PortMapping
import uz.remote.flow.ssh.RemoteConnectionManager
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.table.DefaultTableModel

class RemoteDockerPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val connectionManager = RemoteConnectionManager.getInstance(project)

    private val columns = arrayOf("ID", "Name", "Image", "Status", "Ports")
    private val tableModel = object : DefaultTableModel(columns, 0) {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }
    private val table = JBTable(tableModel).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        rowHeight = 24
    }

    private val btnRefresh = JButton("Refresh", AllIcons.Actions.Refresh)
    private val btnStart = JButton("Start", AllIcons.Actions.Execute)
    private val btnStop = JButton("Stop", AllIcons.Actions.Suspend)
    private val btnRestart = JButton("Restart", AllIcons.Actions.Restart)
    private val btnLogs = JButton("Logs", AllIcons.Nodes.LogFolder)
    private val btnForwardPort = JButton("Forward Port", AllIcons.General.Web)

    private val statusLabel = JBLabel("Ready")
    private var containerList: List<DockerContainer> = emptyList()

    init {
        border = JBUI.Borders.empty(4)

        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4))
        toolbar.add(btnRefresh)
        toolbar.add(btnStart)
        toolbar.add(btnStop)
        toolbar.add(btnRestart)
        toolbar.add(btnLogs)
        toolbar.add(btnForwardPort)

        btnRefresh.addActionListener { loadContainers() }
        btnStart.addActionListener { actionOnSelected("start") }
        btnStop.addActionListener { actionOnSelected("stop") }
        btnRestart.addActionListener { actionOnSelected("restart") }
        btnLogs.addActionListener { viewSelectedLogs() }
        btnForwardPort.addActionListener { forwardSelectedPort() }

        val topPanel = JPanel(BorderLayout())
        topPanel.add(toolbar, BorderLayout.WEST)
        topPanel.add(statusLabel, BorderLayout.EAST)

        add(topPanel, BorderLayout.NORTH)
        add(JBScrollPane(table), BorderLayout.CENTER)
    }

    fun loadContainers() {
        if (!connectionManager.isConnected) {
            statusLabel.text = "Not connected to server"
            tableModel.rowCount = 0
            return
        }

        statusLabel.text = "Loading containers..."
        btnRefresh.isEnabled = false

        connectionManager.listDockerContainers { containers ->
            ApplicationManager.getApplication().invokeLater {
                btnRefresh.isEnabled = true
                containerList = containers
                tableModel.rowCount = 0
                for (c in containers) {
                    tableModel.addRow(arrayOf(c.id, c.names, c.image, c.status, c.ports))
                }
                statusLabel.text = if (containers.isEmpty()) "No Docker containers found" else "${containers.size} container(s)"
            }
        }
    }

    private fun getSelectedContainer(): DockerContainer? {
        val row = table.selectedRow
        if (row < 0 || row >= containerList.size) return null
        return containerList[row]
    }

    private fun actionOnSelected(action: String) {
        val c = getSelectedContainer()
        if (c == null) {
            Messages.showWarningDialog(project, "Please select a Docker container from the list.", "No Container Selected")
            return
        }

        statusLabel.text = "$action ${c.names}..."
        when (action) {
            "start" -> connectionManager.startDockerContainer(c.id) { ok, msg ->
                handleActionResult(ok, msg, "Started container ${c.names}")
            }
            "stop" -> connectionManager.stopDockerContainer(c.id) { ok, msg ->
                handleActionResult(ok, msg, "Stopped container ${c.names}")
            }
            "restart" -> connectionManager.restartDockerContainer(c.id) { ok, msg ->
                handleActionResult(ok, msg, "Restarted container ${c.names}")
            }
        }
    }

    private fun handleActionResult(ok: Boolean, msg: String, successText: String) {
        ApplicationManager.getApplication().invokeLater {
            if (ok) {
                statusLabel.text = successText
                loadContainers()
            } else {
                statusLabel.text = "Action failed: $msg"
                Messages.showErrorDialog(project, msg.ifBlank { "Docker command failed." }, "Error")
            }
        }
    }

    private fun viewSelectedLogs() {
        val c = getSelectedContainer() ?: return
        statusLabel.text = "Fetching logs for ${c.names}..."
        connectionManager.getDockerContainerLogs(c.id, 150) { logs ->
            ApplicationManager.getApplication().invokeLater {
                statusLabel.text = "Logs fetched"
                val area = com.intellij.ui.components.JBTextArea(logs).apply {
                    isEditable = false
                    rows = 25
                    columns = 70
                }
                val scroll = JBScrollPane(area)
                val panel = JPanel(BorderLayout()).apply {
                    add(scroll, BorderLayout.CENTER)
                }
                com.intellij.openapi.ui.DialogBuilder(project).apply {
                    setTitle("Docker Logs: ${c.names} (${c.image})")
                    setCenterPanel(panel)
                    addCloseButton()
                    show()
                }
            }
        }
    }

    private fun forwardSelectedPort() {
        val c = getSelectedContainer() ?: return
        val portsStr = c.ports
        // Parse port e.g. 0.0.0.0:5432->5432/tcp or 6379/tcp
        val portRegex = Regex("""(\d+)(?:/tcp|/udp)?""")
        val match = portRegex.find(portsStr)
        val defaultPort = match?.groupValues?.get(1)?.toIntOrNull() ?: 8080

        val input = Messages.showInputDialog(
            project,
            "Enter local port to forward for container '${c.names}':",
            "Forward Container Port",
            Messages.getQuestionIcon(),
            defaultPort.toString(),
            null
        ) ?: return

        val localP = input.trim().toIntOrNull() ?: return
        val profile = connectionManager.config.activeProfile

        if (profile.forwardedPorts.none { it.localPort == localP }) {
            profile.forwardedPorts.add(
                PortMapping(
                    localPort = localP,
                    remotePort = defaultPort,
                    serviceName = "Docker: ${c.names}",
                    isForwarded = true,
                    direction = ForwardDirection.LOCAL_TO_REMOTE
                )
            )
            connectionManager.startSingleForward(profile.forwardedPorts.last())
            Messages.showInfoMessage(
                project,
                "Forwarded container port $defaultPort to localhost:$localP!\nAccess at: localhost:$localP",
                "Port Forwarded"
            )
        } else {
            Messages.showInfoMessage(project, "Port $localP is already forwarded.", "Port Forwarding")
        }
    }
}
