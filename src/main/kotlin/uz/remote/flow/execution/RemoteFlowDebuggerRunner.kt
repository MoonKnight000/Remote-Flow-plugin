package uz.remote.flow.execution

import com.intellij.debugger.impl.GenericDebuggerRunner
import com.intellij.execution.ExecutionManager
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.fileEditor.FileDocumentManager
import uz.remote.flow.run.RemoteFlowRunConfiguration
import uz.remote.flow.settings.RemoteFlowSettings

/**
 * Native IntelliJ Debugger Runner for Remote Flow.
 * Integrates directly with GenericDebuggerRunner to connect IntelliJ's full JDWP debugging engine
 * (Breakpoints, Frames, Variables, Watches, Evaluate Expression Alt+F8, Stepping F7/F8/F9, HotSwap)
 * to the remote process via SSH port forwarding, while displaying live console output in the same unified Debug window.
 */
class RemoteFlowDebuggerRunner : GenericDebuggerRunner() {

    override fun getRunnerId(): String = "RemoteFlowDebuggerRunner"

    override fun canRun(executorId: String, profile: RunProfile): Boolean {
        if (executorId != DefaultDebugExecutor.EXECUTOR_ID) return false
        if (profile is RemoteFlowRunConfiguration) return true

        val project = (profile as? com.intellij.execution.configurations.RunConfiguration)?.project ?: return false
        val settings = RemoteFlowSettings.getInstance(project)
        if (!settings.routeStandardRunToRemote) return false
        val p = settings.activeProfileOrNull ?: return false
        if (p.host.isBlank()) return false

        return RemoteFlowProgramRunner.isSupportedStandardApplication(profile)
    }

    override fun execute(environment: ExecutionEnvironment) {
        val project = environment.project
        val state = if (environment.runProfile is RemoteFlowRunConfiguration) {
            environment.state ?: RemoteFlowRunProfileState(environment, isDebug = true)
        } else {
            RemoteFlowRunProfileState(environment, isDebug = true)
        }

        FileDocumentManager.getInstance().saveAllDocuments()

        ExecutionManager.getInstance(project).startRunProfile(environment) {
            val descriptor = createContentDescriptor(state, environment)
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) {
                    com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
                        .getToolWindow(com.intellij.openapi.wm.ToolWindowId.DEBUG)?.apply {
                            show(null)
                            activate(null)
                        }
                }
            }
            org.jetbrains.concurrency.resolvedPromise(descriptor)
        }
    }

    override fun createContentDescriptor(
        state: RunProfileState,
        environment: ExecutionEnvironment
    ): RunContentDescriptor? {
        val targetState = if (state is RemoteFlowRunProfileState) {
            state
        } else {
            RemoteFlowRunProfileState(environment, isDebug = true)
        }

        val connection = targetState.createRemoteConnection(environment)
        // 300_000L = 5 minutes timeout to poll 127.0.0.1:debugPort while remote Gradle / Maven builds and boots JVM
        return attachVirtualMachine(targetState, environment, connection, 300000L)
    }
}
