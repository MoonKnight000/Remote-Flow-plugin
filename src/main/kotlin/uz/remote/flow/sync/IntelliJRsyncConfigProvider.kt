package uz.remote.flow.sync

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import java.io.File

data class IntelliJRsyncConfig(
    val rsyncPath: String = "",
    val shellPath: String = "",
    val options: String = ""
)

object IntelliJRsyncConfigProvider {

    @Volatile
    private var cachedConfig: IntelliJRsyncConfig? = null

    fun getRsyncConfig(forceRefresh: Boolean = false): IntelliJRsyncConfig {
        if (!forceRefresh) {
            cachedConfig?.let { return it }
        }

        // 1. Try reading directly from IntelliJ's native RsyncService via reflection
        try {
            val clazz = Class.forName("com.intellij.ssh.rsync.RsyncService")
            val service = ApplicationManager.getApplication()?.getService(clazz)
            if (service != null) {
                val rsyncPath = (clazz.getMethod("getRsyncPath").invoke(service) as? String)?.trim().orEmpty()
                val shellPath = (clazz.getMethod("getShellPath").invoke(service) as? String)?.trim().orEmpty()
                val options = (clazz.getMethod("getOptions").invoke(service) as? String)?.trim().orEmpty()

                if (rsyncPath.isNotBlank() || shellPath.isNotBlank() || options.isNotBlank()) {
                    val cfg = IntelliJRsyncConfig(rsyncPath, shellPath, options)
                    cachedConfig = cfg
                    return cfg
                }
            }
        } catch (_: Throwable) {
            // Service not yet initialized or classloader difference, fallback to XML
        }

        // 2. Read from other.xml directly as fallback
        val cfg = readFromOtherXml()
        cachedConfig = cfg
        return cfg
    }

    private fun readFromOtherXml(): IntelliJRsyncConfig {
        return try {
            val optionsFile = try {
                PathManager.getOptionsFile("other")
            } catch (_: Throwable) {
                null
            }
            val file = if (optionsFile != null && optionsFile.exists()) {
                optionsFile
            } else {
                val configPath = try { PathManager.getConfigPath() } catch (_: Throwable) { "" }
                if (configPath.isNotBlank()) File(configPath, "options/other.xml") else null
            }

            if (file == null || !file.exists()) return IntelliJRsyncConfig()

            val text = file.readText()
            val compRegex = Regex("""<component\s+name=["']RsyncSettings["']>([\s\S]*?)</component>""")
            val compMatch = compRegex.find(text) ?: return IntelliJRsyncConfig()
            val compContent = compMatch.groupValues[1]

            fun extractOption(name: String): String {
                val optRegex = Regex("""<option\s+name=["']$name["']\s+value=["'](.*?)["']\s*/>""")
                return optRegex.find(compContent)?.groupValues?.get(1)?.trim().orEmpty()
            }

            IntelliJRsyncConfig(
                rsyncPath = extractOption("rsyncPath"),
                shellPath = extractOption("shellPath"),
                options = extractOption("options")
            )
        } catch (_: Throwable) {
            IntelliJRsyncConfig()
        }
    }
}
