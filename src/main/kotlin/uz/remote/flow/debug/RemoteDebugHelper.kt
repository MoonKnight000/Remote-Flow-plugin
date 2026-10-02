package uz.remote.flow.debug

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import uz.remote.flow.logging.LogCategory
import uz.remote.flow.logging.RemoteFlowLogService

object RemoteDebugHelper {

    fun attachRemoteDebugger(
        project: Project,
        host: String = "localhost",
        port: Int = 5005,
        serverName: String = "Remote Server"
    ) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater

            val logService = RemoteFlowLogService.getInstance(project)

            try {
                // Find "Remote" configuration type (IntelliJ's built-in Remote JVM Debug)
                val remoteType = ConfigurationType.CONFIGURATION_TYPE_EP.extensionList.find { it.id == "Remote" }
                if (remoteType == null) {
                    logService.log(
                        "[REMOTE DEBUG] Debug port $port is forwarded to $host:$port. Attach your debugger manually if 'Remote' configuration type is unavailable.\n",
                        LogCategory.RUN,
                        serverName
                    )
                    return@invokeLater
                }

                val factory = remoteType.configurationFactories.firstOrNull() ?: return@invokeLater
                val runManager = RunManager.getInstance(project)
                val configName = "Remote JVM ($serverName:$port)"

                var settings = runManager.findConfigurationByName(configName)
                if (settings == null) {
                    settings = runManager.createConfiguration(configName, factory)
                    runManager.addConfiguration(settings)
                }

                val runConfig = settings.configuration

                // RemoteConfiguration has public fields: HOST, PORT, USE_SOCKET_TRANSPORT, SERVER_MODE, AUTO_RESTART
                try {
                    val configClass = runConfig.javaClass
                    configClass.getField("HOST").set(runConfig, host)
                    configClass.getField("PORT").set(runConfig, port.toString())
                    configClass.getField("USE_SOCKET_TRANSPORT").set(runConfig, true)
                    configClass.getField("SERVER_MODE").set(runConfig, false) // Attach mode
                    try { configClass.getField("AUTO_RESTART").set(runConfig, true) } catch (_: Exception) {}
                } catch (e: Exception) {
                    try {
                        val hostField = runConfig.javaClass.getDeclaredField("HOST").apply { isAccessible = true }
                        hostField.set(runConfig, host)
                        val portField = runConfig.javaClass.getDeclaredField("PORT").apply { isAccessible = true }
                        portField.set(runConfig, port.toString())
                        try {
                            val autoRestartField = runConfig.javaClass.getDeclaredField("AUTO_RESTART").apply { isAccessible = true }
                            autoRestartField.set(runConfig, true)
                        } catch (_: Exception) {}
                    } catch (_: Exception) {}
                }

                runManager.selectedConfiguration = settings
                val debugExecutor = DefaultDebugExecutor.getDebugExecutorInstance()

                logService.log(
                    "[REMOTE DEBUG ATTACH] 🚀 Attaching IntelliJ Remote Debugger to $host:$port...\n",
                    LogCategory.RUN,
                    serverName
                )

                ProgramRunnerUtil.executeConfiguration(settings, debugExecutor)

                logService.log(
                    "[REMOTE DEBUG READY] ✅ Debugger attached successfully! Breakpoints in your editor are now active.\n",
                    LogCategory.RUN,
                    serverName
                )
            } catch (e: Exception) {
                logService.log(
                    "[REMOTE DEBUG NOTICE] Could not auto-attach debugger: ${e.message}. You can connect manually to $host:$port via Run -> Edit Configurations -> Remote JVM Debug.\n",
                    LogCategory.RUN,
                    serverName,
                    isError = false
                )
            }
        }
    }
}
