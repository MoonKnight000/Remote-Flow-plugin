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
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.PROJECT)
class RemoteFlowAgentBridgeService(private val project: Project) : Disposable {

    private var server: HttpServer? = null
    private val isRunning = AtomicBoolean(false)
    private val pool = Executors.newCachedThreadPool()

    private val connectionManager get() = RemoteConnectionManager.getInstance(project)
    private val syncManager get() = FastSyncManager(project)
    private val logService get() = RemoteFlowLogService.getInstance(project)

    @Synchronized
    fun start() {
        if (isRunning.get() || project.isDisposed) return

        pool.submit {
            try {
                val http = try {
                    HttpServer.create(InetSocketAddress("127.0.0.1", 45789), 0)
                } catch (_: Exception) {
                    HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
                }

                http.executor = Executors.newCachedThreadPool()

                http.createContext("/api/status", StatusHandler())
                http.createContext("/api/test", TestHandler())
                http.createContext("/api/build", BuildHandler())
                http.createContext("/api/sync", SyncHandler())
                http.createContext("/api/exec", ExecHandler())
                http.createContext("/api/stop", StopHandler())

                http.start()
                server = http
                isRunning.set(true)

                val assignedPort = http.address.port
                writePortFile(assignedPort)
                ensureCliHelperFiles()

                logService.log(
                    "[AI BRIDGE] Started local bridge server on port $assignedPort. CLI scripts (rf, rf.cmd, rf.ps1) are ready.",
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

    private fun writePortFile(port: Int) {
        val base = project.basePath ?: return
        try {
            val portFile = File(base, ".remote-flow.port")
            portFile.writeText(port.toString(), StandardCharsets.UTF_8)
            portFile.deleteOnExit()
        } catch (_: Exception) {}
    }

    private fun writeExitCodeFile(code: Int) {
        val base = project.basePath ?: return
        try {
            val exitFile = File(base, ".remote-flow.exitcode")
            exitFile.writeText(code.toString(), StandardCharsets.UTF_8)
        } catch (_: Exception) {}
    }

    private fun cleanPortFiles() {
        val base = project.basePath ?: return
        try {
            File(base, ".remote-flow.port").delete()
            File(base, ".remote-flow.exitcode").delete()
        } catch (_: Exception) {}
    }

    fun ensureCliHelperFiles() {
        val base = project.basePath ?: return
        try {
            val rfCmd = File(base, "rf.cmd")
            if (!rfCmd.exists()) {
                rfCmd.writeText(generateRfCmd(), StandardCharsets.UTF_8)
            }

            val rfPs1 = File(base, "rf.ps1")
            if (!rfPs1.exists()) {
                rfPs1.writeText(generateRfPs1(), StandardCharsets.UTF_8)
            }

            val rfBash = File(base, "rf")
            if (!rfBash.exists()) {
                rfBash.writeText(generateRfBash(), StandardCharsets.UTF_8)
                rfBash.setExecutable(true, false)
            }

            val agentsMd = File(base, "AGENTS.md")
            if (!agentsMd.exists()) {
                agentsMd.writeText(generateAgentsMd(), StandardCharsets.UTF_8)
            }

            val rulesDir = File(base, ".antigravity/rules")
            if (!rulesDir.exists()) {
                rulesDir.mkdirs()
            }
            val ruleFile = File(rulesDir, "remote-execution.md")
            if (!ruleFile.exists()) {
                ruleFile.writeText(generateAgentsMd(), StandardCharsets.UTF_8)
            }
        } catch (_: Exception) {}
    }

    private inner class StatusHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            val profile = connectionManager.config.activeProfile
            val isConn = connectionManager.isConnected
            val json = buildString {
                append("{")
                append("\"status\":\"ok\",")
                append("\"project\":\"").append(escapeJson(project.name)).append("\",")
                append("\"connected\":").append(isConn).append(",")
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
            val fullCmd = buildRemoteExecutionCommand(rawCmd, profile.javaHome)

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

        val fullCmd = buildRemoteExecutionCommand(rawCmd, profile.javaHome)
        stream("\n[AI $actionName] Executing on remote server: $rawCmd\n" + "-".repeat(60) + "\n\n")
        logService.log("[AI $actionName] Executing: $rawCmd", LogCategory.AI, profile.name)

        // 4. Stream command execution
        val execLatch = CountDownLatch(1)
        var exitCode = 0

        val cancelHandle = connectionManager.executeStreamingCommand(
            cmd = fullCmd,
            workingDir = profile.remoteProjectPath,
            timeoutSeconds = 900,
            onOutput = { out ->
                stream(out)
                logService.log(out, LogCategory.AI, profile.name)
            },
            onComplete = { code ->
                exitCode = code
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
        cleanPortFiles()
        pool.shutdownNow()
    }

    companion object {
        fun getInstance(project: Project): RemoteFlowAgentBridgeService =
            project.getService(RemoteFlowAgentBridgeService::class.java)

        fun generateRfCmd(): String = """@echo off
setlocal enabledelayedexpansion

set "DIR=%~dp0"
set "PORT_FILE=%DIR%.remote-flow.port"
set "EXIT_FILE=%DIR%.remote-flow.exitcode"

if not exist "%PORT_FILE%" (
    echo [Remote Flow] Error: IntelliJ IDEA Remote Flow bridge is not running.
    echo Please ensure IntelliJ IDEA is open with Remote Flow active.
    exit /b 1
)

set /p RF_PORT=<"%PORT_FILE%"
set "RF_PORT=!RF_PORT: =!"

set "ACTION=%~1"
if "%ACTION%"=="" set "ACTION=status"

if exist "%EXIT_FILE%" del /f /q "%EXIT_FILE%" >nul 2>&1

if /i "%ACTION%"=="test" (
    curl.exe -s -N -X POST "http://127.0.0.1:%RF_PORT%/api/test"
    goto :finish
)

if /i "%ACTION%"=="build" (
    curl.exe -s -N -X POST "http://127.0.0.1:%RF_PORT%/api/build"
    goto :finish
)

if /i "%ACTION%"=="sync" (
    curl.exe -s -N -X POST "http://127.0.0.1:%RF_PORT%/api/sync"
    goto :finish
)

if /i "%ACTION%"=="status" (
    curl.exe -s "http://127.0.0.1:%RF_PORT%/api/status"
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
    curl.exe -s -N -X POST "http://127.0.0.1:%RF_PORT%/api/exec" --data-binary "!REM_ARGS!"
    goto :finish
)

echo [Remote Flow] Unknown command: %ACTION%
echo Usage: rf ^<test ^| build ^| sync ^| exec ^<command^> ^| status^>
exit /b 1

:finish
if exist "%EXIT_FILE%" (
    set /p CODE=<"%EXIT_FILE%"
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

${'$'}portFile = Join-Path ${'$'}PSScriptRoot ".remote-flow.port"
${'$'}exitFile = Join-Path ${'$'}PSScriptRoot ".remote-flow.exitcode"

if (-not (Test-Path ${'$'}portFile)) {
    Write-Error "[Remote Flow] Error: IntelliJ IDEA Remote Flow bridge is not running. Please ensure IntelliJ is open with Remote Flow active."
    exit 1
}

${'$'}port = (Get-Content ${'$'}portFile -Raw).Trim()
if (Test-Path ${'$'}exitFile) { Remove-Item ${'$'}exitFile -Force -ErrorAction SilentlyContinue }

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
    "status" {
        & curl.exe -s "http://127.0.0.1:${'$'}port/api/status"
        Write-Host ""
    }
    "exec" {
        ${'$'}cmd = ${'$'}RemainingArgs -join " "
        & curl.exe -s -N -X POST "http://127.0.0.1:${'$'}port/api/exec" --data-binary "${'$'}cmd"
    }
    default {
        Write-Host "Usage: .\rf.ps1 <test | build | sync | exec <command> | status>"
        exit 1
    }
}

if (Test-Path ${'$'}exitFile) {
    ${'$'}code = (Get-Content ${'$'}exitFile -Raw).Trim()
    exit [int]${'$'}code
}
exit 0
"""

        fun generateRfBash(): String = """#!/usr/bin/env bash
DIR="${'$'}(cd "${'$'}(dirname "${'$'}{BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
PORT_FILE="${'$'}DIR/.remote-flow.port"
EXIT_FILE="${'$'}DIR/.remote-flow.exitcode"

if [ ! -f "${'$'}PORT_FILE" ]; then
    echo "[Remote Flow] Error: IntelliJ IDEA Remote Flow bridge is not running."
    exit 1
fi

PORT=${'$'}(cat "${'$'}PORT_FILE" | tr -d ' \r\n')
rm -f "${'$'}EXIT_FILE" 2>/dev/null

ACTION="${'$'}{1:-status}"
case "${'$'}ACTION" in
    test|build|sync)
        curl -s -N -X POST "http://127.0.0.1:${'$'}PORT/api/${'$'}ACTION"
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
        echo "Usage: rf <test|build|sync|exec <cmd>|status>"
        exit 1
        ;;
esac

if [ -f "${'$'}EXIT_FILE" ]; then
    CODE=${'$'}(cat "${'$'}EXIT_FILE" | tr -d ' \r\n')
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
  - Execute Remote Command: `.\rf.cmd exec "<command>"`
  - Check Status: `.\rf.cmd status`

The CLI communicates with the active IntelliJ IDEA Remote Flow bridge, automatically differential-syncs all modified files to the remote server, runs the command inside the server environment, and streams live colorized output and exit codes back to your terminal.
"""
    }
}
