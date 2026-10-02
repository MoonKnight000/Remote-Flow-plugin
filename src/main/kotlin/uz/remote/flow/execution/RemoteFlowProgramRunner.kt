package uz.remote.flow.execution

import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.runners.RunContentBuilder
import com.intellij.openapi.application.ApplicationManager
import uz.remote.flow.run.RemoteFlowRunConfiguration
import uz.remote.flow.settings.RemoteFlowSettings

/**
 * Handles Remote Flow run configurations and intercepts standard IntelliJ Run requests (Shift + F10)
 * when routeStandardRunToRemote is enabled.
 * Integrates natively with IntelliJ's ProcessHandler, Run tool window, and Stop/Rerun toolbar controls.
 */
class RemoteFlowProgramRunner : ProgramRunner<RunnerSettings> {

    override fun getRunnerId(): String = "RemoteFlowProgramRunner"

    override fun canRun(executorId: String, profile: RunProfile): Boolean {
        if (executorId != DefaultRunExecutor.EXECUTOR_ID) {
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
        if (p.host.isBlank()) return false

        return isSupportedStandardApplication(profile)
    }

    companion object {
        /**
         * Checks whether the given configuration is a standard JVM/Spring Boot application run configuration.
         * Explicitly excludes HTTP client requests (.http / .rest), tests, database consoles, shell scripts, etc.
         */
        fun isSupportedStandardApplication(profile: RunProfile?): Boolean {
            if (profile == null) return false
            if (profile is RemoteFlowRunConfiguration) return true
            val runConfig = profile as? RunConfiguration ?: return false

            val typeId = runConfig.type.id
            val className = runConfig.javaClass.name
            val configName = runConfig.name

            // Exclude HTTP client requests (.http / .rest files)
            if (typeId.contains("HttpClient", ignoreCase = true) ||
                typeId.contains("HttpRequest", ignoreCase = true) ||
                typeId.contains("RestClient", ignoreCase = true) ||
                typeId.contains("Http", ignoreCase = true) ||
                className.contains("HttpClient", ignoreCase = true) ||
                className.contains("HttpRequest", ignoreCase = true) ||
                className.contains("RestClient", ignoreCase = true) ||
                className.startsWith("com.intellij.httpClient") ||
                className.startsWith("com.intellij.ws.rest") ||
                configName.endsWith(".http", ignoreCase = true) ||
                configName.endsWith(".rest", ignoreCase = true) ||
                configName.contains("#")
            ) {
                return false
            }

            // Exclude unit / integration tests
            if (typeId.contains("Test", ignoreCase = true) ||
                typeId.contains("JUnit", ignoreCase = true) ||
                className.contains("Test", ignoreCase = true) ||
                className.contains("JUnit", ignoreCase = true)
            ) {
                return false
            }

            // Exclude databases, docker, terminal, scripts, scratches, frontend
            if (typeId.contains("Database", ignoreCase = true) ||
                className.contains("Database", ignoreCase = true) ||
                className.startsWith("com.intellij.database") ||
                typeId.contains("Docker", ignoreCase = true) ||
                typeId.contains("Kubernetes", ignoreCase = true) ||
                typeId.contains("Shell", ignoreCase = true) ||
                typeId.contains("ShRunConfiguration", ignoreCase = true) ||
                typeId.contains("Bash", ignoreCase = true) ||
                typeId.contains("Batch", ignoreCase = true) ||
                typeId.contains("Terminal", ignoreCase = true) ||
                typeId.contains("Scratch", ignoreCase = true) ||
                typeId.contains("NodeJS", ignoreCase = true) ||
                typeId.contains("npm", ignoreCase = true) ||
                typeId.contains("Yarn", ignoreCase = true) ||
                typeId.contains("Vite", ignoreCase = true) ||
                typeId.contains("JavaScript", ignoreCase = true)
            ) {
                return false
            }

            // Whitelist standard application run configurations
            val isAppType = typeId == "Application" ||
                    typeId.contains("SpringBoot", ignoreCase = true) ||
                    typeId.contains("Kotlin", ignoreCase = true) ||
                    className.contains("ApplicationConfiguration", ignoreCase = true) ||
                    className.contains("SpringBoot", ignoreCase = true) ||
                    configName.endsWith("Application", ignoreCase = true) ||
                    (typeId.contains("Gradle", ignoreCase = true) &&
                            (configName.contains("bootRun", ignoreCase = true) || configName.contains("run", ignoreCase = true)))

            return isAppType
        }
    }

    override fun execute(environment: ExecutionEnvironment) {
        if (environment.executor.id == DefaultDebugExecutor.EXECUTOR_ID) {
            RemoteFlowDebuggerRunner().execute(environment)
            return
        }

        val state = if (environment.runProfile is RemoteFlowRunConfiguration) {
            environment.state ?: RemoteFlowRunProfileState(environment, isDebug = false)
        } else {
            RemoteFlowRunProfileState(environment, isDebug = false)
        }

        ApplicationManager.getApplication().invokeLater {
            if (environment.project.isDisposed) return@invokeLater
            val executionResult = state.execute(environment.executor, this) ?: return@invokeLater
            val descriptor = RunContentBuilder(executionResult, environment).showRunContent(environment.contentToReuse)
            if (descriptor != null) {
                com.intellij.execution.ui.RunContentManager.getInstance(environment.project)
                    .toFrontRunContent(environment.executor, descriptor)
            }
            com.intellij.openapi.wm.ToolWindowManager.getInstance(environment.project)
                .getToolWindow(com.intellij.openapi.wm.ToolWindowId.RUN)?.apply {
                    show(null)
                    activate(null)
                }
        }
    }
}
