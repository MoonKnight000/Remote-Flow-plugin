package uz.remote.flow.ssh

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.connection.channel.forwarded.RemotePortForwarder
import net.schmizz.sshj.connection.channel.forwarded.SocketForwardingConnectListener
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
    private val activeRemoteForwards = ConcurrentHashMap<Int, RemotePortForwarder.Forward>()

    val settings get() = uz.remote.flow.settings.RemoteFlowSettings.getInstance(project)
    val config get() = settings.config

    private var heartbeatTask: ScheduledFuture<*>? = null
    private val isReconnecting = AtomicBoolean(false)
    var autoReconnectEnabled: Boolean
        get() = settings.autoReconnect
        set(value) { settings.autoReconnect = value }

    val isConnected: Boolean
        get() = sshClient?.isConnected == true && sshClient?.isAuthenticated == true

    @Volatile var isProcessRunning: Boolean = false
        private set
    @Volatile var runningCommand: String? = null
        private set
    @Volatile private var activeSession: net.schmizz.sshj.connection.channel.direct.Session? = null
    @Volatile private var activeCommand: net.schmizz.sshj.connection.channel.direct.Session.Command? = null
    @Volatile var activeProcessHandler: uz.remote.flow.execution.RemoteFlowProcessHandler? = null
    @Volatile var activeAppPort: Int? = null

    fun sendProcessInput(data: ByteArray) {
        try {
            activeCommand?.outputStream?.let { os ->
                os.write(data)
                os.flush()
            }
        } catch (_: Exception) {}
    }

    fun getActiveSshClient(): SSHClient? = if (isConnected) sshClient else null

    fun <T> withSshClient(
        profile: ServerProfile = config.activeProfile,
        action: (client: SSHClient) -> T
    ): T {
        val active = if (isConnected && sshClient != null && sshClient?.isConnected == true) {
            sshClient
        } else null

        var tempClient: SSHClient? = null
        return try {
            val client = active ?: run {
                tempClient = SSHClient().apply {
                    addHostKeyVerifier(PromiscuousVerifier())
                    connect(profile.host, profile.port)
                    when (profile.authType) {
                        AuthType.PASSWORD -> authPassword(profile.user, profile.password)
                        AuthType.PRIVATE_KEY -> authPublickey(profile.user, loadKeys(profile.privateKeyPath))
                    }
                }
                tempClient!!
            }
            action(client)
        } finally {
            try { tempClient?.disconnect(); tempClient?.close() } catch (_: Exception) {}
        }
    }

    fun testConnection(
        profile: ServerProfile = config.activeProfile,
        checkRemoteDir: String? = null,
        onResult: (ok: Boolean, dirExists: Boolean, msg: String) -> Unit
    ) {
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
                var dirExists = true

                if (ok && !checkRemoteDir.isNullOrBlank()) {
                    try {
                        val session = tempClient.startSession()
                        val cmd = session.exec("test -d \"$checkRemoteDir\"")
                        cmd.join(5, TimeUnit.SECONDS)
                        dirExists = (cmd.exitStatus == 0)
                        session.close()
                    } catch (_: Exception) {
                        dirExists = false
                    }
                }

                tempClient.disconnect()
                tempClient.close()

                if (ok) onResult(true, dirExists, "Connection successful! Server ready: " + profile.name)
                else onResult(false, false, "Authentication failed. Please check username/password or key.")
            } catch (e: Exception) {
                try { tempClient?.disconnect(); tempClient?.close() } catch (_: Exception) {}
                onResult(false, false, "Failed to connect: " + (e.message ?: e.toString()))
            }
        }
    }

    fun testConnection(profile: ServerProfile = config.activeProfile, onResult: (Boolean, String) -> Unit) {
        testConnection(profile, checkRemoteDir = null) { ok, _, msg ->
            onResult(ok, msg)
        }
    }

    fun checkDirectoryExists(
        profile: ServerProfile = config.activeProfile,
        dirPath: String,
        onResult: (exists: Boolean, error: String?) -> Unit
    ) {
        executor.submit {
            val client = if (isConnected && sshClient != null && sshClient?.isConnected == true) {
                sshClient
            } else null

            var tempClient: SSHClient? = null
            try {
                val active = client ?: run {
                    tempClient = SSHClient().apply {
                        addHostKeyVerifier(PromiscuousVerifier())
                        connect(profile.host, profile.port)
                        when (profile.authType) {
                            AuthType.PASSWORD -> authPassword(profile.user, profile.password)
                            AuthType.PRIVATE_KEY -> authPublickey(profile.user, loadKeys(profile.privateKeyPath))
                        }
                    }
                    tempClient!!
                }

                val session = active.startSession()
                val cmd = session.exec("test -d \"$dirPath\"")
                cmd.join(5, TimeUnit.SECONDS)
                val status = cmd.exitStatus ?: -1
                session.close()
                onResult(status == 0, null)
            } catch (e: Exception) {
                onResult(false, e.message ?: e.toString())
            } finally {
                try { tempClient?.disconnect(); tempClient?.close() } catch (_: Exception) {}
            }
        }
    }

    fun createDirectory(
        profile: ServerProfile = config.activeProfile,
        dirPath: String,
        onResult: (success: Boolean, error: String?) -> Unit
    ) {
        executor.submit {
            val client = if (isConnected && sshClient != null && sshClient?.isConnected == true) {
                sshClient
            } else null

            var tempClient: SSHClient? = null
            try {
                val active = client ?: run {
                    tempClient = SSHClient().apply {
                        addHostKeyVerifier(PromiscuousVerifier())
                        connect(profile.host, profile.port)
                        when (profile.authType) {
                            AuthType.PASSWORD -> authPassword(profile.user, profile.password)
                            AuthType.PRIVATE_KEY -> authPublickey(profile.user, loadKeys(profile.privateKeyPath))
                        }
                    }
                    tempClient!!
                }

                val session = active.startSession()
                val cmd = session.exec("mkdir -p \"$dirPath\"")
                cmd.join(10, TimeUnit.SECONDS)
                val status = cmd.exitStatus ?: -1
                session.close()
                onResult(status == 0, if (status == 0) null else "Server error: exit status $status")
            } catch (e: Exception) {
                onResult(false, e.message ?: e.toString())
            } finally {
                try { tempClient?.disconnect(); tempClient?.close() } catch (_: Exception) {}
            }
        }
    }

    fun uploadFile(
        profile: ServerProfile = config.activeProfile,
        localFile: java.io.File,
        remotePath: String,
        onProgress: (String) -> Unit,
        onComplete: (Boolean) -> Unit
    ) {
        executor.submit {
            val client = if (isConnected && sshClient != null && sshClient?.isConnected == true) {
                sshClient
            } else null

            var tempClient: SSHClient? = null
            try {
                val active = client ?: run {
                    tempClient = SSHClient().apply {
                        addHostKeyVerifier(PromiscuousVerifier())
                        connect(profile.host, profile.port)
                        when (profile.authType) {
                            AuthType.PASSWORD -> authPassword(profile.user, profile.password)
                            AuthType.PRIVATE_KEY -> authPublickey(profile.user, loadKeys(profile.privateKeyPath))
                        }
                    }
                    tempClient!!
                }

                val sftp = active.newSFTPClient()
                try {
                    val cleanRemote = remotePath.replace('\\', '/')
                    val parent = cleanRemote.substringBeforeLast('/')
                    if (parent.isNotBlank()) {
                        try { sftp.mkdirs(parent) } catch (_: Exception) {}
                    }
                    sftp.put(localFile.absolutePath, cleanRemote)
                } finally {
                    try { sftp.close() } catch (_: Exception) {}
                }

                onComplete(true)
            } catch (e: Exception) {
                onProgress("[SFTP ERROR] " + (e.message ?: e.toString()) + "\n")
                onComplete(false)
            } finally {
                try { tempClient?.disconnect(); tempClient?.close() } catch (_: Exception) {}
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

                notifyUser("Remote Flow: Connected", "Connected successfully to " + profile.name, NotificationType.INFORMATION)
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
            notifyUser("Remote Flow: Reconnecting", "Connection lost. Automatically reconnecting...", NotificationType.WARNING)
            val profile = config.activeProfile
            connect(
                profile = profile,
                onSuccess = {
                    isReconnecting.set(false)
                    notifyUser("Remote Flow: Restored", "Connection to server automatically restored!", NotificationType.INFORMATION)
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
            // Do not permanently forward backend application ports on SSH connection.
            // Application ports are forwarded dynamically and on-demand only when the application is running.
            if (isAppPort(portMap)) {
                continue
            }
            startSingleForward(portMap)
        }
    }

    private fun isAppPort(portMap: PortMapping): Boolean {
        val name = portMap.serviceName.lowercase()
        return name.contains("backend") || name == "app" || name.startsWith("app (") ||
                (portMap.localPort == 8080 && portMap.remotePort == 8080 && (name.contains("backend") || name.contains("app") || name == "service"))
    }

    /**
     * Dynamically forwards the application port (e.g. 9789 -> 9789) when the application runs.
     * Closes any previous dynamic application port first so no obsolete tunnels linger.
     */
    fun forwardAppPort(port: Int): Boolean {
        val client = sshClient ?: return false
        if (!client.isConnected) return false
        if (port <= 0 || port > 65535) return false

        val current = activeAppPort
        if (current == port && activeTunnels.containsKey(port)) {
            return true
        }

        if (current != null && current != port) {
            stopAppPortForward()
        }

        activeAppPort = port
        val mapping = PortMapping(
            localPort = port,
            remotePort = port,
            serviceName = "App ($port)",
            isForwarded = true,
            direction = ForwardDirection.LOCAL_TO_REMOTE
        )
        startSingleForward(mapping)
        return true
    }

    /**
     * Stops and closes the dynamic application port tunnel immediately, releasing local socket resources.
     */
    fun stopAppPortForward() {
        val port = activeAppPort ?: return
        activeAppPort = null
        val mapping = PortMapping(
            localPort = port,
            remotePort = port,
            serviceName = "App ($port)",
            direction = ForwardDirection.LOCAL_TO_REMOTE
        )
        stopSingleForward(mapping)
    }

    fun startSingleForward(portMap: PortMapping) {
        val client = sshClient ?: return
        if (!client.isConnected) return

        if (portMap.direction == ForwardDirection.LOCAL_TO_REMOTE) {
            if (activeTunnels.containsKey(portMap.localPort)) {
                portMap.isForwarded = true
                return
            }

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
                    activeTunnels.remove(portMap.localPort)?.let {
                        try { it.close() } catch (_: Exception) {}
                    }
                }
            }
        } else {
            // ForwardDirection.REMOTE_TO_LOCAL (Host -> Local, ssh -R)
            if (activeRemoteForwards.containsKey(portMap.remotePort)) {
                portMap.isForwarded = true
                return
            }

            executor.submit {
                try {
                    val forward = RemotePortForwarder.Forward(portMap.remotePort)
                    val listener = SocketForwardingConnectListener(InetSocketAddress("127.0.0.1", portMap.localPort))
                    client.remotePortForwarder.bind(forward, listener)
                    activeRemoteForwards[portMap.remotePort] = forward
                    portMap.isForwarded = true
                } catch (e: Exception) {
                    portMap.isForwarded = false
                    activeRemoteForwards.remove(portMap.remotePort)
                }
            }
        }
    }

    fun stopSingleForward(portMap: PortMapping) {
        if (portMap.direction == ForwardDirection.LOCAL_TO_REMOTE) {
            activeTunnels.remove(portMap.localPort)?.let { socket ->
                try { socket.close() } catch (_: Exception) {}
            }
        } else {
            activeRemoteForwards.remove(portMap.remotePort)?.let { forward ->
                try { sshClient?.remotePortForwarder?.cancel(forward) } catch (_: Exception) {}
            }
        }
        portMap.isForwarded = false
    }

    fun restartAllTunnels(profile: ServerProfile = config.activeProfile) {
        activeTunnels.forEach { (_, socket) ->
            try { socket.close() } catch (_: Exception) {}
        }
        activeTunnels.clear()

        val client = sshClient
        activeRemoteForwards.forEach { (_, forward) ->
            try { client?.remotePortForwarder?.cancel(forward) } catch (_: Exception) {}
        }
        activeRemoteForwards.clear()

        profile.forwardedPorts.forEach { it.isForwarded = false }
        startPortForwarding(profile)
    }

    fun executeRemoteCommand(
        cmd: String,
        workingDir: String = config.activeProfile.remoteProjectPath,
        onOutput: (String) -> Unit,
        onComplete: (Int) -> Unit
    ) = executeRemoteCommand(cmd, workingDir, false, onOutput, onComplete)

    fun executeRemoteCommand(
        cmd: String,
        workingDir: String,
        isLongRunning: Boolean,
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
                val fullCmd = if (workingDir.isNotBlank()) "cd \"$workingDir\" && $cmd" else cmd
                val session = client.startSession()
                session.allocateDefaultPTY()
                val command = session.exec(fullCmd)

                if (isLongRunning) {
                    isProcessRunning = true
                    runningCommand = cmd
                    activeSession = session
                    activeCommand = command
                    com.intellij.ide.ActivityTracker.getInstance().inc()
                    try {
                        project.messageBus.syncPublisher(RemoteConnectionListener.TOPIC).processStateChanged(true, cmd)
                    } catch (_: Exception) {}
                }

                BufferedReader(InputStreamReader(command.inputStream, java.nio.charset.StandardCharsets.UTF_8)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val raw = line ?: ""
                        val parts = raw.split('\r')
                        for (part in parts) {
                            val cleaned = cleanProgressRemnants(part)
                            if (cleaned.isNotBlank()) {
                                onOutput(cleaned + "\n")
                            }
                        }
                    }
                }

                command.join()
                val exitStatus = command.exitStatus ?: 0
                try { session.close() } catch (_: Exception) {}
                onComplete(exitStatus)
            } catch (e: Exception) {
                onOutput("[ERROR]: " + (e.message ?: e.toString()) + "\n")
                onComplete(-1)
            } finally {
                if (isLongRunning) {
                    stopAppPortForward()
                    isProcessRunning = false
                    runningCommand = null
                    activeSession = null
                    activeCommand = null
                    com.intellij.ide.ActivityTracker.getInstance().inc()
                    try {
                        project.messageBus.syncPublisher(RemoteConnectionListener.TOPIC).processStateChanged(false, null)
                    } catch (_: Exception) {}
                    activeProcessHandler?.finishProcess(0)
                    activeProcessHandler = null
                }
            }
        }
    }

    fun stopRemoteProcess(
        profile: ServerProfile = config.activeProfile,
        onOutput: (String) -> Unit = {},
        onComplete: () -> Unit = {}
    ) {
        executor.submit {
            try {
                // 1. Send Ctrl+C (ETX = 3) to the active command's output stream
                activeCommand?.outputStream?.let { os ->
                    try {
                        os.write(3)
                        os.flush()
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}

            try {
                activeSession?.close()
            } catch (_: Exception) {}
            activeSession = null
            activeCommand = null

            // 2. Kill remaining processes on the remote host (bootRun, java, gradle)
            val client = sshClient
            if (client != null && client.isConnected) {
                try {
                    val stopCmd = "pkill -f bootRun 2>/dev/null; pkill -f 'java.*jar' 2>/dev/null; pkill -f 'org.gradle.launcher.daemon' 2>/dev/null; true"
                    val session = client.startSession()
                    val killCommand = session.exec(stopCmd)
                    killCommand.join(4, TimeUnit.SECONDS)
                    session.close()
                } catch (e: Exception) {
                    onOutput("[STOP WARNING] " + (e.message ?: e.toString()) + "\n")
                }
            }

            stopAppPortForward()
            isProcessRunning = false
            runningCommand = null
            com.intellij.ide.ActivityTracker.getInstance().inc()
            try {
                project.messageBus.syncPublisher(RemoteConnectionListener.TOPIC).processStateChanged(false, null)
            } catch (_: Exception) {}
            activeProcessHandler?.finishProcess(130)
            activeProcessHandler = null
            onComplete()
        }
    }

    fun detectRemoteJava(
        profile: ServerProfile = config.activeProfile,
        onResult: (List<String>) -> Unit
    ) {
        executor.submit {
            try {
                withSshClient(profile) { client ->
                    val checkCmd = "which java 2>/dev/null; ls -d /usr/lib/jvm/* 2>/dev/null; ls -d /usr/java/* 2>/dev/null; ls -d /opt/java/* 2>/dev/null; ls -d /opt/jdk* 2>/dev/null; ls -d \$HOME/.sdkman/candidates/java/* 2>/dev/null; [ -n \"\$JAVA_HOME\" ] && echo \"\$JAVA_HOME\""
                    val session = client.startSession()
                    val command = session.exec(checkCmd)
                    val output = BufferedReader(InputStreamReader(command.inputStream, java.nio.charset.StandardCharsets.UTF_8)).readText()
                    command.join(10, TimeUnit.SECONDS)
                    session.close()

                    val rawLines = output.lines()
                    val resultList = mutableListOf<String>()
                    for (raw in rawLines) {
                        val line = raw.trim()
                        if (line.isBlank() || line.contains("No such file") || line.contains("cannot access") || line.contains("Permission denied")) continue
                        val clean = when {
                            line.endsWith("/bin/java") -> line.removeSuffix("/bin/java")
                            line.endsWith("/bin") -> line.removeSuffix("/bin")
                            else -> line
                        }.trimEnd('/', '\\')
                        if (clean.isNotBlank() && !resultList.contains(clean)) {
                            resultList.add(clean)
                        }
                    }
                    onResult(resultList)
                }
            } catch (_: Exception) {
                onResult(emptyList())
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

        val client = sshClient
        activeRemoteForwards.forEach { (_, forward) ->
            try {
                client?.remotePortForwarder?.cancel(forward)
            } catch (_: Exception) {}
        }
        activeRemoteForwards.clear()

        config.activeProfile.forwardedPorts.forEach { it.isForwarded = false }

        try {
            sshClient?.disconnect()
            sshClient?.close()
        } catch (_: Exception) {}
        sshClient = null
        stopAppPortForward()

        if (isProcessRunning) {
            isProcessRunning = false
            runningCommand = null
            activeSession = null
            activeCommand = null
            com.intellij.ide.ActivityTracker.getInstance().inc()
            try {
                project.messageBus.syncPublisher(RemoteConnectionListener.TOPIC).processStateChanged(false, null)
            } catch (_: Exception) {}
            activeProcessHandler?.finishProcess(-1)
            activeProcessHandler = null
        }

        project.messageBus.syncPublisher(RemoteConnectionListener.TOPIC).connectionStateChanged(false, config.activeProfile)
    }

    companion object {
        fun getInstance(project: Project): RemoteConnectionManager =
            project.getService(RemoteConnectionManager::class.java)
    }
}
