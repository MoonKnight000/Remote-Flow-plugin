package uz.remote.flow.run

import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.LocatableConfigurationBase
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import uz.remote.flow.ui.RemoteFlowIcons
import javax.swing.Icon

class RemoteFlowRunConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String?
) : LocatableConfigurationBase<RemoteFlowRunConfigurationOptions>(project, factory, name) {

    override fun getOptions(): RemoteFlowRunConfigurationOptions {
        return super.getOptions() as RemoteFlowRunConfigurationOptions
    }

    var serverProfileName: String
        get() = options.serverProfileName
        set(value) { options.serverProfileName = value }

    var runCommand: String
        get() = options.runCommand
        set(value) { options.runCommand = value }

    var debugCommand: String
        get() = options.debugCommand
        set(value) { options.debugCommand = value }

    var remoteWorkingDir: String
        get() = options.remoteWorkingDir
        set(value) { options.remoteWorkingDir = value }

    var autoSync: Boolean
        get() = options.autoSync
        set(value) { options.autoSync = value }

    var forwardPorts: Boolean
        get() = options.forwardPorts
        set(value) { options.forwardPorts = value }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> {
        return RemoteFlowSettingsEditor(project)
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
        val isDebug = executor.id == com.intellij.execution.executors.DefaultDebugExecutor.EXECUTOR_ID
        return uz.remote.flow.execution.RemoteFlowRunProfileState(environment, isDebug)
    }

    override fun getIcon(): Icon = RemoteFlowIcons.REMOTE_RUN
}
