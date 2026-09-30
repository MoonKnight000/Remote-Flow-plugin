package uz.remote.flow.run

import com.intellij.execution.RunManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class RemoteFlowStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        if (project.isDisposed) return
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                ensureDefaultRunConfiguration(project)
            }
        }
    }

    companion object {
        fun ensureDefaultRunConfiguration(project: Project) {
            val runManager = RunManager.getInstance(project)
            val type = RemoteFlowConfigurationType.getInstance()
            val existing = runManager.getConfigurationSettingsList(type)
            if (existing.isEmpty()) {
                val factory = type.configurationFactories.firstOrNull() ?: return
                val runnerAndConfigurationSettings = runManager.createConfiguration("Remote Flow", factory)
                val config = runnerAndConfigurationSettings.configuration as? RemoteFlowRunConfiguration
                if (config != null) {
                    config.serverProfileName = ""
                    config.autoSync = true
                    config.forwardPorts = true
                }
                runManager.addConfiguration(runnerAndConfigurationSettings)
            }
        }
    }
}
