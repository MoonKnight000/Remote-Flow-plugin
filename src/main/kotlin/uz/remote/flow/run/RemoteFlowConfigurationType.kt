package uz.remote.flow.run

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import uz.remote.flow.ui.RemoteFlowIcons
import javax.swing.Icon

class RemoteFlowConfigurationType : ConfigurationType {
    companion object {
        const val ID = "RemoteFlowRunConfiguration"

        fun getInstance(): RemoteFlowConfigurationType {
            return ConfigurationType.CONFIGURATION_TYPE_EP.extensionList
                .filterIsInstance<RemoteFlowConfigurationType>()
                .firstOrNull() ?: RemoteFlowConfigurationType()
        }
    }

    override fun getDisplayName(): String = "Remote Flow"

    override fun getConfigurationTypeDescription(): String =
        "Run and debug applications on remote servers via SSH"

    override fun getIcon(): Icon = RemoteFlowIcons.REMOTE_RUN

    override fun getId(): String = ID

    override fun getConfigurationFactories(): Array<ConfigurationFactory> {
        return arrayOf(RemoteFlowConfigurationFactory(this))
    }
}
