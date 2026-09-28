package uz.remote.flow.ssh

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.PROJECT)
class RemoteConnectionManager(private val project: Project) {

    private var sshClient: SSHClient? = null
    private val executor = Executors.newCachedThreadPool()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val activeTunnels = ConcurrentHashMap<Int, ServerSocket>()

    val settings get() = uz.remote.flow.settings.RemoteFlowSettings.getInstance(project)
    val config get() = settings.config

    private var heartbeatTask: ScheduledFuture<*>? = null
    private val isReconnecting = AtomicBoolean(false)
    var autoReconnectEnabled: Boolean
        get() = settings.autoReconnect
        set(value) { settings.autoReconnect = value }

    val isConnected: Boolean
        get() = sshClient?.isConnected == true && sshClient?.isAuthenticated == true

    fun testConnection(profile: ServerProfile = config.activeProfile, onResult: (Boolean, String) -> Unit) {
        executor.submit {
            var tempClient: SSHClient? = null
            try {
                tempClient = SSHClient()
                tempClient.addHostKeyVerifier(PromiscuousVerifier())
                tempClient.connect(profile.host, profile.port)

                when (profile.authType) {
                    AuthType.PASSWORD -> tempClient.authPassword(profile.user, profile.password)
                    AuthType.PRIVATE_KEY -> {
                        val keyProvider = tempClient.loadKeys(profile.privateKeyPath)
                        tempClient.authPublickey(profile.user, keyProvider)
                    }
                }

                val ok = tempClient.isConnected && tempClient.isAuthenticated
                tempClient.disconnect()
                tempClient.close()

                if (ok) onResult(true, "Ulanish muvaffaqiyatli! Server tayyor: " + profile.name)
                else onResult(false, "Autentifikatsiya rad etildi. Login/parolni tekshiring.")
            } catch (e: Exception) {
                try { tempClient?.disconnect(); tempClient?.close() } catch (_: Exception) {}
                onResult(false, "Ulanib bo'lmadi: " + (e.message ?: e.toString()))
            }
        }
    }

    fun connect(profile: ServerProfile = config.activeProfile, onSuccess: () -> Unit, onError: (Throwable) -> Unit) {
        executor.submit {
            try {
                disconnect()

                val client = SSHClient()
                client.addHostKeyVerifier(PromiscuousVerifier())
                client.connect(profile.host, profile.port)

                when (profile.authType) {
                    AuthType.PASSWORD -> client.authPassword(profile.user, profile.password)
                    AuthType.PRIVATE_KEY -> {
                        val keyProvider = client.loadKeys(profile.privateKeyPath)
                        client.authPublickey(profile.user, keyProvider)
                    }
                }

                sshClient = client
                startPortForwarding(profile)
                startKeepAliveWatchdog()

                notifyUser("Remote Flow: Ulangan", "Serverga ulanish muvaffaqiyatli o'rnatildi: " + profile.name, NotificationType.INFORMATION)
                project.messageBus.syncPublisher(RemoteConnectionListener.TOPIC).connectionStateChanged(true, profile)
                onSuccess()
            } catch (e: Exception) {
                disconnect()
                project.messageBus.syncPublisher(RemoteConnectionListener.TOPIC).connectionStateChanged(false, profile)
                onError(e)
            }
        }
    }

    private fun startKeepAliveWatchdog() {
        heartbeatTask?.cancel(true)
        heartbeatTask = scheduler.scheduleAtFixedRate({
            if (isConnected) {
                try {
                    val s = sshClient?.startSession()
                    s?.close()
                } catch (_: Exception) {
                    if (autoReconnectEnabled && !isReconnecting.get()) {
                        triggerAutoReconnect()
                    }
                }
            } else if (autoReconnectEnabled && !isReconnecting.get()) {
                triggerAutoReconnect()
            }
        }, 20, 20, TimeUnit.SECONDS)
    }

    private fun triggerAutoReconnect() {
        if (isReconnecting.compareAndSet(false, true)) {
            notifyUser("Remote Flow: Qayta ulanmoqda", "Aloqa uzildi. Avtomatik qayta ulanish boshlandi...", NotificationType.WARNING)
            val profile = config.activeProfile
            connect(
                profile = profile,
                onSuccess = {
                    isReconnecting.set(false)
                    notifyUser("Remote Flow: Tiklandi", "Server bilan aloqa avtomatik tiklandi!", NotificationType.INFORMATION)
                },
                onError = {
                    isReconnecting.set(false)
                }
            )
        }
    }

    fun startPortForwarding(profile: ServerProfile = config.activeProfile) {
        val client = sshClient ?: return
        if (!client.isConnected) return

        for (portMap in profile.forwardedPorts) {
            if (activeTunnels.containsKey(portMap.localPort)) continue

            executor.submit {
                try {
                    val serverSocket = ServerSocket()
                    serverSocket.reuseAddress = true
                    serverSocket.bind(InetSocketAddress("127.0.0.1", portMap.localPort))
                    activeTunnels[portMap.localPort] = serverSocket
                    portMap.isForwarded = true

                    val params = Parameters("127.0.0.1", portMap.localPort, "127.0.0.1", portMap.remotePort)
                    client.newLocalPortForwarder(params, serverSocket).listen()
                } catch (e: Exception) {
                    portMap.isForwarded = false
                }
            }
        }
    }

    fun executeRemoteCommand(
        cmd: String,
        workingDir: String = config.activeProfile.remoteProjectPath,
        onOutput: (String) -> Unit,
        onComplete: (Int) -> Unit
    ) {
        executor.submit {
            val client = sshClient
            if (client == null || !client.isConnected) {
                onOutput("[ERROR] SSH connection is not open. Connect first!\n")
                onComplete(-1)
                return@submit
            }

            try {
                val fullCmd = if (workingDir.isNotBlank()) "cd " + workingDir + " && " + cmd else cmd
                val session = client.startSession()
                session.allocateDefaultPTY()
                val command = session.exec(fullCmd)

                BufferedReader(InputStreamReader(command.inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        onOutput(line + "\n")
                    }
                }

                command.join()
                val exitStatus = command.exitStatus ?: 0
                session.close()
                onComplete(exitStatus)
            } catch (e: Exception) {
                onOutput("[ERROR]: " + e.message + "\n")
                onComplete(-1)
            }
        }
    }

    fun notifyUser(title: String, message: String, type: NotificationType = NotificationType.INFORMATION) {
        try {
            val notification = Notification("Remote Flow", title, message, type)
            Notifications.Bus.notify(notification, project)
        } catch (_: Exception) {}
    }

    fun disconnect() {
        heartbeatTask?.cancel(true)
        heartbeatTask = null

        activeTunnels.forEach { (_, socket) ->
            try {
                socket.close()
            } catch (_: Exception) {}
        }
        activeTunnels.clear()

        config.activeProfile.forwardedPorts.forEach { it.isForwarded = false }

        try {
            sshClient?.disconnect()
            sshClient?.close()
        } catch (_: Exception) {}
        sshClient = null

        project.messageBus.syncPublisher(RemoteConnectionListener.TOPIC).connectionStateChanged(false, config.activeProfile)
    }

    companion object {
        fun getInstance(project: Project): RemoteConnectionManager =
            project.getService(RemoteConnectionManager::class.java)
    }
}
