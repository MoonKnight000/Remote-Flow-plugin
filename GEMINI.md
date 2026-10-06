# Antigravity Rules for Remote Flow

## 🌐 MANDATORY LANGUAGE AND LOCALIZATION RULE:
- 🔤 **ALL plugin UI texts, dialogs, labels, button texts, notifications, error messages, status logs, tooltips, action descriptions, code comments, and documentation MUST BE STRICTLY 100% IN ENGLISH.**
- ❌ **NEVER write Uzbek, Russian, or any non-English language** inside the plugin codebase, UI dialogs, notifications, logs, XML configs, CLI tools, or documentation. The plugin is built for global developers on the JetBrains Marketplace.

## ⚠️ REMOTE EXECUTION RULE:
- ❌ **NEVER run `./gradlew test`, `./gradlew build`, or `./mvnw` locally** on Windows terminal.
- ✅ **ALWAYS use the Remote Flow CLI (`rf`)** to trigger tests, builds, and commands directly on the remote server:
  - Run Tests: `.\rf.cmd test` (PowerShell: `.\rf.ps1 test`, Bash: `./rf test`)
  - Run Build: `.\rf.cmd build` (PowerShell: `.\rf.ps1 build`, Bash: `./rf build`)
  - Sync Files: `.\rf.cmd sync`
  - Pull Remote Files: `.\rf.cmd pull`
  - Compare Diff with Server: `.\rf.cmd diff [path]` (PowerShell: `.\rf.ps1 diff [path]`, Bash: `./rf diff [path]`)
  - Manage Remote Docker: `.\rf.cmd docker` / `.\rf.cmd docker logs <container>`
  - Get Error Diagnostics: `.\rf.cmd diagnostics`
  - Execute Remote Command: `.\rf.cmd exec "<command>"`
  - Check Status: `.\rf.cmd status`
