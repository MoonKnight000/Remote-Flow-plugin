package uz.remote.flow.logging

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.AnsiEscapeDecoder
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import uz.remote.flow.ssh.cleanAnsiText
import java.text.SimpleDateFormat
import java.util.*

enum class LogCategory(val displayName: String, val badge: String) {
    ALL("All Categories", "[ALL]"),
    SYNC("Sync", "[SYNC]"),
    RUN("Run & Debug", "[RUN]"),
    PORT("Port Forwarding", "[PORT]"),
    SSH("SSH & System", "[SSH]"),
    FILES("Remote Files", "[FILES]");

    override fun toString(): String = displayName
}

data class LogEntry(
    val timestamp: Long,
    val category: LogCategory,
    val serverName: String,
    val message: String,
    val isError: Boolean = false
)

@Service(Service.Level.PROJECT)
class RemoteFlowLogService(private val project: Project) {

    val consoleView: ConsoleView = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val entries = mutableListOf<LogEntry>()
    private val ansiDecoder = AnsiEscapeDecoder()

    var activeCategoryFilter: LogCategory = LogCategory.ALL
    var activeServerFilter: String = "All Servers"
    var activeTextFilter: String = ""

    fun log(
        message: String,
        category: LogCategory = LogCategory.ALL,
        serverName: String = "",
        isError: Boolean = false
    ) {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            category = category,
            serverName = serverName,
            message = message,
            isError = isError
        )

        synchronized(entries) {
            entries.add(entry)
            if (entries.size > 10000) {
                entries.removeAt(0)
            }
        }

        ApplicationManager.getApplication().invokeLater {
            if (matchesFilter(entry)) {
                printEntry(entry)
            }
        }
    }

    fun clearLogs() {
        synchronized(entries) {
            entries.clear()
        }
        ApplicationManager.getApplication().invokeLater {
            consoleView.clear()
        }
    }

    fun applyFilters(category: LogCategory, server: String, textFilter: String) {
        activeCategoryFilter = category
        activeServerFilter = server
        activeTextFilter = textFilter.trim().lowercase(Locale.US)

        ApplicationManager.getApplication().invokeLater {
            consoleView.clear()
            val toPrint = synchronized(entries) {
                entries.filter { matchesFilter(it) }
            }
            for (entry in toPrint) {
                printEntry(entry)
            }
        }
    }

    private fun matchesFilter(entry: LogEntry): Boolean {
        if (activeCategoryFilter != LogCategory.ALL && entry.category != activeCategoryFilter) {
            return false
        }
        if (activeServerFilter != "All Servers" && entry.serverName.isNotBlank() && entry.serverName != activeServerFilter) {
            return false
        }
        if (activeTextFilter.isNotEmpty()) {
            val text = cleanAnsiText(entry.message).lowercase(Locale.US)
            val server = entry.serverName.lowercase(Locale.US)
            val cat = entry.category.displayName.lowercase(Locale.US)
            if (!text.contains(activeTextFilter) && !server.contains(activeTextFilter) && !cat.contains(activeTextFilter)) {
                return false
            }
        }
        return true
    }

    private fun printEntry(entry: LogEntry) {
        val timeStr = timeFormat.format(Date(entry.timestamp))
        val serverStr = if (entry.serverName.isNotBlank()) "[${entry.serverName}] " else ""
        val categoryStr = if (entry.category != LogCategory.ALL) "${entry.category.badge} " else ""

        // Print timestamp and badges with distinct subtle colors
        consoleView.print("[$timeStr] ", ConsoleViewContentType.SYSTEM_OUTPUT)
        if (categoryStr.isNotBlank()) {
            val catContentType = when (entry.category) {
                LogCategory.RUN -> ConsoleViewContentType.USER_INPUT
                LogCategory.SYNC -> ConsoleViewContentType.LOG_INFO_OUTPUT
                LogCategory.PORT -> ConsoleViewContentType.LOG_INFO_OUTPUT
                LogCategory.SSH -> ConsoleViewContentType.SYSTEM_OUTPUT
                LogCategory.FILES -> ConsoleViewContentType.LOG_INFO_OUTPUT
                else -> ConsoleViewContentType.NORMAL_OUTPUT
            }
            consoleView.print(categoryStr, catContentType)
        }
        if (serverStr.isNotBlank()) {
            consoleView.print(serverStr, ConsoleViewContentType.LOG_INFO_OUTPUT)
        }

        val rawMsg = entry.message
        if (rawMsg.contains("\u001B")) {
            // ANSI escape sequences present - decode colors (Spring Boot banner, rich logs, etc.)
            ansiDecoder.escapeText(rawMsg, ProcessOutputTypes.STDOUT) { chunk, outputType ->
                val type = ConsoleViewContentType.getConsoleViewType(outputType)
                consoleView.print(chunk, type)
            }
        } else {
            // Plain text - perform smart level-based color highlighting
            val contentType = when {
                entry.isError -> ConsoleViewContentType.ERROR_OUTPUT
                rawMsg.contains("[ERROR]") || rawMsg.contains(" ERROR ") || rawMsg.contains("FATAL") || rawMsg.contains("Exception") || rawMsg.contains("FAILED") -> ConsoleViewContentType.ERROR_OUTPUT
                rawMsg.contains("[WARNING]") || rawMsg.contains("[WARN]") || rawMsg.contains(" WARN ") -> ConsoleViewContentType.LOG_WARNING_OUTPUT
                rawMsg.contains("[SUCCESS]") || rawMsg.contains("[SYNC SUCCESS]") || rawMsg.contains("BUILD SUCCESSFUL") || rawMsg.contains("Started ") -> ConsoleViewContentType.USER_INPUT
                rawMsg.contains("[REMOTE RUN]") || rawMsg.contains("[REMOTE DEBUG]") || rawMsg.contains("[PORT") || rawMsg.contains("[GIT") -> ConsoleViewContentType.LOG_INFO_OUTPUT
                else -> ConsoleViewContentType.NORMAL_OUTPUT
            }
            consoleView.print(rawMsg, contentType)
        }

        if (!rawMsg.endsWith("\n")) {
            consoleView.print("\n", ConsoleViewContentType.NORMAL_OUTPUT)
        }
    }

    companion object {
        fun getInstance(project: Project): RemoteFlowLogService =
            project.getService(RemoteFlowLogService::class.java)
    }
}
