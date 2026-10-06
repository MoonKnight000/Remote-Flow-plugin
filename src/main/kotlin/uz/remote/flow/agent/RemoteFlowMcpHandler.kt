package uz.remote.flow.agent

import com.intellij.openapi.project.Project
import uz.remote.flow.ssh.RemoteConnectionManager
import uz.remote.flow.sync.FastSyncManager
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class DiagnosticItem(
    val file: String,
    val line: Int,
    val column: Int,
    val message: String,
    val severity: String = "ERROR"
)

object DiagnosticsExtractor {

    private val javaErrorRegex = Regex("""(?:\.?/)?([a-zA-Z0-9_/\-\\]+\.(?:java|kt|groovy)):\[?(\d+)[,:](\d+)?\]?:\s*(?:error:\s*)?(.*)""", RegexOption.IGNORE_CASE)
    private val tsErrorRegex = Regex("""(?:\.?/)?([a-zA-Z0-9_/\-\\]+\.(?:ts|tsx|js|jsx)):(\d+):(\d+)\s*-\s*error\s*(?:[A-Z0-9]+)?:\s*(.*)""", RegexOption.IGNORE_CASE)
    private val pythonErrorRegex = Regex("""File\s*"([^"]+)",\s*line\s*(\d+)(?:,\s*in\s*.*)?\s*\n\s*(.*)""")
    private val goErrorRegex = Regex("""(?:\.?/)?([a-zA-Z0-9_/\-\\]+\.go):(\d+):(\d+)?:\s*(.*)""")
    private val rustErrorRegex = Regex("""-->\s*([a-zA-Z0-9_/\-\\]+\.rs):(\d+):(\d+)""")

    fun extract(output: String): List<DiagnosticItem> {
        val list = mutableListOf<DiagnosticItem>()
        val lines = output.lines()

        for (i in lines.indices) {
            val line = lines[i].trim()

            // 1. Java / Kotlin
            val jm = javaErrorRegex.find(line)
            if (jm != null) {
                val file = jm.groupValues[1]
                val lNum = jm.groupValues[2].toIntOrNull() ?: 1
                val col = jm.groupValues[3].toIntOrNull() ?: 1
                val msg = jm.groupValues[4].ifBlank { line }
                list.add(DiagnosticItem(file, lNum, col, msg))
                continue
            }

            // 2. TypeScript / JS
            val tm = tsErrorRegex.find(line)
            if (tm != null) {
                val file = tm.groupValues[1]
                val lNum = tm.groupValues[2].toIntOrNull() ?: 1
                val col = tm.groupValues[3].toIntOrNull() ?: 1
                val msg = tm.groupValues[4]
                list.add(DiagnosticItem(file, lNum, col, msg))
                continue
            }

            // 3. Go
            val gm = goErrorRegex.find(line)
            if (gm != null) {
                val file = gm.groupValues[1]
                val lNum = gm.groupValues[2].toIntOrNull() ?: 1
                val col = gm.groupValues[3].toIntOrNull() ?: 1
                val msg = gm.groupValues[4]
                list.add(DiagnosticItem(file, lNum, col, msg))
                continue
            }

            // 4. Rust
            val rm = rustErrorRegex.find(line)
            if (rm != null) {
                val file = rm.groupValues[1]
                val lNum = rm.groupValues[2].toIntOrNull() ?: 1
                val col = rm.groupValues[3].toIntOrNull() ?: 1
                val msg = if (i > 0) lines[i - 1].trim() else "Rust compilation error"
                list.add(DiagnosticItem(file, lNum, col, msg))
                continue
            }
        }
        return list.distinctBy { "${it.file}:${it.line}:${it.message}" }.take(30)
    }
}

class RemoteFlowMcpProcessor(private val project: Project) {

    private val connectionManager get() = RemoteConnectionManager.getInstance(project)
    private val syncManager get() = FastSyncManager(project)

    fun handleMcpRequest(jsonBody: String): String {
        val method = extractJsonField(jsonBody, "method") ?: ""
        val id = extractJsonField(jsonBody, "id") ?: "1"

        return when (method) {
            "tools/list" -> buildToolsListResponse(id)
            "tools/call" -> handleToolCall(id, jsonBody)
            "initialize" -> buildInitializeResponse(id)
            else -> buildErrorResponse(id, -32601, "Method '$method' not found")
        }
    }

    private fun buildInitializeResponse(id: String): String = """
    {
      "jsonrpc": "2.0",
      "id": $id,
      "result": {
        "protocolVersion": "2024-11-05",
        "capabilities": {
          "tools": {}
        },
        "serverInfo": {
          "name": "Remote Flow MCP Server",
          "version": "1.0.0"
        }
      }
    }
    """.trimIndent()

    private fun buildToolsListResponse(id: String): String = """
    {
      "jsonrpc": "2.0",
      "id": $id,
      "result": {
        "tools": [
          {
            "name": "rf_status",
            "description": "Check Remote Flow SSH connection and active server status",
            "inputSchema": { "type": "object", "properties": {} }
          },
          {
            "name": "rf_exec",
            "description": "Execute a bash command directly on the remote Linux server with stdout/stderr return",
            "inputSchema": {
              "type": "object",
              "properties": {
                "cmd": { "type": "string", "description": "Bash command to execute remotely" }
              },
              "required": ["cmd"]
            }
          },
          {
            "name": "rf_sync",
            "description": "Perform fast differential code synchronization from local workspace to the remote server",
            "inputSchema": { "type": "object", "properties": {} }
          },
          {
            "name": "rf_pull",
            "description": "Download changes from remote server directory into local project",
            "inputSchema": { "type": "object", "properties": {} }
          },
          {
            "name": "rf_docker_list",
            "description": "List all Docker containers running on the remote server",
            "inputSchema": { "type": "object", "properties": {} }
          }
        ]
      }
    }
    """.trimIndent()

    private fun handleToolCall(id: String, body: String): String {
        val toolName = extractJsonField(body, "name") ?: ""

        when (toolName) {
            "rf_status" -> {
                val profile = connectionManager.config.activeProfile
                val text = "Remote Flow Status: Connected=${connectionManager.isConnected}, Server=${profile.name} (${profile.host}), RemotePath=${profile.remoteProjectPath}"
                return buildToolSuccessResponse(id, text)
            }

            "rf_sync" -> {
                val latch = CountDownLatch(1)
                var ok = false
                val logs = StringBuilder()
                val profile = connectionManager.config.activeProfile
                syncManager.syncSingleServer(
                    profile = profile,
                    onLog = { logs.append(it) },
                    onComplete = { success ->
                        ok = success
                        latch.countDown()
                    }
                )
                try { latch.await(60, TimeUnit.SECONDS) } catch (_: Exception) {}
                val msg = if (ok) "✓ Sync successful!\n$logs" else "✗ Sync failed:\n$logs"
                return buildToolSuccessResponse(id, msg, isError = !ok)
            }

            "rf_pull" -> {
                val latch = CountDownLatch(1)
                var ok = false
                val logs = StringBuilder()
                val profile = connectionManager.config.activeProfile
                syncManager.pullFromRemote(
                    profile = profile,
                    onLog = { logs.append(it) },
                    onComplete = { success ->
                        ok = success
                        latch.countDown()
                    }
                )
                try { latch.await(60, TimeUnit.SECONDS) } catch (_: Exception) {}
                val msg = if (ok) "✓ Pull successful!\n$logs" else "✗ Pull failed:\n$logs"
                return buildToolSuccessResponse(id, msg, isError = !ok)
            }

            "rf_docker_list" -> {
                val latch = CountDownLatch(1)
                val sb = StringBuilder()
                connectionManager.listDockerContainers { containers ->
                    if (containers.isEmpty()) {
                        sb.append("No Docker containers running or found on remote server.")
                    } else {
                        sb.append("Containers on server:\n")
                        for (c in containers) {
                            sb.append("• ${c.names} [${c.image}] - ${c.status} (Ports: ${c.ports})\n")
                        }
                    }
                    latch.countDown()
                }
                try { latch.await(15, TimeUnit.SECONDS) } catch (_: Exception) {}
                return buildToolSuccessResponse(id, sb.toString())
            }

            "rf_exec" -> {
                val cmd = extractJsonField(body, "cmd") ?: ""
                if (cmd.isBlank()) {
                    return buildErrorResponse(id, -32602, "Missing 'cmd' argument")
                }
                val latch = CountDownLatch(1)
                val out = StringBuilder()
                var exitCode = 0
                val profile = connectionManager.config.activeProfile

                connectionManager.executeStreamingCommand(
                    cmd = cmd,
                    workingDir = profile.remoteProjectPath,
                    timeoutSeconds = 300,
                    onOutput = { out.append(it) },
                    onComplete = { code ->
                        exitCode = code
                        latch.countDown()
                    }
                )
                try { latch.await(300, TimeUnit.SECONDS) } catch (_: Exception) {}

                val finalOutput = out.toString()
                val diagnostics = DiagnosticsExtractor.extract(finalOutput)
                val diagInfo = if (diagnostics.isNotEmpty()) {
                    "\n\n[Parsed Diagnostics: ${diagnostics.size} issues found]\n" +
                            diagnostics.joinToString("\n") { "• ${it.file}:${it.line}:${it.column} ${it.message}" }
                } else ""

                val text = "$finalOutput\n[exit code: $exitCode]$diagInfo"
                return buildToolSuccessResponse(id, text, isError = (exitCode != 0))
            }

            else -> return buildErrorResponse(id, -32601, "Unknown tool: $toolName")
        }
    }

    private fun buildToolSuccessResponse(id: String, text: String, isError: Boolean = false): String {
        val safeText = escapeJson(text)
        return """
        {
          "jsonrpc": "2.0",
          "id": $id,
          "result": {
            "content": [
              {
                "type": "text",
                "text": "$safeText"
              }
            ],
            "isError": $isError
          }
        }
        """.trimIndent()
    }

    private fun buildErrorResponse(id: String, code: Int, message: String): String {
        return """
        {
          "jsonrpc": "2.0",
          "id": $id,
          "error": {
            "code": $code,
            "message": "${escapeJson(message)}"
          }
        }
        """.trimIndent()
    }

    private fun extractJsonField(json: String, field: String): String? {
        val regex = Regex(""""$field"\s*:\s*(?:"([^"]*)"|([0-9]+)|true|false)""")
        val match = regex.find(json) ?: return null
        return match.groupValues[1].ifEmpty { match.groupValues[2] }
    }

    private fun escapeJson(str: String): String {
        return str.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\b", "\\b")
            .replace("\u000C", "\\f")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}
