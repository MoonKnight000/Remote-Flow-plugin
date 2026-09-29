package uz.remote.flow.execution

import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.application.ApplicationManager
import uz.remote.flow.actions.RemoteFlowDebugAction
import uz.remote.flow.actions.RemoteFlowRunAction
import uz.remote.flow.settings.RemoteFlowSettings

/**
 * Intercepts standard IntelliJ Run and Debug requests (e.g. clicking the Run/Debug button
 * next to HomeSaleV2Application or pressing Shift+F10 / Shift+F9) when routeStandardRunToRemote is enabled.
 * Executes the build and run on the remote server with automatic port forwarding.
 */
class RemoteFlowProgramRunner : ProgramRunner<RunnerSettings> {

    override fun getRunnerId(): String = "RemoteFlowProgramRunner"

    override fun canRun(executorId: String, profile: RunProfile): Boolean {
        if (executorId != DefaultRunExecutor.EXECUTOR_ID && executorId != DefaultDebugExecutor.EXECUTOR_ID) {
            return false
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

        ApplicationManager.getApplication().invokeLater {
            if (isDebug) {
                RemoteFlowDebugAction.runDebugDirectly(project)
            } else {
                RemoteFlowRunAction.runDirectly(project)
            }
        }
    }
}
