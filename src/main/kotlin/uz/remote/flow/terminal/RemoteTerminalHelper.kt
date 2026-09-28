package uz.remote.flow.terminal

import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.concurrency.AppExecutorUtil
import uz.remote.flow.ssh.AuthType
import uz.remote.flow.ssh.RemoteConnectionManager
import uz.remote.flow.ssh.ServerProfile
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.util.concurrent.TimeUnit

object RemoteTerminalHelper {

    fun openTerminal(project: Project, profile: ServerProfile) {
        if (profile.host.isBlank()) {
            RemoteConnectionManager.getInstance(project).notifyUser(
                "Remote Flow",
                "Server profili to'liq sozlanmagan! Avval host va username kiriting.",
                NotificationType.WARNING
            )
            return
        }

        val sshCmd = buildSshCommand(profile)

        // Agar parol bilan autentifikatsiya bo'lsa, parolni buferga nusxalaymiz
        if (profile.authType == AuthType.PASSWORD && profile.password.isNotBlank()) {
            try {
                val sel = StringSelection(profile.password)
                Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
            } catch (_: Exception) {}
        }

        // IntelliJ Terminal Tool Window ni faollashtirish
        val tw = ToolWindowManager.getInstance(project).getToolWindow("Terminal")
        tw?.activate(null)

        ApplicationManager.getApplication().invokeLater {
            val openedInIde = tryOpenInIdeTerminal(project, profile, sshCmd)
            if (openedInIde) {
                val pwdNote = if (profile.authType == AuthType.PASSWORD && profile.password.isNotBlank()) {
                    "\nParol buferga nusxalandi (Ctrl+V bilan kiritishingiz mumkin)."
                } else ""
                RemoteConnectionManager.getInstance(project).notifyUser(
                    "Remote Flow: SSH Terminal",
                    "IntelliJ Terminal ochildi va ${profile.name} serveriga ulanmoqda...$pwdNote",
                    NotificationType.INFORMATION
                )
            } else {
                // Agar IntelliJ ichki terminali ochilmasa, tashqi PowerShell ochiladi
                openExternalTerminal(project, profile, sshCmd)
            }
        }
    }

    fun buildSshCommand(profile: ServerProfile): String {
        val sb = StringBuilder("ssh")
        if (profile.port != 22) {
            sb.append(" -p ").append(profile.port)
        }
        if (profile.authType == AuthType.PRIVATE_KEY && profile.privateKeyPath.isNotBlank()) {
            val keyPath = profile.privateKeyPath.replace('\\', '/')
            sb.append(" -i \"").append(keyPath).append("\"")
        }
        val user = profile.user.ifBlank { "root" }
        sb.append(" ").append(user).append("@").append(profile.host)
        return sb.toString()
    }

    private fun tryOpenInIdeTerminal(project: Project, profile: ServerProfile, sshCmd: String): Boolean {
        val tabName = "SSH: ${profile.name}"
        val workingDir = project.basePath

        // 1. TerminalToolWindowManager orqali (IntelliJ 2023.2+)
        try {
            val ttwmClass = Class.forName("org.jetbrains.plugins.terminal.TerminalToolWindowManager")
            val getInstance = ttwmClass.getMethod("getInstance", Project::class.java)
            val mgr = getInstance.invoke(null, project)

            val widget = try {
                val m = ttwmClass.getMethod(
                    "createLocalShellWidget",
                    String::class.java,
                    String::class.java,
                    Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType
                )
                m.invoke(mgr, workingDir, tabName, true, true)
            } catch (_: NoSuchMethodException) {
                try {
                    val m = ttwmClass.getMethod(
                        "createLocalShellWidget",
                        String::class.java,
                        String::class.java,
                        Boolean::class.javaPrimitiveType
                    )
                    m.invoke(mgr, workingDir, tabName, true)
                } catch (_: NoSuchMethodException) {
                    val m = ttwmClass.getMethod(
                        "createLocalShellWidget",
                        String::class.java,
                        String::class.java
                    )
                    m.invoke(mgr, workingDir, tabName)
                }
            }

            if (widget != null) {
                executeCommandOnWidget(widget, sshCmd)
                return true
            }
        } catch (_: Throwable) {}

        // 2. TerminalView orqali (IntelliJ TerminalView)
        try {
            val tvClass = Class.forName("org.jetbrains.plugins.terminal.TerminalView")
            val getInstance = tvClass.getMethod("getInstance", Project::class.java)
            val tv = getInstance.invoke(null, project)

            val widget = try {
                val m = tvClass.getMethod(
                    "createLocalShellWidget",
                    String::class.java,
                    String::class.java,
                    Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType
                )
                m.invoke(tv, workingDir, tabName, true, true)
            } catch (_: NoSuchMethodException) {
                try {
                    val m = tvClass.getMethod(
                        "createLocalShellWidget",
                        String::class.java,
                        String::class.java
                    )
                    m.invoke(tv, workingDir, tabName)
                } catch (_: NoSuchMethodException) {
                    null
                }
            }

            if (widget != null) {
                executeCommandOnWidget(widget, sshCmd)
                return true
            }
        } catch (_: Throwable) {}

        return false
    }

    private fun executeCommandOnWidget(widget: Any, command: String) {
        fun tryExec(): Boolean {
            val methods = widget.javaClass.methods
            val execM = methods.firstOrNull {
                it.name == "executeCommand" && it.parameterCount == 1 && it.parameterTypes[0] == String::class.java
            }
            if (execM != null) {
                try {
                    execM.invoke(widget, command)
                    return true
                } catch (_: Throwable) {}
            }

            val sendM = methods.firstOrNull {
                it.name == "sendCommandToExecute" && it.parameterCount == 1 && it.parameterTypes[0] == String::class.java
            }
            if (sendM != null) {
                try {
                    sendM.invoke(widget, command)
                    return true
                } catch (_: Throwable) {}
            }
            return false
        }

        if (!tryExec()) {
            AppExecutorUtil.getAppScheduledExecutorService().schedule({
                ApplicationManager.getApplication().invokeLater {
                    tryExec()
                }
            }, 250, TimeUnit.MILLISECONDS)
        }
    }

    fun openExternalTerminal(project: Project, profile: ServerProfile, sshCmd: String = buildSshCommand(profile)) {
        val user = profile.user.ifBlank { "root" }
        val cmd = "start powershell -NoExit -Command \"Write-Host 'Connecting to ${profile.name} ($user@${profile.host})...' -ForegroundColor Cyan; $sshCmd\""
        try {
            Runtime.getRuntime().exec(arrayOf("cmd.exe", "/c", cmd))
            RemoteConnectionManager.getInstance(project).notifyUser(
                "Remote Flow: Tashqi Terminal",
                "${profile.name} uchun tashqi terminal oynasi ochildi.",
                NotificationType.INFORMATION
            )
        } catch (e: Exception) {
            RemoteConnectionManager.getInstance(project).notifyUser(
                "Remote Flow",
                "Terminalni ochishda xatolik: ${e.message}",
                NotificationType.ERROR
            )
        }
    }
}
