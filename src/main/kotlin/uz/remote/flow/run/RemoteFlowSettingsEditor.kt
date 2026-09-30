package uz.remote.flow.run

import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import uz.remote.flow.settings.RemoteFlowSettings
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

class RemoteFlowSettingsEditor(private val project: Project) : SettingsEditor<RemoteFlowRunConfiguration>() {

    private val profileCombo = ComboBox<String>()
    private val runCommandField = JBTextField()
    private val debugCommandField = JBTextField()
    private val remoteWorkingDirField = JBTextField()
    private val autoSyncCheckBox = JBCheckBox("Auto-sync project files to remote server before execution", true)
    private val forwardPortsCheckBox = JBCheckBox("Automatically forward configured SSH ports (HTTP, DB, Redis, etc.)", true)

    private fun reloadProfiles() {
        val settings = RemoteFlowSettings.getInstance(project)
        val selected = profileCombo.selectedItem as? String
        profileCombo.removeAllItems()
        profileCombo.addItem("[Active Server Profile]")
        settings.profiles.forEach { profileCombo.addItem(it.name) }
        if (selected != null && settings.profiles.any { it.name == selected }) {
            profileCombo.selectedItem = selected
        } else {
            profileCombo.selectedIndex = 0
        }
    }

    override fun createEditor(): JComponent {
        reloadProfiles()

        runCommandField.emptyText.text = "e.g. ./gradlew bootRun (Leave blank to use profile default)"
        debugCommandField.emptyText.text = "e.g. ./gradlew bootRun --debug-jvm (Leave blank to use profile default)"
        remoteWorkingDirField.emptyText.text = "e.g. /home/ubuntu/app (Leave blank to use profile default)"

        val hintLabel = JBLabel("Runs or debugs your application directly on the remote Linux server via SSH.")
        hintLabel.font = UIUtil.getLabelFont(UIUtil.FontSize.SMALL)
        hintLabel.foreground = UIUtil.getContextHelpForeground()

        val formPanel = FormBuilder.createFormBuilder()
            .addComponent(hintLabel)
            .addVerticalGap(8)
            .addLabeledComponent("Server Profile:", profileCombo)
            .addLabeledComponent("Run Command:", runCommandField)
            .addLabeledComponent("Debug Command:", debugCommandField)
            .addLabeledComponent("Remote Directory:", remoteWorkingDirField)
            .addVerticalGap(6)
            .addComponent(autoSyncCheckBox)
            .addComponent(forwardPortsCheckBox)
            .panel

        val wrapper = JPanel(BorderLayout())
        wrapper.border = JBUI.Borders.empty(8)
        wrapper.add(formPanel, BorderLayout.NORTH)
        return wrapper
    }

    override fun resetEditorFrom(config: RemoteFlowRunConfiguration) {
        reloadProfiles()
        val targetProfile = config.serverProfileName
        if (targetProfile.isNotBlank()) {
            profileCombo.selectedItem = targetProfile
        } else {
            profileCombo.selectedIndex = 0
        }
        runCommandField.text = config.runCommand
        debugCommandField.text = config.debugCommand
        remoteWorkingDirField.text = config.remoteWorkingDir
        autoSyncCheckBox.isSelected = config.autoSync
        forwardPortsCheckBox.isSelected = config.forwardPorts
    }

    override fun applyEditorTo(config: RemoteFlowRunConfiguration) {
        val selected = profileCombo.selectedItem as? String
        config.serverProfileName = if (selected == null || selected == "[Active Server Profile]") "" else selected
        config.runCommand = runCommandField.text.trim()
        config.debugCommand = debugCommandField.text.trim()
        config.remoteWorkingDir = remoteWorkingDirField.text.trim()
        config.autoSync = autoSyncCheckBox.isSelected
        config.forwardPorts = forwardPortsCheckBox.isSelected
    }
}
