package uz.remote.flow.run

import com.intellij.execution.RunManagerEx
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.openapi.project.Project

class RemoteFlowConfigurationFactory(type: ConfigurationType) : ConfigurationFactory(type) {

    override fun getId(): String = "RemoteFlow"

    override fun getName(): String = "Remote Flow"

    override fun createTemplateConfiguration(project: Project): RunConfiguration {
        return RemoteFlowRunConfiguration(project, this, "Remote Flow")
    }

    override fun getOptionsClass(): Class<out RunConfigurationOptions> {
        return RemoteFlowRunConfigurationOptions::class.java
    }

    override fun configureDefaultSettings(settings: RunnerAndConfigurationSettings) {
        super.configureDefaultSettings(settings)
        try {
            RunManagerEx.getInstanceEx(settings.configuration.project).setBeforeRunTasks(settings.configuration, emptyList())
        } catch (_: Throwable) {}
    }
}
