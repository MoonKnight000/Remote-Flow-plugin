package uz.remote.flow.execution

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RemoteConnection
import com.intellij.execution.configurations.RemoteConnectionCreator
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import uz.remote.flow.logging.LogCategory
import uz.remote.flow.logging.RemoteFlowLogService
import uz.remote.flow.run.RemoteFlowRunConfiguration
import uz.remote.flow.settings.RemoteFlowSettings
import uz.remote.flow.ssh.ForwardDirection
import uz.remote.flow.ssh.PortMapping
import uz.remote.flow.ssh.RemoteConnectionManager
import uz.remote.flow.ssh.RemoteSafetyHelper
import uz.remote.flow.ssh.ServerProfile
import uz.remote.flow.sync.FastSyncManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Execution state for Remote Flow run and debug configurations.
 * Implements RemoteConnectionCreator to seamlessly hook into IntelliJ's native JDWP Debugger,
 * giving full access to breakpoints, stepping (F7/F8/F9), variable inspection, evaluate expression,
 * watches, frames, and HotSwap without losing any IDE features.
 */
class RemoteFlowRunProfileState(
    val environment: ExecutionEnvironment,
    val isDebug: Boolean
) : RunProfileState, RemoteConnectionCreator {

    val project: Project get() = environment.project

    /**
     * Resolves the target debug port from configuration or commands, defaulting to standard 5005.
     */
    val debugPort: Int
        get() {
            val runConfig = environment.runProfile as? RemoteFlowRunConfiguration
            val settings = RemoteFlowSettings.getInstance(project)
            val p = resolveProfile(runConfig, settings)
            val cmd = if (runConfig != null && runConfig.debugCommand.isNotBlank()) {
                runConfig.debugCommand.trim()
            } else {
                p?.debugCommand ?: ""
            }
            return extractDebugPort(cmd)
        }

    override fun createRemoteConnection(environment: ExecutionEnvironment): RemoteConnection {
        return RemoteConnection(true, "127.0.0.1", debugPort.toString(), false)
    }

    override fun isPollConnection(): Boolean = true

    override fun execute(executor: Executor?, runner: ProgramRunner<*>): ExecutionResult? {
        val settings = RemoteFlowSettings.getInstance(project)
        val connMgr = RemoteConnectionManager.getInstance(project)
        val logService = RemoteFlowLogService.getInstance(project)
        val runConfig = environment.runProfile as? RemoteFlowRunConfiguration

        val p = resolveProfile(runConfig, settings)
        if (p == null || p.host.isBlank()) {
            connMgr.notifyUser("Remote Flow", "No active server profile configured!", NotificationType.WARNING)
            return null
        }

        val modeName = if (isDebug) "Remote Debug" else "Remote Run"
        if (!RemoteSafetyHelper.checkProductionSafe(project, p, modeName)) {
            return null
        }

        val stackPreset = uz.remote.flow.stack.ProjectStackDetector.detect(java.io.File(project.basePath ?: ""))
        val rawCmd = if (isDebug) {
            if (runConfig != null && runConfig.debugCommand.isNotBlank()) {
                runConfig.debugCommand.trim()
            } else {
                p.debugCommand.ifBlank { stackPreset.debugCommand.ifBlank { "./gradlew bootRun --debug-jvm" } }
            }
        } else {
            if (runConfig != null && runConfig.runCommand.isNotBlank()) {
                runConfig.runCommand.trim()
            } else {
                p.runCommand.ifBlank { stackPreset.runCommand.ifBlank { "./gradlew bootRun" } }
            }
        }

        val workingDir = if (runConfig != null && runConfig.remoteWorkingDir.isNotBlank()) {
            runConfig.remoteWorkingDir.trim()
        } else {
            p.remoteProjectPath
        }
        val shouldSync = runConfig?.autoSync ?: true
        val shouldForward = runConfig?.forwardPorts ?: true

        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        val processHandler = RemoteFlowProcessHandler(project, p, rawCmd)
        console.attachToProcess(processHandler)

        processHandler.startNotify()

        // Attach Remote Performance Profiler Panel (Live CPU/Heap graphs & recording)
        val profilerManager = uz.remote.flow.profiler.RemoteProfilerManager.getInstance(project)
        val performancePanel = uz.remote.flow.ui.RemotePerformancePanel(project, profilerManager, processHandler)
        val executionConsole = RemoteFlowExecutionConsole(console, performancePanel)

        // Automatically open Remote Flow log console when execution starts
        logService.showLogWindow()

        // Trigger background SSH execution and port forwarding
        startBackgroundExecution(
            p = p,
            rawCmd = rawCmd,
            workingDir = workingDir,
            shouldSync = shouldSync,
            shouldForward = shouldForward,
            processHandler = processHandler,
            logService = logService,
            connMgr = connMgr
        )

        return DefaultExecutionResult(executionConsole, processHandler)
    }

    private fun startBackgroundExecution(
        p: ServerProfile,
        rawCmd: String,
        workingDir: String,
        shouldSync: Boolean,
        shouldForward: Boolean,
        processHandler: RemoteFlowProcessHandler,
        logService: RemoteFlowLogService,
        connMgr: RemoteConnectionManager
    ) {
        val syncMgr = FastSyncManager(project)
        val cmd = uz.remote.flow.ssh.buildRemoteExecutionCommand(rawCmd, p.javaHome, p)
        val port = debugPort

        val doRun = {
            if (isDebug) {
                // Ensure local forward for debug port is active immediately
                connMgr.startSingleForward(
                    PortMapping(
                        localPort = port,
                        remotePort = port,
                        serviceName = "JVM Debug ($port)",
                        direction = ForwardDirection.LOCAL_TO_REMOTE
                    )
                )
                val dbgMsg = "[REMOTE DEBUG] 🪲 Forwarding JVM Debug port $port (127.0.0.1:$port -> ${p.name}:$port)...\n"
                processHandler.printSystem(dbgMsg)
                logService.log(dbgMsg, LogCategory.RUN, p.name)
            }

            val detectedAppPort = java.util.concurrent.atomic.AtomicInteger(
                uz.remote.flow.ssh.DynamicPortDetector.resolveInitialAppPort(project, rawCmd, 0)
            )

            if (shouldForward) {
                connMgr.startPortForwarding(p)
                val initialPort = detectedAppPort.get()
                if (initialPort > 0) {
                    connMgr.forwardAppPort(initialPort)
                    val initMsg = "[PORT FORWARD] 🔀 Remote application port :$initialPort forwarded to http://localhost:$initialPort\n"
                    processHandler.printSystem(initMsg)
                    logService.log(initMsg, LogCategory.RUN, p.name)
                }
            }

            val runAfterSync = {
                val javaInfo = if (p.javaHome.isNotBlank()) " (Java: ${p.javaHome})" else ""
                val execMsg = "[REMOTE FLOW] Executing on server$javaInfo: $rawCmd\n"
                processHandler.printSystem(execMsg)
                logService.log(execMsg, LogCategory.RUN, p.name)

                val readyNotified = AtomicBoolean(false)

                connMgr.executeRemoteCommand(
                    cmd = cmd,
                    workingDir = workingDir,
                    isLongRunning = true,
                    onOutput = { line ->
                        processHandler.printOutput(line)
                        logService.log(line, LogCategory.RUN, p.name)

                        // Detect dynamic app port from stdout log (Spring Boot, Tomcat, Netty, etc.)
                        val portInLog = uz.remote.flow.ssh.DynamicPortDetector.extractPortFromLine(line)
                        if (portInLog != null && portInLog != detectedAppPort.get()) {
                            detectedAppPort.set(portInLog)
                            connMgr.forwardAppPort(portInLog)
                            val forwardMsg = "[PORT FORWARD] 🔀 Remote application port :$portInLog detected! Forwarded locally to http://localhost:$portInLog\n"
                            processHandler.printSystem(forwardMsg)
                            logService.log(forwardMsg, LogCategory.RUN, p.name)
                        }

                        if (isDebug && (line.contains("Listening for transport dt_socket at address:") || line.contains("dt_socket"))) {
                            val readyDbg = "[REMOTE DEBUG READY] 🚀 Remote JVM listening for debugger. IntelliJ debugger connecting to 127.0.0.1:$port...\n"
                            processHandler.printSystem(readyDbg)
                            logService.log(readyDbg, LogCategory.RUN, p.name)
                        }

                        if (!readyNotified.get() && uz.remote.flow.ssh.DynamicPortDetector.isAppReadyLine(line)) {
                            readyNotified.set(true)
                            val finalPort = if (detectedAppPort.get() > 0) detectedAppPort.get() else (portInLog ?: 8080)
                            connMgr.forwardAppPort(finalPort)
                            val targetUrl = uz.remote.flow.ssh.DynamicPortDetector.buildTargetUrl(p.browserUrl, finalPort)
                            val readyMsg = "[APP READY] 🚀 Application is ready! Accessible locally at: $targetUrl\n"
                            processHandler.printSystem(readyMsg)
                            logService.log(readyMsg, LogCategory.RUN, p.name)

                            if (p.openBrowserOnReady) {
                                ApplicationManager.getApplication().invokeLater {
                                    try {
                                        com.intellij.ide.BrowserUtil.browse(targetUrl)
                                    } catch (_: Throwable) {}
                                }
                            }

                            if (p.postRunCommand.isNotBlank()) {
                                processHandler.printSystem("[POST-RUN HOOK] Running post-run hook: ${p.postRunCommand}\n")
                                val postCmd = uz.remote.flow.ssh.buildRemoteExecutionCommand(p.postRunCommand, p.javaHome, p)
                                connMgr.executeRemoteCommand(postCmd, workingDir, false, { processHandler.printOutput(it) }, {})
                            }
                        }
                    },
                    onComplete = { code ->
                        connMgr.stopAppPortForward()
                        val finishMsg = "[REMOTE FLOW FINISHED] Exit code: $code\n"
                        processHandler.printSystem(finishMsg)
                        logService.log(finishMsg, LogCategory.RUN, p.name)
                        if (code == 137) {
                            connMgr.notifyUser(
                                "Remote Flow: Process Crashed (OOMKilled)",
                                "Application on '${p.name}' was killed by the OS (Exit code 137 / Out of Memory). Consider increasing memory via Remote Memory Tuning.",
                                NotificationType.ERROR
                            )
                        } else if (code != 0) {
                            connMgr.notifyUser(
                                "Remote Flow: Execution Finished with Errors",
                                "Application on server ${p.name} exited with code $code.",
                                NotificationType.WARNING
                            )
                        } else {
                            connMgr.notifyUser(
                                "Remote Flow: Execution Finished",
                                "Application completed on server ${p.name} successfully (Exit code 0).",
                                NotificationType.INFORMATION
                            )
                        }
                        processHandler.finishProcess(code)
                    }
                )
            }

            val runWithPreHook = {
                if (p.preRunCommand.isNotBlank()) {
                    processHandler.printSystem("[PRE-RUN HOOK] Executing pre-run hook: ${p.preRunCommand}\n")
                    val preCmd = uz.remote.flow.ssh.buildRemoteExecutionCommand(p.preRunCommand, p.javaHome, p)
                    connMgr.executeRemoteCommand(
                        cmd = preCmd,
                        workingDir = workingDir,
                        isLongRunning = false,
                        onOutput = { processHandler.printOutput(it) },
                        onComplete = { code ->
                            if (code != 0) {
                                processHandler.printOutput("[PRE-RUN WARNING] Pre-run hook returned exit code: $code\n", true)
                            }
                            runAfterSync()
                        }
                    )
                } else {
                    runAfterSync()
                }
            }

            if (shouldSync) {
                processHandler.printSystem("[REMOTE FLOW] 1. Syncing latest code to server ${p.name}...\n")
                syncMgr.syncSingleServer(
                    profile = p,
                    onLog = { processHandler.printOutput(it) },
                    onComplete = { success ->
                        if (!success) {
                            processHandler.printOutput("[REMOTE FLOW WARNING] Sync warning occurred, proceeding with command...\n", true)
                        }
                        runWithPreHook()
                    }
                )
            } else {
                processHandler.printSystem("[REMOTE FLOW] Skipping code sync (disabled in configuration)...\n")
                runWithPreHook()
            }
        }

        val startOrRestart = {
            if (connMgr.isProcessRunning) {
                processHandler.printSystem("[REMOTE FLOW] Restarting application: stopping existing instance...\n")
                connMgr.stopRemoteProcess(p, onOutput = { processHandler.printOutput(it) }) {
                    connMgr.activeProcessHandler = processHandler
                    doRun()
                }
            } else {
                connMgr.activeProcessHandler = processHandler
                doRun()
            }
        }

        if (!connMgr.isConnected) {
            processHandler.printSystem("[CONNECT] Connecting to ${p.name} (${p.host}:${p.port})...\n")
            connMgr.connect(
                profile = p,
                onSuccess = { startOrRestart() },
                onError = { err ->
                    processHandler.printOutput("[ERROR] Connection failed: ${err.message}\n", true)
                    connMgr.notifyUser("Remote Flow: Connection Failed", "Could not connect to ${p.name}: ${err.message}", NotificationType.ERROR)
                    processHandler.finishProcess(-1)
                }
            )
        } else {
            startOrRestart()
        }
    }

    companion object {
        fun resolveProfile(runConfig: RemoteFlowRunConfiguration?, settings: RemoteFlowSettings): ServerProfile? {
            return if (runConfig != null && runConfig.serverProfileName.isNotBlank() && runConfig.serverProfileName != "[Active Server Profile]") {
                settings.profiles.find { it.name.equals(runConfig.serverProfileName, ignoreCase = true) } ?: settings.activeProfileOrNull
            } else {
                settings.activeProfileOrNull
            }
        }

        fun extractDebugPort(cmd: String): Int {
            if (cmd.isBlank()) return 5005
            val regexPatterns = listOf(
                Regex("""address=(?:.*:)?(\d+)"""),
                Regex("""(?:debug[-.]port|port)=(\d+)"""),
                Regex("""--debug-jvm=(\d+)""")
            )
            for (r in regexPatterns) {
                val match = r.find(cmd)
                if (match != null) {
                    val p = match.groupValues[1].toIntOrNull()
                    if (p != null && p in 1..65535) return p
                }
            }
            return 5005
        }
    }
}
