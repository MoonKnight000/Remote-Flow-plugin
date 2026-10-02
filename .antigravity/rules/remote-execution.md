# AI Coding Agent Instructions for Remote Flow

This project is developed locally, but its runtime environment, databases (PostgreSQL, Redis, RabbitMQ, etc.), and services reside on a remote Linux server managed by the **Remote Flow** IntelliJ IDEA plugin.

## ⚠️ CRITICAL INSTRUCTION FOR ALL AI CODING AGENTS:
- ❌ **NEVER run `./gradlew test`, `./gradlew build`, or `./mvnw` locally** on Windows terminal. They will fail because remote databases, environment configs, and Linux containers are not running locally.
- ✅ **ALWAYS use the Remote Flow CLI (`rf`)** to trigger tests, builds, and commands directly on the remote server:
  - Run Tests: `.\rf.cmd test` (PowerShell: `.\rf.ps1 test`, Bash: `./rf test`)
  - Run Build: `.\rf.cmd build` (PowerShell: `.\rf.ps1 build`, Bash: `./rf build`)
  - Sync Files: `.\rf.cmd sync`
  - Execute Remote Command: `.\rf.cmd exec "<command>"`
  - Check Status: `.\rf.cmd status`

The CLI communicates with the active IntelliJ IDEA Remote Flow bridge, automatically differential-syncs all modified files to the remote server, runs the command inside the server environment, and streams live colorized output and exit codes back to your terminal.
