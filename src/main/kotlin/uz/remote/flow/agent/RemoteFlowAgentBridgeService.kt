package uz.remote.flow.agent

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import uz.remote.flow.logging.LogCategory
import uz.remote.flow.logging.RemoteFlowLogService
import uz.remote.flow.settings.RemoteFlowSettings
import uz.remote.flow.ssh.RemoteConnectionManager
import uz.remote.flow.ssh.buildRemoteExecutionCommand
import uz.remote.flow.sync.FastSyncManager
import java.io.File
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.PROJECT)
class RemoteFlowAgentBridgeService(private val project: Project) : Disposable {

    private var server: HttpServer? = null
    private val isRunning = AtomicBoolean(false)
    private val pool = Executors.newCachedThreadPool()

    private val connectionManager get() = RemoteConnectionManager.getInstance(project)
    private val syncManager get() = FastSyncManager(project)
    private val logService get() = RemoteFlowLogService.getInstance(project)
    private val settings get() = RemoteFlowSettings.getInstance(project)

    val remoteFlowHome: File
        get() = File(System.getProperty("user.home"), ".remote-flow")

    val remoteFlowBinDir: File
        get() = File(remoteFlowHome, "bin")

    val remoteFlowStateDir: File
        get() = File(remoteFlowHome, "state")

    @Synchronized
    fun start() {
        if (isRunning.get() || project.isDisposed) return

        // Always clean any legacy files left in the project root
        cleanLegacyProjectFiles()

        if (!settings.enableAgentBridge) return
        val base = project.basePath ?: return

        pool.submit {
            try {
                // 1. Ensure global CLI scripts exist in ~/.remote-flow/bin and bin is in user PATH
                ensureGlobalCliHelperFiles()

                val http = try {
                    HttpServer.create(InetSocketAddress("127.0.0.1", 45789), 0)
                } catch (_: Exception) {
                    HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
                }

                http.executor = Executors.newCachedThreadPool()

                val mcpProcessor = RemoteFlowMcpProcessor(project)
                http.createContext("/api/status", StatusHandler())
                http.createContext("/api/test", TestHandler())
                http.createContext("/api/build", BuildHandler())
                http.createContext("/api/sync", SyncHandler())
                http.createContext("/api/pull", PullHandler())
                http.createContext("/api/docker", DockerHandler())
                http.createContext("/api/diagnostics", DiagnosticsHandler())
                http.createContext("/api/mcp", McpHandler(mcpProcessor))
                http.createContext("/api/diff", DiffHandler())
                http.createContext("/api/memory", MemoryHandler())
                http.createContext("/api/env", EnvHandler())
                http.createContext("/api/terminal", TerminalHandler())
                http.createContext("/api/exec", ExecHandler())
                http.createContext("/api/stop", StopHandler())
                http.createContext("/api/profile/start", ProfileStartHandler())
                http.createContext("/api/profile/stop", ProfileStopHandler())
                http.createContext("/api/profile/status", ProfileStatusHandler())

                http.start()
                server = http
                isRunning.set(true)

                val assignedPort = http.address.port
                writeStateFile(assignedPort)

                logService.log(
                    "[AI BRIDGE] Started local bridge server on port $assignedPort. Global CLI: ~/.remote-flow/bin/rf (zero files added to project).",
                    LogCategory.AI,
                    connectionManager.config.activeProfile.name
                )
            } catch (e: Exception) {
                logService.log(
                    "[AI BRIDGE ERROR] Failed to start agent bridge server: ${e.message}",
                    LogCategory.AI,
                    connectionManager.config.activeProfile.name,
                    isError = true
                )
            }
        }
    }

    private fun getProjectKey(): String {
        val base = project.basePath ?: project.name
        return Math.abs((project.name + "_" + base.replace('\\', '/')).hashCode()).toString()
    }

    private fun writeStateFile(port: Int) {
        val base = project.basePath ?: return
        try {
            remoteFlowStateDir.mkdirs()
            val stateFile = File(remoteFlowStateDir, "project_${getProjectKey()}.properties")
            val content = buildString {
                append("PORT=").append(port).append("\n")
                append("PROJECT_NAME=").append(project.name).append("\n")
                append("PROJECT_PATH=").append(base.replace('\\', '/')).append("\n")
            }
            stateFile.writeText(content, StandardCharsets.UTF_8)
            stateFile.deleteOnExit()
        } catch (_: Exception) {}
    }

    private fun writeExitCodeFile(code: Int) {
        val s = server ?: return
        try {
            remoteFlowStateDir.mkdirs()
            val exitFile = File(remoteFlowStateDir, "exitcode_${s.address.port}.txt")
            exitFile.writeText(code.toString(), StandardCharsets.UTF_8)
        } catch (_: Exception) {}
    }

    fun cleanStateFiles() {
        try {
            val stateFile = File(remoteFlowStateDir, "project_${getProjectKey()}.properties")
            if (stateFile.exists()) stateFile.delete()
            server?.let { s ->
                val exitFile = File(remoteFlowStateDir, "exitcode_${s.address.port}.txt")
                if (exitFile.exists()) exitFile.delete()
            }
        } catch (_: Exception) {}
    }

    fun cleanLegacyProjectFiles() {
        val base = project.basePath ?: return
        try {
            val portFile = File(base, ".remote-flow.port")
            if (portFile.exists()) portFile.delete()

            val exitFile = File(base, ".remote-flow.exitcode")
            if (exitFile.exists()) exitFile.delete()

            val rfCmd = File(base, "rf.cmd")
            if (rfCmd.exists()) {
                val text = rfCmd.readText(StandardCharsets.UTF_8)
                if (text.contains("Remote Flow") || text.contains(".remote-flow.port")) {
                    rfCmd.delete()
                }
            }

            val rfPs1 = File(base, "rf.ps1")
            if (rfPs1.exists()) {
                val text = rfPs1.readText(StandardCharsets.UTF_8)
                if (text.contains("Remote Flow") || text.contains(".remote-flow.port")) {
                    rfPs1.delete()
                }
            }

            val rfBash = File(base, "rf")
            if (rfBash.exists()) {
                val text = rfBash.readText(StandardCharsets.UTF_8)
                if (text.contains("Remote Flow") || text.contains(".remote-flow.port")) {
                    rfBash.delete()
                }
            }

            val ruleFile = File(base, ".antigravity/rules/remote-execution.md")
            if (ruleFile.exists()) {
                val text = ruleFile.readText(StandardCharsets.UTF_8)
                if (text.contains("Remote Flow")) {
                    ruleFile.delete()
                }
            }

            val rulesDir = File(base, ".antigravity/rules")
            if (rulesDir.exists() && rulesDir.list().isNullOrEmpty()) {
                rulesDir.delete()
            }

            val agDir = File(base, ".antigravity")
            if (agDir.exists() && agDir.list().isNullOrEmpty()) {
                agDir.delete()
            }
        } catch (_: Exception) {}
    }

    fun ensureGlobalCliHelperFiles() {
        try {
            remoteFlowBinDir.mkdirs()
            val rfCmd = File(remoteFlowBinDir, "rf.cmd")
            rfCmd.writeText(generateRfCmd(), StandardCharsets.UTF_8)

            val rfPs1 = File(remoteFlowBinDir, "rf.ps1")
            rfPs1.writeText(generateRfPs1(), StandardCharsets.UTF_8)

            val rfBash = File(remoteFlowBinDir, "rf")
            rfBash.writeText(generateRfBash(), StandardCharsets.UTF_8)
            rfBash.setExecutable(true, false)

            ensureUserPathContains(remoteFlowBinDir.absolutePath)
        } catch (_: Exception) {}
    }

    private fun ensureUserPathContains(binPath: String) {
        val os = System.getProperty("os.name", "").lowercase()
        if (os.contains("win")) {
            try {
                val normalizedBin = binPath.replace('/', '\\')
                val checkProc = ProcessBuilder(
                    "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                    "[Environment]::GetEnvironmentVariable('Path', 'User')"
                ).start()
                val currentPath = checkProc.inputStream.bufferedReader().readText().trim()
                checkProc.waitFor(3, TimeUnit.SECONDS)

                if (!currentPath.split(';').any { it.trim().equals(normalizedBin, ignoreCase = true) }) {
                    val script = "[Environment]::SetEnvironmentVariable('Path', [Environment]::GetEnvironmentVariable('Path', 'User') + ';$normalizedBin', 'User')"
                    val setProc = ProcessBuilder(
                        "powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script
                    ).start()
                    setProc.waitFor(3, TimeUnit.SECONDS)
                }
            } catch (_: Exception) {}
        }
    }

    private inner class StatusHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            val profile = connectionManager.config.activeProfile
            val isConn = connectionManager.isConnected
            val json = buildString {
                append("{")
                append("\"status\":\"ok\",")
                append("\"project\":\"").append(escapeJson(project.name)).append("\",")
                append("\"projectPath\":\"").append(escapeJson(project.basePath ?: "")).append("\",")
                append("\"connected\":").append(isConn).append(",")
                append("\"processRunning\":").append(connectionManager.isProcessRunning).append(",")
                append("\"runningCommand\":\"").append(escapeJson(connectionManager.runningCommand ?: "")).append("\",")
                append("\"remotePid\":").append(connectionManager.remoteProcessPid ?: "null").append(",")
                append("\"activeProfile\":{")
                append("\"name\":\"").append(escapeJson(profile.name)).append("\",")
                append("\"host\":\"").append(escapeJson(profile.host)).append("\",")
                append("\"remoteProjectPath\":\"").append(escapeJson(profile.remoteProjectPath)).append("\",")
                append("\"testCommand\":\"").append(escapeJson(profile.testCommand)).append("\",")
                append("\"buildCommand\":\"").append(escapeJson(profile.buildCommand)).append("\",")
                append("\"runCommand\":\"").append(escapeJson(profile.runCommand)).append("\"")
                append("}")
                append("}")
            }
            val bytes = json.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json; charset=UTF-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    private inner class TestHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            executeStreamingAction(exchange, isTest = true)
        }
    }

    private inner class BuildHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            executeStreamingAction(exchange, isTest = false)
        }
    }

    private inner class SyncHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            exchange.responseHeaders.set("Content-Type", "text/plain; charset=UTF-8")
            exchange.sendResponseHeaders(200, 0)
            val os = exchange.responseBody

            fun stream(msg: String) {
                try {
                    os.write(msg.toByteArray(StandardCharsets.UTF_8))
                    os.flush()
                } catch (_: Exception) {}
            }

            val profile = connectionManager.config.activeProfile
            stream("[AI SYNC] Starting differential project synchronization to ${profile.name} (${profile.host})...\n")
            logService.log("[AI SYNC] Starting sync triggered by AI Agent / CLI...", LogCategory.AI, profile.name)

            val latch = CountDownLatch(1)
            var syncSuccess = false

            syncManager.syncSingleServer(
                profile = profile,
                onLog = { msg: String ->
                    stream(msg)
                },
                onComplete = { success: Boolean ->
                    syncSuccess = success
                    latch.countDown()
                }
            )

            try { latch.await() } catch (_: Exception) {}

            val exitCode = if (syncSuccess) 0 else 1
            writeExitCodeFile(exitCode)
            stream("\n[AI SYNC COMPLETED: exit code $exitCode]\n")
            try { os.close() } catch (_: Exception) {}
        }
    }

    private inner class ExecHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            val queryParams = parseQueryParams(exchange.requestURI.query ?: "")
            val body = exchange.requestBody.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }.trim()
            val rawCmd = body.ifBlank { queryParams["cmd"] ?: "" }

            if (rawCmd.isBlank()) {
                val err = "Error: No command specified. Provide command via request body or ?cmd=...\n"
                val b = err.toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", "text/plain; charset=UTF-8")
                exchange.sendResponseHeaders(400, b.size.toLong())
                exchange.responseBody.use { it.write(b) }
                return
            }

            exchange.responseHeaders.set("Content-Type", "text/plain; charset=UTF-8")
            exchange.sendResponseHeaders(200, 0)
            val os = exchange.responseBody

            fun stream(msg: String) {
                try {
                    os.write(msg.toByteArray(StandardCharsets.UTF_8))
                    os.flush()
                } catch (_: Exception) {}
            }

            val profile = connectionManager.config.activeProfile
            val fullCmd = buildRemoteExecutionCommand(rawCmd, profile.javaHome, profile)

            stream("[AI EXEC] Running command on ${profile.name}: $rawCmd\n\n")
            logService.log("[AI EXEC] Running: $rawCmd", LogCategory.AI, profile.name)

            val latch = CountDownLatch(1)
            var exitCode = 0

            val cancelHandle = connectionManager.executeStreamingCommand(
                cmd = fullCmd,
                workingDir = profile.remoteProjectPath,
                timeoutSeconds = 600,
                onOutput = { out ->
                    stream(out)
                    logService.log(out, LogCategory.AI, profile.name)
                },
                onComplete = { code ->
                    exitCode = code
                    latch.countDown()
                }
            )

            try {
                latch.await()
            } catch (_: Exception) {
                cancelHandle()
            }

            writeExitCodeFile(exitCode)
            stream("\n[AI EXEC COMPLETED: exit code $exitCode]\n")
            try { os.close() } catch (_: Exception) {}
        }
    }

    @Volatile private var lastExecutionOutput: String = ""

    private inner class PullHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            exchange.responseHeaders.set("Content-Type", "text/plain; charset=UTF-8")
            exchange.sendResponseHeaders(200, 0)
            val os = exchange.responseBody
            fun stream(msg: String) {
                try {
                    os.write(msg.toByteArray(StandardCharsets.UTF_8))
                    os.flush()
                } catch (_: Exception) {}
            }

            val profile = connectionManager.config.activeProfile
            stream("[AI PULL] Downloading remote changes from ${profile.name} (${profile.host})...\n")
            val latch = CountDownLatch(1)
            var pullSuccess = false

            syncManager.pullFromRemote(
                profile = profile,
                onLog = { stream(it) },
                onComplete = { success ->
                    pullSuccess = success
                    latch.countDown()
                }
            )
            try { latch.await(120, TimeUnit.SECONDS) } catch (_: Exception) {}
            val exitCode = if (pullSuccess) 0 else 1
            writeExitCodeFile(exitCode)
            stream("\n[AI PULL COMPLETED: exit code $exitCode]\n")
            try { os.close() } catch (_: Exception) {}
        }
    }

    private inner class DockerHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            val queryParams = parseQueryParams(exchange.requestURI.query ?: "")
            val action = queryParams["action"]?.lowercase() ?: "list"
            val name = queryParams["name"] ?: ""

            when (action) {
                "list" -> {
                    val latch = CountDownLatch(1)
                    val resultList = mutableListOf<uz.remote.flow.ssh.DockerContainer>()
                    connectionManager.listDockerContainers { containers ->
                        resultList.addAll(containers)
                        latch.countDown()
                    }
                    try { latch.await(10, TimeUnit.SECONDS) } catch (_: Exception) {}

                    val json = buildString {
                        append("{\"status\":\"ok\",\"containers\":[")
                        resultList.forEachIndexed { idx, c ->
                            if (idx > 0) append(",")
                            append("{\"id\":\"").append(escapeJson(c.id)).append("\",")
                            append("\"name\":\"").append(escapeJson(c.names)).append("\",")
                            append("\"image\":\"").append(escapeJson(c.image)).append("\",")
                            append("\"status\":\"").append(escapeJson(c.status)).append("\",")
                            append("\"ports\":\"").append(escapeJson(c.ports)).append("\"}")
                        }
                        append("]}")
                    }
                    val bytes = json.toByteArray(StandardCharsets.UTF_8)
                    exchange.responseHeaders.set("Content-Type", "application/json; charset=UTF-8")
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                "restart" -> {
                    val latch = CountDownLatch(1)
                    var ok = false
                    var msg = ""
                    connectionManager.restartDockerContainer(name) { success, out ->
                        ok = success
                        msg = out
                        latch.countDown()
                    }
                    try { latch.await(20, TimeUnit.SECONDS) } catch (_: Exception) {}
                    val resp = "{\"success\":$ok,\"output\":\"${escapeJson(msg)}\"}"
                    val bytes = resp.toByteArray(StandardCharsets.UTF_8)
                    exchange.responseHeaders.set("Content-Type", "application/json; charset=UTF-8")
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                "logs" -> {
                    val latch = CountDownLatch(1)
                    var logOut = ""
                    connectionManager.getDockerContainerLogs(name, 100) { out ->
                        logOut = out
                        latch.countDown()
                    }
                    try { latch.await(15, TimeUnit.SECONDS) } catch (_: Exception) {}
                    val bytes = logOut.toByteArray(StandardCharsets.UTF_8)
                    exchange.responseHeaders.set("Content-Type", "text/plain; charset=UTF-8")
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                else -> {
                    val err = "Unknown action: $action\n".toByteArray(StandardCharsets.UTF_8)
                    exchange.sendResponseHeaders(400, err.size.toLong())
                    exchange.responseBody.use { it.write(err) }
                }
            }
        }
    }

    private inner class DiagnosticsHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            val diagnostics = DiagnosticsExtractor.extract(lastExecutionOutput)
            val json = buildString {
                append("{\"status\":\"ok\",\"count\":${diagnostics.size},\"diagnostics\":[")
                diagnostics.forEachIndexed { idx, d ->
                    if (idx > 0) append(",")
                    append("{\"file\":\"").append(escapeJson(d.file)).append("\",")
                    append("\"line\":").append(d.line).append(",")
                    append("\"column\":").append(d.column).append(",")
                    append("\"message\":\"").append(escapeJson(d.message)).append("\"}")
                }
                append("]}")
            }
            val bytes = json.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json; charset=UTF-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    private inner class McpHandler(private val processor: RemoteFlowMcpProcessor) : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            val body = exchange.requestBody.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            val resp = processor.handleMcpRequest(body)
            val bytes = resp.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json; charset=UTF-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    private inner class StopHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            connectionManager.stopRemoteProcess()
            val msg = "Remote process stop signal dispatched.\n"
            val b = msg.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "text/plain; charset=UTF-8")
            exchange.sendResponseHeaders(200, b.size.toLong())
            exchange.responseBody.use { it.write(b) }
        }
    }

    private inner class DiffHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            exchange.responseHeaders.set("Content-Type", "text/plain; charset=UTF-8")
            val queryParams = parseQueryParams(exchange.requestURI.query ?: "")
            val targetPath = queryParams["path"]?.trim() ?: ""

            if (targetPath.isNotBlank()) {
                val profile = connectionManager.config.activeProfile
                val basePath = project.basePath ?: ""
                val localFile = File(basePath, targetPath)
                val localContent = if (localFile.exists() && localFile.isFile) localFile.readText(StandardCharsets.UTF_8) else ""
                val remotePath = "${profile.remoteProjectPath.trimEnd('/')}/$targetPath"

                val latch = CountDownLatch(1)
                var remoteContent: String? = null
                uz.remote.flow.files.RemoteFileManager(project).readFileContent(profile, remotePath) { content, _ ->
                    remoteContent = content
                    latch.countDown()
                }
                try { latch.await(10, TimeUnit.SECONDS) } catch (_: Exception) {}

                val sb = StringBuilder()
                sb.append("--- local: ").append(targetPath).append("\n")
                sb.append("+++ remote (").append(profile.name).append("): ").append(remotePath).append("\n")
                if (remoteContent == null) {
                    sb.append("[FILE NOT FOUND ON REMOTE SERVER]\n")
                } else if (localContent == remoteContent) {
                    sb.append("[IDENTICAL - No differences found]\n")
                } else {
                    sb.append(computeSimpleDiff(localContent, remoteContent!!))
                }
                val bytes = sb.toString().toByteArray(StandardCharsets.UTF_8)
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } else {
                val latch = CountDownLatch(1)
                var items: List<uz.remote.flow.sync.SyncDiffItem> = emptyList()
                syncManager.previewDryRunDiff(onResult = {
                    items = it
                    latch.countDown()
                })
                try { latch.await(15, TimeUnit.SECONDS) } catch (_: Exception) {}

                val sb = StringBuilder()
                val profile = connectionManager.config.activeProfile
                sb.append("Remote Flow Diff: Local vs ").append(profile.name).append(" (").append(profile.host).append(")\n")
                sb.append("=".repeat(60)).append("\n")
                if (items.isEmpty()) {
                    sb.append("All files are up to date. No differences found.\n")
                } else {
                    items.forEach { item ->
                        val symbol = when (item.changeType) {
                            uz.remote.flow.sync.SyncChangeType.ADDED -> "[+ NEW]     "
                            uz.remote.flow.sync.SyncChangeType.MODIFIED -> "[~ MODIFIED]"
                            uz.remote.flow.sync.SyncChangeType.DELETED -> "[- DELETED] "
                        }
                        sb.append(symbol).append(" ").append(item.relativePath).append("\n")
                    }
                }
                val bytes = sb.toString().toByteArray(StandardCharsets.UTF_8)
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
    }

    private inner class MemoryHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            val profile = connectionManager.config.activeProfile
            val queryParams = parseQueryParams(exchange.requestURI.query ?: "")
            val max = queryParams["max"]
            val initial = queryParams["initial"]
            if (!max.isNullOrBlank()) {
                profile.maxHeapSize = max.trim()
            }
            if (!initial.isNullOrBlank()) {
                profile.initialHeapSize = initial.trim()
            }
            val json = buildString {
                append("{")
                append("\"status\":\"ok\",")
                append("\"server\":\"").append(escapeJson(profile.name)).append("\",")
                append("\"maxHeap\":\"").append(escapeJson(profile.maxHeapSize)).append("\",")
                append("\"initialHeap\":\"").append(escapeJson(profile.initialHeapSize)).append("\",")
                append("\"extraJvmArgs\":\"").append(escapeJson(profile.extraJvmArgs)).append("\",")
                append("\"nodeMemoryLimitMb\":").append(profile.nodeMemoryLimitMb).append(",")
                append("\"dockerMemoryLimit\":\"").append(escapeJson(profile.dockerMemoryLimit)).append("\"")
                append("}")
            }
            val bytes = json.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json; charset=UTF-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    private inner class EnvHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            val profile = connectionManager.config.activeProfile
            val json = buildString {
                append("{")
                append("\"status\":\"ok\",")
                append("\"server\":\"").append(escapeJson(profile.name)).append("\",")
                append("\"count\":").append(profile.environmentVariables.size).append(",")
                append("\"variables\":{")
                profile.environmentVariables.entries.forEachIndexed { idx, (k, v) ->
                    if (idx > 0) append(",")
                    append("\"").append(escapeJson(k)).append("\":\"").append(escapeJson(v)).append("\"")
                }
                append("}}")
            }
            val bytes = json.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json; charset=UTF-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    private inner class TerminalHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            val profile = connectionManager.config.activeProfile
            ApplicationManager.getApplication().invokeLater {
                uz.remote.flow.terminal.RemoteTerminalHelper.openTerminal(project, profile)
            }
            val resp = "{\"status\":\"ok\",\"message\":\"Opened remote terminal for ${escapeJson(profile.name)}\"}\n"
            val bytes = resp.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json; charset=UTF-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    private inner class ProfileStartHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            val profiler = uz.remote.flow.profiler.RemoteProfilerManager.getInstance(project)
            val latch = CountDownLatch(1)
            var startOk = false
            var startMsg = ""
            profiler.startRecording(profiler.selectedMode) { ok, msg ->
                startOk = ok
                startMsg = msg
                latch.countDown()
            }
            try { latch.await(10, TimeUnit.SECONDS) } catch (_: Exception) {}
            val json = buildString {
                append("{")
                append("\"status\":\"").append(if (startOk) "recording" else "error").append("\",")
                append("\"message\":\"").append(escapeJson(startMsg)).append("\",")
                append("\"mode\":\"").append(profiler.selectedMode.displayName).append("\"")
                append("}")
            }
            val bytes = json.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json; charset=UTF-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    private inner class ProfileStopHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            val profiler = uz.remote.flow.profiler.RemoteProfilerManager.getInstance(project)
            val latch = CountDownLatch(1)
            var snap: uz.remote.flow.profiler.ProfilingSnapshot? = null
            profiler.stopRecording { snapshot ->
                snap = snapshot
                latch.countDown()
            }
            try { latch.await(20, TimeUnit.SECONDS) } catch (_: Exception) {}
            val s = snap ?: profiler.lastSnapshot
            val json = buildString {
                append("{")
                append("\"status\":\"stopped\",")
                append("\"durationSeconds\":").append(s?.durationSeconds ?: 0).append(",")
                append("\"peakCpu\":").append(s?.peakCpuPercent ?: 0.0).append(",")
                append("\"peakHeap\":").append(s?.peakHeapUsedMb ?: 0).append(",")
                append("\"hotspots\":[")
                s?.hotspots?.take(5)?.forEachIndexed { idx, h ->
                    if (idx > 0) append(",")
                    append("{\"method\":\"").append(escapeJson(h.fullMethodName)).append("\",\"cpuPercent\":").append(h.cpuPercent).append("}")
                }
                append("]}")
            }
            val bytes = json.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json; charset=UTF-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    private inner class ProfileStatusHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            val profiler = uz.remote.flow.profiler.RemoteProfilerManager.getInstance(project)
            val m = profiler.latestMetric
            val json = buildString {
                append("{")
                append("\"state\":\"").append(profiler.currentState.name).append("\",")
                append("\"cpuPercent\":").append(m.cpuPercent).append(",")
                append("\"heapUsedMb\":").append(m.heapUsedMb).append(",")
                append("\"heapMaxMb\":").append(m.heapMaxMb).append(",")
                append("\"hasLastSnapshot\":").append(profiler.lastSnapshot != null)
                append("}")
            }
            val bytes = json.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json; charset=UTF-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    private fun computeSimpleDiff(local: String, remote: String): String {
        val localLines = local.lines()
        val remoteLines = remote.lines()
        val sb = StringBuilder()
        var i = 0
        var j = 0
        while (i < localLines.size || j < remoteLines.size) {
            val l = if (i < localLines.size) localLines[i] else null
            val r = if (j < remoteLines.size) remoteLines[j] else null
            if (l == r) {
                i++
                j++
            } else {
                if (l != null && (r == null || !remoteLines.contains(l))) {
                    sb.append("- ").append(l).append("\n")
                    i++
                } else if (r != null) {
                    sb.append("+ ").append(r).append("\n")
                    j++
                } else {
                    i++; j++
                }
            }
        }
        return sb.toString()
    }

    private fun executeStreamingAction(exchange: HttpExchange, isTest: Boolean) {
        val queryParams = parseQueryParams(exchange.requestURI.query ?: "")
        val shouldSync = queryParams["sync"]?.lowercase() != "false"
        val customCmd = queryParams["cmd"] ?: ""

        exchange.responseHeaders.set("Content-Type", "text/plain; charset=UTF-8")
        exchange.sendResponseHeaders(200, 0)
        val os = exchange.responseBody

        fun stream(msg: String) {
            try {
                os.write(msg.toByteArray(StandardCharsets.UTF_8))
                os.flush()
            } catch (_: Exception) {}
        }

        val profile = connectionManager.config.activeProfile
        val actionName = if (isTest) "TEST" else "BUILD"

        logService.showLogWindow()
        logService.log("[AI $actionName] Request received from AI Agent / CLI...", LogCategory.AI, profile.name)

        // 1. Ensure SSH is connected
        if (!connectionManager.isConnected) {
            stream("[AI $actionName] Connecting to remote server ${profile.name} (${profile.host})...\n")
            logService.log("[AI $actionName] Connecting to server ${profile.name}...", LogCategory.AI, profile.name)
            val connLatch = CountDownLatch(1)
            var connOk = false
            var connErr = ""
            connectionManager.ensureConnected(profile) { ok, err ->
                connOk = ok
                connErr = err
                connLatch.countDown()
            }
            try { connLatch.await() } catch (_: Exception) {}

            if (!connOk) {
                val err = "[AI $actionName ERROR] Failed to connect to server: $connErr\n"
                stream(err)
                logService.log(err, LogCategory.AI, profile.name, isError = true)
                writeExitCodeFile(1)
                try { os.close() } catch (_: Exception) {}
                return
            }
            stream("[AI $actionName] Connected to ${profile.name} successfully!\n")
        }

        // 2. Perform differential sync if requested
        if (shouldSync) {
            stream("[AI $actionName] Synchronizing code changes to server (${profile.remoteProjectPath})...\n")
            val syncLatch = CountDownLatch(1)
            var syncSuccess = false
            syncManager.syncSingleServer(
                profile = profile,
                onLog = { logMsg: String ->
                    stream(logMsg)
                },
                onComplete = { success: Boolean ->
                    syncSuccess = success
                    syncLatch.countDown()
                }
            )
            try { syncLatch.await() } catch (_: Exception) {}

            if (!syncSuccess) {
                val err = "[AI $actionName ERROR] Sync failed. Aborting remote execution.\n"
                stream(err)
                logService.log(err, LogCategory.AI, profile.name, isError = true)
                writeExitCodeFile(1)
                try { os.close() } catch (_: Exception) {}
                return
            }
        }

        // 3. Determine command to run
        val rawCmd = if (customCmd.isNotBlank()) {
            customCmd
        } else if (isTest) {
            profile.testCommand.ifBlank { "./gradlew test" }
        } else {
            profile.buildCommand.ifBlank { "./gradlew build -x test" }
        }

        val fullCmd = buildRemoteExecutionCommand(rawCmd, profile.javaHome, profile)
        stream("\n[AI $actionName] Executing on remote server: $rawCmd\n" + "-".repeat(60) + "\n\n")
        logService.log("[AI $actionName] Executing: $rawCmd", LogCategory.AI, profile.name)

        // 4. Stream command execution
        val execLatch = CountDownLatch(1)
        var exitCode = 0
        val outputBuffer = StringBuilder()

        val cancelHandle = connectionManager.executeStreamingCommand(
            cmd = fullCmd,
            workingDir = profile.remoteProjectPath,
            timeoutSeconds = 900,
            onOutput = { out ->
                stream(out)
                outputBuffer.append(out)
                logService.log(out, LogCategory.AI, profile.name)
            },
            onComplete = { code ->
                exitCode = code
                lastExecutionOutput = outputBuffer.toString()
                execLatch.countDown()
            }
        )

        try {
            execLatch.await()
        } catch (_: Exception) {
            cancelHandle()
        }

        writeExitCodeFile(exitCode)
        val finalMsg = "\n" + "-".repeat(60) + "\n[AI $actionName COMPLETE: exit code $exitCode]\n"
        stream(finalMsg)
        logService.log(finalMsg, LogCategory.AI, profile.name)
        try { os.close() } catch (_: Exception) {}
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        val map = mutableMapOf<String, String>()
        val pairs = query.split('&')
        for (pair in pairs) {
            val idx = pair.indexOf('=')
            if (idx > 0) {
                val k = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8.name())
                val v = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8.name())
                map[k] = v
            } else if (pair.isNotBlank()) {
                val k = URLDecoder.decode(pair, StandardCharsets.UTF_8.name())
                map[k] = ""
            }
        }
        return map
    }

    private fun escapeJson(str: String): String {
        return str.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\b", "\\b")
            .replace("\u000C", "\\f")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    override fun dispose() {
        isRunning.set(false)
        try {
            server?.stop(0)
        } catch (_: Exception) {}
        cleanStateFiles()
        pool.shutdownNow()
    }

    companion object {
        fun getInstance(project: Project): RemoteFlowAgentBridgeService =
            project.getService(RemoteFlowAgentBridgeService::class.java)

        fun generateRfCmd(): String = """@echo off
setlocal enabledelayedexpansion

set "RF_DIR=%USERPROFILE%\.remote-flow"
set "STATE_DIR=%RF_DIR%\state"
set "CUR_DIR=%CD%"
set "CUR_DIR=%CUR_DIR:\=/%"

set "RF_PORT="

rem 1. Check project state files matching current working directory
if exist "%STATE_DIR%" (
    for %%F in ("%STATE_DIR%\project_*.properties") do (
        set "P_PATH="
        set "P_PORT="
        for /f "usebackq tokens=1,2 delims==" %%A in ("%%~F") do (
            if /i "%%A"=="PROJECT_PATH" set "P_PATH=%%B"
            if /i "%%A"=="PORT" set "P_PORT=%%B"
        )
        if defined P_PATH (
            echo !CUR_DIR!/ | findstr /i /c:"!P_PATH!/" >nul 2>&1
            if not errorlevel 1 (
                set "RF_PORT=!P_PORT!"
            )
        )
    )
)

rem 2. Fallback: if only one project state file exists, use its port
if not defined RF_PORT (
    if exist "%STATE_DIR%" (
        for %%F in ("%STATE_DIR%\project_*.properties") do (
            for /f "usebackq tokens=1,2 delims==" %%A in ("%%~F") do (
                if /i "%%A"=="PORT" set "RF_PORT=%%B"
            )
        )
    )
)

rem 3. Fallback: check legacy local port file if it exists
if not defined RF_PORT (
    if exist "%CD%\.remote-flow.port" (
        set /p RF_PORT=<"%CD%\.remote-flow.port"
        set "RF_PORT=!RF_PORT: =!"
    )
)

rem 4. Fallback: default port 45789
if not defined RF_PORT set "RF_PORT=45789"

rem Test bridge connection
curl.exe -s --connect-timeout 2 "http://127.0.0.1:!RF_PORT!/api/status" >nul 2>&1
if errorlevel 1 (
    echo [Remote Flow] Error: IntelliJ IDEA Remote Flow bridge is not running on port !RF_PORT!.
    echo Please ensure IntelliJ IDEA is open with Remote Flow active.
    exit /b 1
)

set "EXIT_FILE=%STATE_DIR%\exitcode_!RF_PORT!.txt"
if exist "!EXIT_FILE!" del /f /q "!EXIT_FILE!" >nul 2>&1

set "ACTION=%~1"
if "%ACTION%"=="" set "ACTION=status"

if /i "%ACTION%"=="test" (
    curl.exe -s -N -X POST "http://127.0.0.1:!RF_PORT!/api/test"
    goto :finish
)

if /i "%ACTION%"=="build" (
    curl.exe -s -N -X POST "http://127.0.0.1:!RF_PORT!/api/build"
    goto :finish
)

if /i "%ACTION%"=="sync" (
    curl.exe -s -N -X POST "http://127.0.0.1:!RF_PORT!/api/sync"
    goto :finish
)

if /i "%ACTION%"=="pull" (
    curl.exe -s -N -X POST "http://127.0.0.1:!RF_PORT!/api/pull"
    goto :finish
)

if /i "%ACTION%"=="diff" (
    set "TARGET_PATH=%~2"
    if "!TARGET_PATH!"=="" (
        curl.exe -s "http://127.0.0.1:!RF_PORT!/api/diff"
    ) else (
        curl.exe -s "http://127.0.0.1:!RF_PORT!/api/diff?path=!TARGET_PATH!"
    )
    echo.
    goto :finish
)

if /i "%ACTION%"=="mem" (
    set "MAX_MEM=%~2"
    if "!MAX_MEM!"=="" (
        curl.exe -s "http://127.0.0.1:!RF_PORT!/api/memory"
    ) else (
        curl.exe -s "http://127.0.0.1:!RF_PORT!/api/memory?max=!MAX_MEM!"
    )
    echo.
    goto :finish
)

if /i "%ACTION%"=="env" (
    curl.exe -s "http://127.0.0.1:!RF_PORT!/api/env"
    echo.
    goto :finish
)

if /i "%ACTION%"=="terminal" (
    curl.exe -s "http://127.0.0.1:!RF_PORT!/api/terminal"
    goto :finish
)

if /i "%ACTION%"=="profile" (
    set "SUBCMD=%~2"
    if /i "!SUBCMD!"=="start" (
        curl.exe -s -X POST "http://127.0.0.1:!RF_PORT!/api/profile/start"
        echo.
        goto :finish
    )
    if /i "!SUBCMD!"=="stop" (
        curl.exe -s -X POST "http://127.0.0.1:!RF_PORT!/api/profile/stop"
        echo.
        goto :finish
    )
    curl.exe -s "http://127.0.0.1:!RF_PORT!/api/profile/status"
    echo.
    goto :finish
)

if /i "%ACTION%"=="diagnostics" (
    curl.exe -s "http://127.0.0.1:!RF_PORT!/api/diagnostics"
    echo.
    goto :finish
)

if /i "%ACTION%"=="docker" (
    set "SUBCMD=%~2"
    if /i "!SUBCMD!"=="logs" (
        curl.exe -s "http://127.0.0.1:!RF_PORT!/api/docker?action=logs&name=%~3"
        goto :finish
    )
    if /i "!SUBCMD!"=="restart" (
        curl.exe -s -X POST "http://127.0.0.1:!RF_PORT!/api/docker?action=restart&name=%~3"
        echo.
        goto :finish
    )
    curl.exe -s "http://127.0.0.1:!RF_PORT!/api/docker?action=list"
    echo.
    goto :finish
)

if /i "%ACTION%"=="status" (
    curl.exe -s "http://127.0.0.1:!RF_PORT!/api/status"
    echo.
    goto :finish
)

if /i "%ACTION%"=="exec" (
    shift
    set "REM_ARGS="
    :loop
    if "%~1"=="" goto :endloop
    if defined REM_ARGS (set "REM_ARGS=!REM_ARGS! %~1") else (set "REM_ARGS=%~1")
    shift
    goto :loop
    :endloop
    curl.exe -s -N -X POST "http://127.0.0.1:!RF_PORT!/api/exec" --data-binary "!REM_ARGS!"
    goto :finish
)

echo [Remote Flow] Unknown command: %ACTION%
echo Usage: rf ^<test ^| build ^| sync ^| pull ^| diff [path] ^| mem [max] ^| env ^| terminal ^| profile [start^|stop^|status] ^| docker ^| diagnostics ^| exec ^<cmd^> ^| status^>
exit /b 1

:finish
if exist "!EXIT_FILE!" (
    set /p CODE=<"!EXIT_FILE!"
    del /f /q "!EXIT_FILE!" >nul 2>&1
    exit /b !CODE!
)
exit /b 0
"""

        fun generateRfPs1(): String = """param(
    [Parameter(Position=0, Mandatory=${'$'}false)]
    [string]${'$'}Action = "status",
    [Parameter(Position=1, ValueFromRemainingArguments=${'$'}true)]
    [string[]]${'$'}RemainingArgs
)

${'$'}rfDir = Join-Path ${'$'}env:USERPROFILE ".remote-flow"
${'$'}stateDir = Join-Path ${'$'}rfDir "state"
${'$'}curDir = (Get-Location).Path.Replace('\', '/').TrimEnd('/') + '/'
${'$'}port = ${'$'}null

if (Test-Path ${'$'}stateDir) {
    ${'$'}files = Get-ChildItem -Path ${'$'}stateDir -Filter "project_*.properties" -ErrorAction SilentlyContinue
    foreach (${'$'}f in ${'$'}files) {
        ${'$'}props = @{}
        Get-Content ${'$'}f.FullName -ErrorAction SilentlyContinue | ForEach-Object {
            ${'$'}idx = ${'$'}_.IndexOf('=')
            if (${'$'}idx -gt 0) {
                ${'$'}k = ${'$'}_.Substring(0, ${'$'}idx).Trim()
                ${'$'}v = ${'$'}_.Substring(${'$'}idx + 1).Trim()
                ${'$'}props[${'$'}k] = ${'$'}v
            }
        }
        ${'$'}pPath = ${'$'}props["PROJECT_PATH"]
        if (${'$'}pPath) {
            ${'$'}pNorm = ${'$'}pPath.Replace('\', '/').TrimEnd('/') + '/'
            if (${'$'}curDir.StartsWith(${'$'}pNorm, [System.StringComparison]::OrdinalIgnoreCase)) {
                ${'$'}port = ${'$'}props["PORT"]
                break
            }
        }
    }
    if (-not ${'$'}port -and ${'$'}files -and ${'$'}files.Count -eq 1) {
        ${'$'}line = Get-Content ${'$'}files[0].FullName -ErrorAction SilentlyContinue | Where-Object { ${'$'}_ -like "PORT=*" } | Select-Object -First 1
        if (${'$'}line) {
            ${'$'}port = (${'$'}line -split '=', 2)[1].Trim()
        }
    }
}

if (-not ${'$'}port) {
    ${'$'}localPort = Join-Path (Get-Location).Path ".remote-flow.port"
    if (Test-Path ${'$'}localPort) {
        ${'$'}port = (Get-Content ${'$'}localPort -Raw -ErrorAction SilentlyContinue).Trim()
    }
}

if (-not ${'$'}port) { ${'$'}port = "45789" }

${'$'}exitFile = Join-Path ${'$'}stateDir "exitcode_${'$'}port.txt"
if (Test-Path ${'$'}exitFile) { Remove-Item ${'$'}exitFile -Force -ErrorAction SilentlyContinue }

try {
    ${'$'}null = Invoke-WebRequest -Uri "http://127.0.0.1:${'$'}port/api/status" -UseBasicParsing -TimeoutSec 2 -ErrorAction Stop
} catch {
    Write-Error "[Remote Flow] Error: IntelliJ IDEA Remote Flow bridge is not running on port ${'$'}port. Please ensure IntelliJ is open with Remote Flow active."
    exit 1
}

switch (${'$'}Action.ToLower()) {
    "test" {
        & curl.exe -s -N -X POST "http://127.0.0.1:${'$'}port/api/test"
    }
    "build" {
        & curl.exe -s -N -X POST "http://127.0.0.1:${'$'}port/api/build"
    }
    "sync" {
        & curl.exe -s -N -X POST "http://127.0.0.1:${'$'}port/api/sync"
    }
    "pull" {
        & curl.exe -s -N -X POST "http://127.0.0.1:${'$'}port/api/pull"
    }
    "diff" {
        ${'$'}target = if (${'$'}RemainingArgs.Count -gt 0) { ${'$'}RemainingArgs[0] } else { "" }
        if (${'$'}target) {
            & curl.exe -s "http://127.0.0.1:${'$'}port/api/diff?path=${'$'}target"
        } else {
            & curl.exe -s "http://127.0.0.1:${'$'}port/api/diff"
        }
        Write-Host ""
    }
    "mem" {
        ${'$'}maxMem = if (${'$'}RemainingArgs.Count -gt 0) { ${'$'}RemainingArgs[0] } else { "" }
        if (${'$'}maxMem) {
            & curl.exe -s "http://127.0.0.1:${'$'}port/api/memory?max=${'$'}maxMem"
        } else {
            & curl.exe -s "http://127.0.0.1:${'$'}port/api/memory"
        }
        Write-Host ""
    }
    "env" {
        & curl.exe -s "http://127.0.0.1:${'$'}port/api/env"
        Write-Host ""
    }
    "terminal" {
        & curl.exe -s "http://127.0.0.1:${'$'}port/api/terminal"
    }
    "profile" {
        ${'$'}sub = if (${'$'}RemainingArgs.Count -gt 0) { ${'$'}RemainingArgs[0].ToLower() } else { "status" }
        if (${'$'}sub -eq "start") {
            & curl.exe -s -X POST "http://127.0.0.1:${'$'}port/api/profile/start"
            Write-Host ""
        } elseif (${'$'}sub -eq "stop") {
            & curl.exe -s -X POST "http://127.0.0.1:${'$'}port/api/profile/stop"
            Write-Host ""
        } else {
            & curl.exe -s "http://127.0.0.1:${'$'}port/api/profile/status"
            Write-Host ""
        }
    }
    "diagnostics" {
        & curl.exe -s "http://127.0.0.1:${'$'}port/api/diagnostics"
        Write-Host ""
    }
    "docker" {
        ${'$'}sub = if (${'$'}RemainingArgs.Count -gt 0) { ${'$'}RemainingArgs[0].ToLower() } else { "list" }
        ${'$'}tName = if (${'$'}RemainingArgs.Count -gt 1) { ${'$'}RemainingArgs[1] } else { "" }
        if (${'$'}sub -eq "logs") {
            & curl.exe -s "http://127.0.0.1:${'$'}port/api/docker?action=logs&name=${'$'}tName"
        } elseif (${'$'}sub -eq "restart") {
            & curl.exe -s -X POST "http://127.0.0.1:${'$'}port/api/docker?action=restart&name=${'$'}tName"
            Write-Host ""
        } else {
            & curl.exe -s "http://127.0.0.1:${'$'}port/api/docker?action=list"
            Write-Host ""
        }
    }
    "status" {
        & curl.exe -s "http://127.0.0.1:${'$'}port/api/status"
        Write-Host ""
    }
    "exec" {
        ${'$'}cmd = ${'$'}RemainingArgs -join " "
        & curl.exe -s -N -X POST "http://127.0.0.1:${'$'}port/api/exec" --data-binary "${'$'}cmd"
    }
    default {
        Write-Host "Usage: rf <test | build | sync | pull | diff [path] | mem [max] | env | terminal | profile [start|stop|status] | docker | diagnostics | exec <command> | status>"
        exit 1
    }
}

if (Test-Path ${'$'}exitFile) {
    ${'$'}code = (Get-Content ${'$'}exitFile -Raw -ErrorAction SilentlyContinue).Trim()
    Remove-Item ${'$'}exitFile -Force -ErrorAction SilentlyContinue
    exit [int]${'$'}code
}
exit 0
"""

        fun generateRfBash(): String = """#!/usr/bin/env bash
RF_DIR="${'$'}{HOME}/.remote-flow"
STATE_DIR="${'$'}{RF_DIR}/state"
CUR_DIR="$(pwd)"
PORT=""

if [ -d "${'$'}STATE_DIR" ]; then
    for f in "${'$'}STATE_DIR"/project_*.properties; do
        [ -e "${'$'}f" ] || continue
        P_PATH=$(grep "^PROJECT_PATH=" "${'$'}f" | cut -d'=' -f2-)
        P_PORT=$(grep "^PORT=" "${'$'}f" | cut -d'=' -f2-)
        if [[ "${'$'}CUR_DIR" == "${'$'}P_PATH"* ]]; then
            PORT="${'$'}P_PORT"
            break
        fi
    done
    if [ -z "${'$'}PORT" ]; then
        for f in "${'$'}STATE_DIR"/project_*.properties; do
            [ -e "${'$'}f" ] || continue
            PORT=$(grep "^PORT=" "${'$'}f" | cut -d'=' -f2-)
            break
        done
    fi
fi

if [ -z "${'$'}PORT" ] && [ -f "$(pwd)/.remote-flow.port" ]; then
    PORT=$(cat "$(pwd)/.remote-flow.port" | tr -d ' \r\n')
fi

[ -z "${'$'}PORT" ] && PORT="45789"

EXIT_FILE="${'$'}STATE_DIR/exitcode_${'$'}PORT.txt"
rm -f "${'$'}EXIT_FILE" 2>/dev/null

if ! curl -s --connect-timeout 2 "http://127.0.0.1:${'$'}PORT/api/status" >/dev/null 2>&1; then
    echo "[Remote Flow] Error: IntelliJ IDEA Remote Flow bridge is not running on port ${'$'}PORT."
    echo "Please ensure IntelliJ IDEA is open with Remote Flow active."
    exit 1
fi

ACTION="${'$'}{1:-status}"
case "${'$'}ACTION" in
    test|build|sync|pull)
        curl -s -N -X POST "http://127.0.0.1:${'$'}PORT/api/${'$'}ACTION"
        ;;
    diff)
        shift
        TARGET="${'$'}1"
        if [ -n "${'$'}TARGET" ]; then
            curl -s "http://127.0.0.1:${'$'}PORT/api/diff?path=${'$'}TARGET"
        else
            curl -s "http://127.0.0.1:${'$'}PORT/api/diff"
        fi
        echo ""
        ;;
    mem)
        shift
        MAX="${'$'}1"
        if [ -n "${'$'}MAX" ]; then
            curl -s "http://127.0.0.1:${'$'}PORT/api/memory?max=${'$'}MAX"
        else
            curl -s "http://127.0.0.1:${'$'}PORT/api/memory"
        fi
        echo ""
        ;;
    env)
        curl -s "http://127.0.0.1:${'$'}PORT/api/env"
        echo ""
        ;;
    terminal)
        curl -s "http://127.0.0.1:${'$'}PORT/api/terminal"
        ;;
    profile)
        shift
        SUB="${'$'}{1:-status}"
        if [ "${'$'}SUB" = "start" ]; then
            curl -s -X POST "http://127.0.0.1:${'$'}PORT/api/profile/start"
            echo ""
        elif [ "${'$'}SUB" = "stop" ]; then
            curl -s -X POST "http://127.0.0.1:${'$'}PORT/api/profile/stop"
            echo ""
        else
            curl -s "http://127.0.0.1:${'$'}PORT/api/profile/status"
            echo ""
        fi
        ;;
    diagnostics)
        curl -s "http://127.0.0.1:${'$'}PORT/api/diagnostics"
        echo ""
        ;;
    docker)
        shift
        SUB="${'$'}{1:-list}"
        TARGET="${'$'}2"
        if [ "${'$'}SUB" = "logs" ]; then
            curl -s "http://127.0.0.1:${'$'}PORT/api/docker?action=logs&name=${'$'}TARGET"
        elif [ "${'$'}SUB" = "restart" ]; then
            curl -s -X POST "http://127.0.0.1:${'$'}PORT/api/docker?action=restart&name=${'$'}TARGET"
            echo ""
        else
            curl -s "http://127.0.0.1:${'$'}PORT/api/docker?action=list"
            echo ""
        fi
        ;;
    status)
        curl -s "http://127.0.0.1:${'$'}PORT/api/status"
        echo ""
        ;;
    exec)
        shift
        curl -s -N -X POST "http://127.0.0.1:${'$'}PORT/api/exec" --data-binary "${'$'}*"
        ;;
    *)
        echo "Usage: rf <test|build|sync|pull|diff [path]|mem [max]|env|terminal|profile [start|stop|status]|docker|diagnostics|exec <cmd>|status>"
        exit 1
        ;;
esac

if [ -f "${'$'}EXIT_FILE" ]; then
    CODE=$(cat "${'$'}EXIT_FILE" | tr -d ' \r\n')
    rm -f "${'$'}EXIT_FILE" 2>/dev/null
    exit "${'$'}CODE"
fi
exit 0
"""

        fun generateAgentsMd(): String = """# AI Coding Agent Instructions for Remote Flow

This project is developed locally, but its runtime environment, databases (PostgreSQL, Redis, RabbitMQ, etc.), and services reside on a remote Linux server managed by the **Remote Flow** IntelliJ IDEA plugin.

## ⚠️ CRITICAL INSTRUCTION FOR ALL AI CODING AGENTS:
- ❌ **NEVER run `./gradlew test`, `./gradlew build`, or `./mvnw` locally** on Windows terminal. They will fail because remote databases, environment configs, and Linux containers are not running locally.
- ✅ **ALWAYS use the Remote Flow CLI (`rf`)** to trigger tests, builds, and commands directly on the remote server:
  - Run Tests: `.\rf.cmd test` (PowerShell: `.\rf.ps1 test`, Bash: `./rf test`)
  - Run Build: `.\rf.cmd build` (PowerShell: `.\rf.ps1 build`, Bash: `./rf build`)
  - Sync Files: `.\rf.cmd sync`
  - Pull Remote Files: `.\rf.cmd pull`
  - Compare Diff with Server: `.\rf.cmd diff [path]` (PowerShell: `.\rf.ps1 diff [path]`, Bash: `./rf diff [path]`)
  - Configure Remote Memory: `.\rf.cmd mem [maxHeap]` (e.g. `.\rf.cmd mem 4g`)
  - View Environment Variables: `.\rf.cmd env`
  - Open Remote SSH Terminal: `.\rf.cmd terminal`
  - Performance Profiler (CPU & RAM): `.\rf.cmd profile [start|stop|status]`
  - Manage Remote Docker: `.\rf.cmd docker` / `.\rf.cmd docker logs <container>`
  - Get Error Diagnostics: `.\rf.cmd diagnostics`
  - Execute Remote Command: `.\rf.cmd exec "<command>"`
  - Check Status: `.\rf.cmd status`
  - Model Context Protocol (MCP): Connect via `http://127.0.0.1:45789/api/mcp` for native AI agent tool execution.

The CLI communicates with the active IntelliJ IDEA Remote Flow bridge, automatically differential-syncs all modified files to the remote server, runs the command inside the server environment, and streams live colorized output and exit codes back to your terminal.

## 🌐 MANDATORY LANGUAGE AND LOCALIZATION RULE:
- 🔤 **ALL plugin UI texts, dialogs, labels, button texts, notifications, error messages, status logs, tooltips, action descriptions, code comments, and documentation MUST BE STRICTLY 100% IN ENGLISH.**
- ❌ **NEVER write Uzbek, Russian, or any non-English language** inside the plugin codebase, UI dialogs, notifications, logs, XML configs, CLI tools, or documentation. The plugin is built for global developers on the JetBrains Marketplace.
"""
    }
}
