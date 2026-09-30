package uz.remote.flow.execution

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.runners.RunContentBuilder
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import uz.remote.flow.logging.LogCategory
import uz.remote.flow.logging.RemoteFlowLogService
import uz.remote.flow.run.RemoteFlowRunConfiguration
import uz.remote.flow.settings.RemoteFlowSettings
import uz.remote.flow.ssh.ForwardDirection
import uz.remote.flow.ssh.PortMapping
import uz.remote.flow.ssh.RemoteConnectionManager
import uz.remote.flow.ssh.RemoteSafetyHelper
import uz.remote.flow.sync.FastSyncManager
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Handles Remote Flow run configurations and intercepts standard IntelliJ Run/Debug requests
 * when routeStandardRunToRemote is enabled.
 * Integrates natively with IntelliJ's ProcessHandler, Run/Debug tool window, and Stop/Rerun toolbar controls.
 */
class RemoteFlowProgramRunner : ProgramRunner<RunnerSettings> {

    override fun getRunnerId(): String = "RemoteFlowProgramRunner"

    override fun canRun(executorId: String, profile: RunProfile): Boolean {
        if (executorId != DefaultRunExecutor.EXECUTOR_ID && executorId != DefaultDebugExecutor.EXECUTOR_ID) {
            return false
        }
        // Always support RemoteFlowRunConfiguration
        if (profile is RemoteFlowRunConfiguration) {
            return true
        }
        val project = (profile as? RunConfiguration)?.project ?: return false
        val settings = RemoteFlowSettings.getInstance(project)
        if (!settings.routeStandardRunToRemote) return false
        val p = settings.activeProfileOrNull ?: return false
        return p.host.isNotBlank()
    }

    override fun execute(environment: ExecutionEnvironment) {
        val project = environment.project
        val isDebug = environment.executor.id == DefaultDebugExecutor.EXECUTOR_ID
        val runConfig = environment.runProfile as? RemoteFlowRunConfiguration
        val settings = RemoteFlowSettings.getInstance(project)
        val connMgr = RemoteConnectionManager.getInstance(project)

        val p = if (runConfig != null && runConfig.serverProfileName.isNotBlank() && runConfig.serverProfileName != "[Active Server Profile]") {
            settings.profiles.find { it.name.equals(runConfig.serverProfileName, ignoreCase = true) } ?: settings.activeProfileOrNull
        } else {
            settings.activeProfileOrNull
        }

        if (p == null || p.host.isBlank()) {
            connMgr.notifyUser("Remote Flow", "No active server profile configured!", NotificationType.WARNING)
            return
        }

        if (!RemoteSafetyHelper.checkProductionSafe(project, p, if (isDebug) "Remote Debug" else "Remote Run")) {
            return
        }

        val rawCmd = if (isDebug) {
            if (runConfig != null && runConfig.debugCommand.isNotBlank()) {
                runConfig.debugCommand.trim()
            } else {
                p.debugCommand.ifBlank { "./gradlew bootRun --debug-jvm" }
            }
        } else {
            if (runConfig != null && runConfig.runCommand.isNotBlank()) {
                runConfig.runCommand.trim()
            } else {
                p.runCommand.ifBlank { "./gradlew bootRun" }
            }
        }

        val workingDir = if (runConfig != null && runConfig.remoteWorkingDir.isNotBlank()) {
            runConfig.remoteWorkingDir.trim()
        } else {
            p.remoteProjectPath
        }
        val shouldSync = runConfig?.autoSync ?: true
        val shouldForward = runConfig?.forwardPorts ?: true

        ApplicationManager.getApplication().invokeLater {
            val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
            val processHandler = RemoteFlowProcessHandler(project, p, rawCmd)
            console.attachToProcess(processHandler)

            val executionResult = DefaultExecutionResult(console, processHandler)
            RunContentBuilder(executionResult, environment).showRunContent(environment.contentToReuse)
            processHandler.startNotify()

            connMgr.activeProcessHandler = processHandler

            // Launch remote execution
            val syncMgr = FastSyncManager(project)
            val logService = RemoteFlowLogService.getInstance(project)
            val cmd = uz.remote.flow.ssh.buildRemoteExecutionCommand(rawCmd, p.javaHome)

            val doRun = {
                if (isDebug) {
                    connMgr.startSingleForward(PortMapping(5005, 5005, "JVM Debug", direction = ForwardDirection.LOCAL_TO_REMOTE))
                }
                val detectedAppPort = java.util.concurrent.atomic.AtomicInteger(
                    uz.remote.flow.ssh.DynamicPortDetector.resolveInitialAppPort(project, rawCmd, 0)
                )

                if (shouldForward) {
                    connMgr.startPortForwarding(p)
                    val initialPort = detectedAppPort.get()
                    if (initialPort > 0) {
                        connMgr.forwardAppPort(initialPort)
                        val initMsg = "[PORT FORWARD] 🔀 Port forwarding initialized: localhost:$initialPort -> remote:$initialPort\n"
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
                    val debuggerAttached = AtomicBoolean(false)

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
                                val forwardMsg = "[PORT FORWARD] 🔀 Application port detected ($portInLog). Forwarding: localhost:$portInLog -> remote:$portInLog\n"
                                processHandler.printSystem(forwardMsg)
                                logService.log(forwardMsg, LogCategory.RUN, p.name)
                            }

                            if (isDebug && !debuggerAttached.get() && (line.contains("Listening for transport dt_socket at address:") || line.contains("dt_socket") || line.contains("5005"))) {
                                if (debuggerAttached.compareAndSet(false, true)) {
                                    uz.remote.flow.debug.RemoteDebugHelper.attachRemoteDebugger(project, "localhost", 5005, p.name)
                                }
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
                                    val postCmd = uz.remote.flow.ssh.buildRemoteExecutionCommand(p.postRunCommand, p.javaHome)
                                    connMgr.executeRemoteCommand(postCmd, workingDir, false, { processHandler.printOutput(it) }, {})
                                }
                            }
                        },
                        onComplete = { code ->
                            connMgr.stopAppPortForward()
                            val finishMsg = "[REMOTE FLOW FINISHED] Exit code: $code\n"
                            processHandler.printSystem(finishMsg)
                            logService.log(finishMsg, LogCategory.RUN, p.name)
                            connMgr.notifyUser("Remote Flow: Execution Finished", "Application completed on server ${p.name} (Exit code: $code)", NotificationType.INFORMATION)
                            processHandler.finishProcess(code)
                        }
                    )

                    if (isDebug) {
                        com.intellij.util.concurrency.AppExecutorUtil.getAppScheduledExecutorService().schedule({
                            if (connMgr.isProcessRunning && debuggerAttached.compareAndSet(false, true)) {
                                uz.remote.flow.debug.RemoteDebugHelper.attachRemoteDebugger(project, "localhost", 5005, p.name)
                            }
                        }, 3500, TimeUnit.MILLISECONDS)
                    }
                }

                val runWithPreHook = {
                    if (p.preRunCommand.isNotBlank()) {
                        processHandler.printSystem("[PRE-RUN HOOK] Executing pre-run hook: ${p.preRunCommand}\n")
                        val preCmd = uz.remote.flow.ssh.buildRemoteExecutionCommand(p.preRunCommand, p.javaHome)
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
                        doRun()
                    }
                } else {
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
    }
}
