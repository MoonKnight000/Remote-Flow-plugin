package uz.remote.flow.ssh

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

object RemoteSafetyHelper {

    /**
     * If the profile is marked as PRODUCTION and confirmOnProduction is true,
     * prompts the user with an explicit warning dialog. Returns true if user confirms or profile is not PRODUCTION.
     */
    fun checkProductionSafe(project: Project, profile: ServerProfile, actionDescription: String): Boolean {
        if (profile.environment != ServerEnvironment.PRODUCTION || !profile.confirmOnProduction) {
            return true
        }

        val result = Messages.showYesNoDialog(
            project,
            "⚠️ PRODUCTION SERVER SAFETY WARNING!\n\n" +
                "Action: $actionDescription\n" +
                "Target Server: ${profile.name} (${profile.host}:${profile.port})\n" +
                "Environment: PRODUCTION\n\n" +
                "Are you sure you want to proceed with this operation on the live PRODUCTION server?",
            "Production Protection: ${profile.name}",
            "Proceed on Production",
            "Cancel",
            Messages.getWarningIcon()
        )
        return result == Messages.YES
    }
}
