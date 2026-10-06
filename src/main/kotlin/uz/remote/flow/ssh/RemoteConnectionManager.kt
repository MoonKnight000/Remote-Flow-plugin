package uz.remote.flow.ssh

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.connection.channel.forwarded.RemotePortForwarder
import net.schmizz.sshj.connection.channel.forwarded.SocketForwardingConnectListener
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import uz.remote.flow.logging.LogCategory
import uz.remote.flow.logging.RemoteFlowLogService
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

fun sanitizeServerHostAndPort(rawHost: String, rawPort: Int): Pair<String, Int> {
    var h = rawHost.trim()
    var p = if (rawPort in 1..65535) rawPort else 22
    if (h.startsWith("ssh://", ignoreCase = true)) h = h.substring(6).trim()
    if (h.startsWith("http://", ignoreCase = true)) h = h.substring(7).trim()
    if (h.startsWith("https://", ignoreCase = true)) h = h.substring(8).trim()
    if (h.startsWith("ssh ", ignoreCase = true)) h = h.substring(4).trim()
    if (h.contains("@")) {
        h = h.substringAfter('@').trim()
    }
    if (h.contains(":") && !h.startsWith("[")) {
        val portStr = h.substringAfterLast(':').trim()
        val parsedPort = portStr.toIntOrNull()
        if (parsedPort != null && parsedPort in 1..65535) {
            p = parsedPort
            h = h.substringBeforeLast(':').trim()
        }
    }
    return Pair(h, p)
}

@Service(Service.Level.PROJECT)
class RemoteConnectionManager(private val project: Project) {

    private val LOG = Logger.getInstance(RemoteConnectionManager::class.java)
    private val logService get() = RemoteFlowLogService.getInstance(project)

    private var sshClient: SSHClient? = null
    private val executor = Executors.newCachedThreadPool()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val activeTunnels = ConcurrentHashMap<Int, ServerSocket>()
    private val activeRemoteForwards = ConcurrentHashMap<Int, RemotePortForwarder.Forward>()
    private val tunnelLock = Any()

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

    private fun createSshClient(connectTimeoutMs: Int = 7000, readTimeoutMs: Int = 10000): SSHClient {
        return SSHClient().apply {
            addHostKeyVerifier(PromiscuousVerifier())
            connectTimeout = connectTimeoutMs
            timeout = readTimeoutMs
        }
    }

    private fun authenticateClient(client: SSHClient, profile: ServerProfile) {
        when (profile.authType) {
            AuthType.PASSWORD -> client.authPassword(profile.user, profile.password)
            AuthType.PRIVATE_KEY -> {
                val keyProvider = client.loadKeys(profile.privateKeyPath)
                client.authPublickey(profile.user, keyProvider)
            }
        }
    }

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
                tempClient = createSshClient(7000, 10000).apply {
                    connect(profile.host, profile.port)
                    authenticateClient(this, profile)
                }
                tempClient!!
            }
            action(client)
        } finally {
            try { tempClient?.disconnect(); tempClient?.close() } catch (_: Throwable) {}
        }
    }

    fun testConnection(
        profile: ServerProfile = config.activeProfile,
        checkRemoteDir: String? = null,
        onProgress: ((String) -> Unit)? = null,
        onResult: (ok: Boolean, dirExists: Boolean, msg: String) -> Unit
    ) {
        val (cleanHost, cleanPort) = sanitizeServerHostAndPort(profile.host, profile.port)
        val cleanUser = profile.user.trim().ifBlank { "root" }

        LOG.info("[TEST-CONN] Starting test for profile '${profile.name}' -> user='$cleanUser', host='$cleanHost', port=$cleanPort, authType=${profile.authType}, keyPath='${profile.privateKeyPath}'")
        logService.log("[SSH TEST] Starting connection test for profile '${profile.name}' ($cleanUser@$cleanHost:$cleanPort, Auth: ${profile.authType})...", LogCategory.SSH, profile.name)

        if (cleanHost.isBlank()) {
            val err = "Server Host IP / Domain is required."
            LOG.warn("[TEST-CONN] Failed validation: $err")
            onProgress?.invoke("✗ $err")
            onResult(false, false, err)
            return
        }

        if (profile.authType == AuthType.PRIVATE_KEY) {
            if (profile.privateKeyPath.isBlank()) {
                val err = "Please specify path to SSH Private Key file (.pem / id_rsa)."
                LOG.warn("[TEST-CONN] Failed validation: $err")
                onProgress?.invoke("✗ $err")
                onResult(false, false, err)
                return
            }
            val keyFile = File(profile.privateKeyPath)
            if (!keyFile.exists() || !keyFile.isFile) {
                val err = "SSH Private Key file not found: ${profile.privateKeyPath}"
                LOG.warn("[TEST-CONN] Failed validation: $err")
                onProgress?.invoke("✗ $err")
                onResult(false, false, err)
                return
            }
        }

        onProgress?.invoke("1/3 Connecting to TCP socket $cleanHost:$cleanPort (timeout 7s)...")

        executor.submit {
            var tempClient: SSHClient? = null
            val startTime = System.currentTimeMillis()
            try {
                // Ensure BouncyCastle provider is registered for modern OpenSSH / Ed25519 keys
                try {
                    if (java.security.Security.getProvider("BC") == null) {
                        val bcClass = Class.forName("org.bouncycastle.jce.provider.BouncyCastleProvider")
                        val bcProvider = bcClass.getDeclaredConstructor().newInstance() as java.security.Provider
                        java.security.Security.addProvider(bcProvider)
                    }
                } catch (bce: Throwable) {
                    LOG.warn("[TEST-CONN] BouncyCastle init notice: ${bce.message}")
                }

                tempClient = createSshClient(connectTimeoutMs = 7000, readTimeoutMs = 10000)

                LOG.info("[TEST-CONN] Connecting socket to $cleanHost:$cleanPort (timeout=7000ms)...")
                tempClient.connect(cleanHost, cleanPort)
                val tcpDuration = System.currentTimeMillis() - startTime
                val srvBanner = try { tempClient.transport.serverVersion ?: "SSH-2.0" } catch (_: Throwable) { "SSH-2.0" }
                LOG.info("[TEST-CONN] Socket connected in ${tcpDuration}ms. Server banner: $srvBanner")

                onProgress?.invoke("2/3 TCP connected (${tcpDuration}ms, $srvBanner). Authenticating '$cleanUser' via ${profile.authType}...")
                logService.log("[SSH TEST] Socket connected in ${tcpDuration}ms. Server: $srvBanner. Authenticating user '$cleanUser'...", LogCategory.SSH, profile.name)

                when (profile.authType) {
                    AuthType.PASSWORD -> tempClient.authPassword(cleanUser, profile.password)
                    AuthType.PRIVATE_KEY -> {
                        val keyProvider = tempClient.loadKeys(profile.privateKeyPath)
                        tempClient.authPublickey(cleanUser, keyProvider)
                    }
                }

                val ok = tempClient.isConnected && tempClient.isAuthenticated
                val totalDuration = System.currentTimeMillis() - startTime
                LOG.info("[TEST-CONN] Auth result: isConnected=${tempClient.isConnected}, isAuthenticated=${tempClient.isAuthenticated} in ${totalDuration}ms")

                var dirExists = true
                if (ok && !checkRemoteDir.isNullOrBlank()) {
                    onProgress?.invoke("3/3 Checking remote directory '$checkRemoteDir'...")
                    try {
                        val session = tempClient.startSession()
                        val cmd = session.exec("test -d \"$checkRemoteDir\"")
                        cmd.join(5, TimeUnit.SECONDS)
                        dirExists = (cmd.exitStatus == 0)
                        session.close()
                        LOG.info("[TEST-CONN] Remote dir '$checkRemoteDir' exists: $dirExists (exitStatus=${cmd.exitStatus})")
                    } catch (de: Throwable) {
                        LOG.warn("[TEST-CONN] Remote dir check notice: ${de.message}")
                        dirExists = false
                    }
                }

                try { tempClient.disconnect(); tempClient.close() } catch (_: Throwable) {}

                if (ok) {
                    val successMsg = "Connected to $cleanHost:$cleanPort as '$cleanUser' in ${totalDuration}ms (Server: $srvBanner)"
                    LOG.info("[TEST-CONN] $successMsg")
                    logService.log("[SSH TEST SUCCESS] $successMsg", LogCategory.SSH, profile.name)
                    onProgress?.invoke("✓ $successMsg")
                    onResult(true, dirExists, successMsg)
                } else {
                    val authFail = "Authentication failed for user '$cleanUser' on $cleanHost:$cleanPort via ${profile.authType}. Server rejected credentials."
                    LOG.warn("[TEST-CONN] $authFail")
                    logService.log("[SSH TEST FAILED] $authFail", LogCategory.SSH, profile.name, isError = true)
                    onProgress?.invoke("✗ $authFail")
                    onResult(false, false, authFail)
                }
            } catch (t: Throwable) {
                try { tempClient?.disconnect(); tempClient?.close() } catch (_: Throwable) {}
                LOG.warn("[TEST-CONN EXCEPTION] Error connecting to $cleanHost:$cleanPort for user '$cleanUser': ${t.message}", t)

                val errMsg = when {
                    t is java.net.SocketTimeoutException ->
                        "Connection timed out (7s). Host '$cleanHost:$cleanPort' is unreachable or port $cleanPort is firewalled."
                    t is java.net.ConnectException ->
                        "Connection refused by host '$cleanHost:$cleanPort' (SSH service is not running on port $cleanPort or firewall dropped connection)."
                    t is java.net.UnknownHostException ->
                        "Unknown host '$cleanHost'. Please check the IP address or domain name spelling."
                    t is net.schmizz.sshj.userauth.UserAuthException ->
                        "Authentication failed for user '$cleanUser' on $cleanHost:$cleanPort via ${profile.authType}. Server rejected credentials (check password or SSH private key)."
                    t.message?.contains("PasswordFinder", ignoreCase = true) == true ->
                        "Private key is encrypted with a passphrase. Please use an unencrypted private key file."
                    t.message?.contains("Algorithm negotiation fail", ignoreCase = true) == true ->
                        "SSH cipher/algorithm negotiation failed. Server and client do not share compatible algorithms."
                    else -> "Failed to connect: " + (t.message ?: t.toString())
                }

                logService.log("[SSH TEST ERROR] $errMsg", LogCategory.SSH, profile.name, isError = true)
                onProgress?.invoke("✗ $errMsg")
                onResult(false, false, errMsg)
            }
        }
    }

    fun testConnection(
        profile: ServerProfile = config.activeProfile,
        checkRemoteDir: String? = null,
        onResult: (ok: Boolean, dirExists: Boolean, msg: String) -> Unit
    ) {
        testConnection(profile, checkRemoteDir, onProgress = null, onResult = onResult)
    }

    fun testConnection(profile: ServerProfile = config.activeProfile, onResult: (Boolean, String) -> Unit) {
        testConnection(profile, checkRemoteDir = null, onProgress = null) { ok, _, msg ->
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
                    tempClient = createSshClient(7000, 10000).apply {
                        connect(profile.host, profile.port)
                        authenticateClient(this, profile)
                    }
                    tempClient!!
                }

                val session = active.startSession()
                val cmd = session.exec("test -d \"$dirPath\"")
                cmd.join(5, TimeUnit.SECONDS)
                val status = cmd.exitStatus ?: -1
                session.close()
                onResult(status == 0, null)
            } catch (t: Throwable) {
                onResult(false, t.message ?: t.toString())
            } finally {
                try { tempClient?.disconnect(); tempClient?.close() } catch (_: Throwable) {}
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
                    tempClient = createSshClient(7000, 10000).apply {
                        connect(profile.host, profile.port)
                        authenticateClient(this, profile)
                    }
                    tempClient!!
                }

                val session = active.startSession()
                val cmd = session.exec("mkdir -p \"$dirPath\"")
                cmd.join(10, TimeUnit.SECONDS)
                val status = cmd.exitStatus ?: -1
                session.close()
                onResult(status == 0, if (status == 0) null else "Server error: exit status $status")
            } catch (t: Throwable) {
                onResult(false, t.message ?: t.toString())
            } finally {
                try { tempClient?.disconnect(); tempClient?.close() } catch (_: Throwable) {}
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
                    tempClient = createSshClient(7000, 10000).apply {
                        connect(profile.host, profile.port)
                        authenticateClient(this, profile)
                    }
                    tempClient!!
                }

                val sftp = active.newSFTPClient()
                try {
                    val cleanRemote = remotePath.replace('\\', '/')
                    val parent = cleanRemote.substringBeforeLast('/')
                    if (parent.isNotBlank()) {
                        try { sftp.mkdirs(parent) } catch (_: Throwable) {}
                    }
                    sftp.put(localFile.absolutePath, cleanRemote)
                } finally {
                    try { sftp.close() } catch (_: Throwable) {}
                }

                onComplete(true)
            } catch (t: Throwable) {
                onProgress("[SFTP ERROR] " + (t.message ?: t.toString()) + "\n")
                onComplete(false)
            } finally {
                try { tempClient?.disconnect(); tempClient?.close() } catch (_: Throwable) {}
            }
        }
    }

    fun connect(profile: ServerProfile = config.activeProfile, onSuccess: () -> Unit, onError: (Throwable) -> Unit) {
        executor.submit {
            try {
                disconnect()

                val client = createSshClient(connectTimeoutMs = 8000, readTimeoutMs = 15000)
                client.connect(profile.host, profile.port)

                authenticateClient(client, profile)

                sshClient = client
                startPortForwarding(profile)
                startKeepAliveWatchdog()

                notifyUser("Remote Flow: Connected", "Connected successfully to " + profile.name, NotificationType.INFORMATION)
                project.messageBus.syncPublisher(RemoteConnectionListener.TOPIC).connectionStateChanged(true, profile)
                onSuccess()
            } catch (t: Throwable) {
                disconnect()
                project.messageBus.syncPublisher(RemoteConnectionListener.TOPIC).connectionStateChanged(false, profile)
                onError(t)
            }
        }
    }

    fun ensureConnected(
        profile: ServerProfile = config.activeProfile,
        onResult: (Boolean, String) -> Unit
    ) {
        if (isConnected) {
            onResult(true, "")
            return
        }
        if (profile.host.isBlank()) {
            onResult(false, "No active remote server host configured.")
            return
        }
        connect(
            profile = profile,
            onSuccess = { onResult(true, "") },
            onError = { err -> onResult(false, err.message ?: err.toString()) }
        )
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

        synchronized(tunnelLock) {
            val current = activeAppPort
            val existing = activeTunnels[port]
            if (current == port && existing != null && !existing.isClosed && existing.isBound) {
                return true
            }

            if (current != null && current != port) {
                stopAppPortForward()
            }

            activeAppPort = port
        }

        val mapping = PortMapping(
            localPort = port,
            remotePort = port,
            serviceName = "App ($port)",
            isForwarded = true,
            direction = ForwardDirection.LOCAL_TO_REMOTE
        )
        startSingleForward(mapping)
        notifyPortForwardingChanged()
        return true
    }

    /**
     * Stops and closes the dynamic application port tunnel immediately, releasing local socket resources.
     */
    fun stopAppPortForward() {
        val port: Int
        synchronized(tunnelLock) {
            port = activeAppPort ?: return
            activeAppPort = null
        }
        val mapping = PortMapping(
            localPort = port,
            remotePort = port,
            serviceName = "App ($port)",
            direction = ForwardDirection.LOCAL_TO_REMOTE
        )
        stopSingleForward(mapping)
        notifyPortForwardingChanged()
    }

    fun startSingleForward(portMap: PortMapping) {
        val client = sshClient ?: return
        if (!client.isConnected) return

        if (portMap.direction == ForwardDirection.LOCAL_TO_REMOTE) {
            val serverSocket: ServerSocket
            synchronized(tunnelLock) {
                val existing = activeTunnels[portMap.localPort]
                if (existing != null && !existing.isClosed && existing.isBound) {
                    portMap.isForwarded = true
                    return
                }

                try {
                    serverSocket = ServerSocket()
                    serverSocket.reuseAddress = true
                    serverSocket.bind(InetSocketAddress("127.0.0.1", portMap.localPort))
                    activeTunnels[portMap.localPort] = serverSocket
                    portMap.isForwarded = true
                } catch (e: Exception) {
                    portMap.isForwarded = false
                    try {
                        uz.remote.flow.logging.RemoteFlowLogService.getInstance(project).log(
                            "[PORT FORWARD WARNING] Local port ${portMap.localPort} could not be bound: ${e.message}\n",
                            uz.remote.flow.logging.LogCategory.SSH,
                            config.activeProfile.name
                        )
                    } catch (_: Exception) {}
                    return
                }
            }

            executor.submit {
                try {
                    val params = Parameters("127.0.0.1", portMap.localPort, "127.0.0.1", portMap.remotePort)
                    client.newLocalPortForwarder(params, serverSocket).listen()
                } catch (e: Exception) {
                    synchronized(tunnelLock) {
                        if (activeTunnels[portMap.localPort] === serverSocket) {
                            activeTunnels.remove(portMap.localPort)
                            portMap.isForwarded = false
                        }
                    }
                    try { serverSocket.close() } catch (_: Exception) {}
                    notifyPortForwardingChanged()
                }
            }
            notifyPortForwardingChanged()
        } else {
            // ForwardDirection.REMOTE_TO_LOCAL (Host -> Local, ssh -R)
            synchronized(tunnelLock) {
                if (activeRemoteForwards.containsKey(portMap.remotePort)) {
                    portMap.isForwarded = true
                    return
                }
            }

            executor.submit {
                try {
                    val forward = RemotePortForwarder.Forward(portMap.remotePort)
                    val listener = SocketForwardingConnectListener(InetSocketAddress("127.0.0.1", portMap.localPort))
                    client.remotePortForwarder.bind(forward, listener)
                    synchronized(tunnelLock) {
                        activeRemoteForwards[portMap.remotePort] = forward
                        portMap.isForwarded = true
                    }
                    notifyPortForwardingChanged()
                } catch (e: Exception) {
                    synchronized(tunnelLock) {
                        portMap.isForwarded = false
                        activeRemoteForwards.remove(portMap.remotePort)
                    }
                    try {
                        uz.remote.flow.logging.RemoteFlowLogService.getInstance(project).log(
                            "[PORT FORWARD WARNING] Remote port ${portMap.remotePort} reverse bind failed: ${e.message}\n",
                            uz.remote.flow.logging.LogCategory.SSH,
                            config.activeProfile.name
                        )
                    } catch (_: Exception) {}
                }
            }
        }
    }

    fun stopSingleForward(portMap: PortMapping) {
        synchronized(tunnelLock) {
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
        notifyPortForwardingChanged()
    }

    fun notifyPortForwardingChanged() {
        try {
            project.messageBus.syncPublisher(RemoteConnectionListener.TOPIC).portForwardingChanged()
        } catch (_: Exception) {}
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

    fun executeStreamingCommand(
        cmd: String,
        workingDir: String = config.activeProfile.remoteProjectPath,
        timeoutSeconds: Long = 600,
        onOutput: (String) -> Unit,
        onComplete: (Int) -> Unit
    ): () -> Unit {
        var isCancelled = false
        var currentSession: net.schmizz.sshj.connection.channel.direct.Session? = null
        var currentCommand: net.schmizz.sshj.connection.channel.direct.Session.Command? = null

        val cancelHandle: () -> Unit = {
            isCancelled = true
            try { currentCommand?.outputStream?.write(3) } catch (_: Exception) {}
            try { currentCommand?.outputStream?.flush() } catch (_: Exception) {}
            try { currentCommand?.close() } catch (_: Exception) {}
            try { currentSession?.close() } catch (_: Exception) {}
        }

        executor.submit {
            val client = sshClient
            if (client == null || !client.isConnected) {
                onOutput("[ERROR] SSH connection is not open. Connect to server first!\n")
                onComplete(-1)
                return@submit
            }

            try {
                val fullCmd = if (workingDir.isNotBlank()) "cd \"$workingDir\" && $cmd" else cmd
                val session = client.startSession()
                currentSession = session

                val command = session.exec(fullCmd)
                currentCommand = command

                // Close remote stdin immediately so non-interactive tools don't hang
                try { command.outputStream.close() } catch (_: Exception) {}

                // Drain stderr in background thread
                val errFuture = executor.submit {
                    try {
                        BufferedReader(InputStreamReader(command.errorStream, java.nio.charset.StandardCharsets.UTF_8)).use { errReader ->
                            var errLine: String?
                            while (errReader.readLine().also { errLine = it } != null) {
                                val raw = errLine ?: ""
                                val cleaned = cleanProgressRemnants(raw)
                                if (cleaned.isNotBlank() && cleanAnsiText(cleaned).trim().isNotBlank()) {
                                    onOutput(cleaned + "\n")
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }

                // Drain stdout
                BufferedReader(InputStreamReader(command.inputStream, java.nio.charset.StandardCharsets.UTF_8)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val raw = line ?: ""
                        val cleaned = cleanProgressRemnants(raw)
                        if (cleaned.isNotBlank() && cleanAnsiText(cleaned).trim().isNotBlank()) {
                            onOutput(cleaned + "\n")
                        }
                    }
                }

                try { errFuture.get(2, TimeUnit.SECONDS) } catch (_: Exception) {}
                if (timeoutSeconds > 0) {
                    try {
                        command.join(timeoutSeconds, TimeUnit.SECONDS)
                    } catch (_: Exception) {}
                } else {
                    command.join()
                }

                val exitStatus = if (isCancelled) -1 else (command.exitStatus ?: 0)
                onComplete(exitStatus)
            } catch (e: Exception) {
                if (!isCancelled) {
                    onOutput("[ERROR]: " + (e.message ?: e.toString()) + "\n")
                    onComplete(-1)
                }
            } finally {
                try { currentSession?.close() } catch (_: Exception) {}
            }
        }

        return cancelHandle
    }

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

            var session: net.schmizz.sshj.connection.channel.direct.Session? = null
            try {
                val fullCmd = if (workingDir.isNotBlank()) "cd \"$workingDir\" && $cmd" else cmd
                session = client.startSession()

                if (isLongRunning) {
                    session.allocateDefaultPTY()
                    val command = session.exec(fullCmd)
                    isProcessRunning = true
                    runningCommand = cmd
                    activeSession = session
                    activeCommand = command
                    com.intellij.ide.ActivityTracker.getInstance().inc()
                    try {
                        project.messageBus.syncPublisher(RemoteConnectionListener.TOPIC).processStateChanged(true, cmd)
                    } catch (_: Exception) {}

                    BufferedReader(InputStreamReader(command.inputStream, java.nio.charset.StandardCharsets.UTF_8)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            val raw = line ?: ""
                            val cleaned = cleanProgressRemnants(raw)
                            if (cleaned.isNotBlank() && cleanAnsiText(cleaned).trim().isNotBlank()) {
                                onOutput(cleaned + "\n")
                            }
                        }
                    }

                    command.join()
                    val exitStatus = command.exitStatus ?: 0
                    onComplete(exitStatus)
                } else {
                    // Non-interactive short command: DO NOT allocate PTY!
                    val command = session.exec(fullCmd)

                    // Immediately close remote stdin so commands reading standard input don't block
                    try { command.outputStream.close() } catch (_: Exception) {}

                    // Drain stderr in background to prevent SSH buffer deadlock
                    val errFuture = executor.submit {
                        try {
                            BufferedReader(InputStreamReader(command.errorStream, java.nio.charset.StandardCharsets.UTF_8)).use { errReader ->
                                var errLine: String?
                                while (errReader.readLine().also { errLine = it } != null) {
                                    val raw = errLine ?: ""
                                    val cleaned = cleanProgressRemnants(raw)
                                    if (cleaned.isNotBlank() && cleanAnsiText(cleaned).trim().isNotBlank()) {
                                        onOutput(cleaned + "\n")
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    }

                    // Read stdout
                    BufferedReader(InputStreamReader(command.inputStream, java.nio.charset.StandardCharsets.UTF_8)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            val raw = line ?: ""
                            val cleaned = cleanProgressRemnants(raw)
                            if (cleaned.isNotBlank() && cleanAnsiText(cleaned).trim().isNotBlank()) {
                                onOutput(cleaned + "\n")
                            }
                        }
                    }

                    try { errFuture.get(2, TimeUnit.SECONDS) } catch (_: Exception) {}
                    try {
                        command.join(15, TimeUnit.SECONDS)
                    } catch (_: Exception) {}
                    val exitStatus = command.exitStatus ?: 0
                    onComplete(exitStatus)
                }
            } catch (e: Exception) {
                onOutput("[ERROR]: " + (e.message ?: e.toString()) + "\n")
                onComplete(-1)
            } finally {
                try { session?.close() } catch (_: Exception) {}
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
