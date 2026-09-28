package uz.remote.flow.logging

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
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
            val text = entry.message.lowercase(Locale.US)
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
        val line = "[$timeStr] $categoryStr$serverStr${entry.message}"

        val contentType = when {
            entry.isError -> ConsoleViewContentType.ERROR_OUTPUT
            entry.message.contains("[ERROR]") || entry.message.contains("FAILED") -> ConsoleViewContentType.ERROR_OUTPUT
            entry.message.contains("[SUCCESS]") || entry.message.contains("OK") -> ConsoleViewContentType.USER_INPUT
            else -> ConsoleViewContentType.NORMAL_OUTPUT
        }

        consoleView.print(line, contentType)
    }

    companion object {
        fun getInstance(project: Project): RemoteFlowLogService =
            project.getService(RemoteFlowLogService::class.java)
    }
}
