# AI Coding Agent Instructions for Remote Flow

This project is developed locally, but its runtime environment, databases (PostgreSQL, Redis, RabbitMQ, etc.), and services reside on a remote Linux server managed by the **Remote Flow** IntelliJ IDEA plugin.

## ⚠️ CRITICAL INSTRUCTION FOR ALL AI CODING AGENTS (Antigravity, Cursor, Claude Code, GitHub Copilot, etc.):
- ❌ **NEVER run `./gradlew test`, `./gradlew build`, or `./mvnw` locally** on Windows terminal. They will fail because remote databases, environment configs, and Linux containers are not running locally.
- ✅ **ALWAYS use the Remote Flow CLI (`rf`)** to trigger tests, builds, and commands directly on the remote server:
  - Run Tests: `.\rf.cmd test` (PowerShell: `.\rf.ps1 test`, Bash: `./rf test`)
  - Run Build: `.\rf.cmd build` (PowerShell: `.\rf.ps1 build`, Bash: `./rf build`)
  - Sync Files: `.\rf.cmd sync`
  - Pull Remote Files: `.\rf.cmd pull`
  - Compare Diff with Server: `.\rf.cmd diff [path]` (PowerShell: `.\rf.ps1 diff [path]`, Bash: `./rf diff [path]`)
  - Configure Remote Memory: `.\rf.cmd mem [maxHeap]` (e.g. `.\rf.cmd mem 4g`)
  - View Environment Variables: `.\rf.cmd env`
  - Open Remote SSH Terminal: `.\rf.cmd terminal`
  - Performance Profiler (CPU & RAM): `.\rf.cmd profile [start|stop|status]`
  - Manage Remote Docker: `.\rf.cmd docker` / `.\rf.cmd docker logs <container>`
  - Get Error Diagnostics: `.\rf.cmd diagnostics`
  - Execute Remote Command: `.\rf.cmd exec "<command>"`
  - Check Status: `.\rf.cmd status`
  - Model Context Protocol (MCP): Connect via `http://127.0.0.1:45789/api/mcp` for native AI agent tool execution.

The CLI communicates with the active IntelliJ IDEA Remote Flow bridge, automatically differential-syncs all modified files to the remote server, runs the command inside the server environment, and streams live colorized output and exit codes back to your terminal.

## 🌐 MANDATORY LANGUAGE AND LOCALIZATION RULE:
- 🔤 **ALL plugin UI texts, dialogs, labels, button texts, notifications, error messages, status logs, tooltips, action descriptions, code comments, and documentation MUST BE STRICTLY 100% IN ENGLISH.**
- ❌ **NEVER write Uzbek, Russian, or any non-English language** inside the plugin codebase, UI dialogs, notifications, logs, XML configs, CLI tools, or documentation. The plugin is built for global developers on the JetBrains Marketplace.
