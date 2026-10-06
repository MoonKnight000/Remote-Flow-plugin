# Remote Flow — Remote Server Runner & Development Gateway

[![IntelliJ Platform](https://img.shields.io/badge/Platform-IntelliJ%20IDEA%202024%2B-blue.svg)](https://www.jetbrains.com/idea/)
[![Plugin Version](https://img.shields.io/badge/Version-1.0.0-emerald.svg)](https://github.com/MoonKnight000/Remote-Flow-plugin)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2-purple.svg)](https://kotlinlang.org/)
[![License](https://img.shields.io/badge/License-MIT-amber.svg)](LICENSE)

**Remote Flow** is a high-performance, zero-overhead remote development plugin for IntelliJ IDEA developed by **Murodjon**. It allows developers to code locally on their fast Windows, macOS, or Linux machine while seamlessly building, synchronizing, running, debugging, and monitoring applications directly on remote Linux servers or cloud instances via secure SSH.

---

## ⚡ Why Remote Flow? (Zero Headless IDE Overhead)

Traditional remote development solutions (such as JetBrains Gateway or headless remote IDE backends) require running a full IntelliJ backend instance on the remote server, consuming **2 GB – 4 GB of RAM**, generating heavy disk I/O, and causing UI lag over high-latency networks.

**Remote Flow takes a radically lightweight approach:**
- **Local IDE Performance**: Your IDE, indexing, syntax analysis, and autocomplete run 100% locally on your machine with zero lag.
- **Server Resources Conserved**: The remote server only runs your actual application (e.g. Spring Boot, Gradle, Docker, DBs) — saving gigabytes of memory.
- **Instant Bi-Directional Forwarding**: Remote service ports (e.g. 8080, 5432, 6379, 5005) are tunneled transparently to `localhost` on your local PC.

---

## 🚀 Key Features

### 1. Native Run Configuration Integration
- **Run Configuration Dropdown**: Select the native **Remote Flow** configuration directly in IntelliJ's main header toolbar and run or debug using standard IDE buttons (`Shift + F10` / `Shift + F9`).
- **Smart Run Delegation (`RemoteFlowProgramRunner`)**: Seamlessly routes standard IntelliJ Run and Debug actions to the active remote server with automatic port forwarding.
- **Interactive Ready Detection**: Automatically parses stdout to detect when Spring Boot / Tomcat / Netty is ready, printing clickable local URLs (e.g. `http://localhost:8080`).

### 2. High-Speed Differential Code Synchronization
- **Rsync & SSH SFTP Engines**: Sync only changed files in milliseconds using delta compression and checksum verification.
- **Intelligent Ignore Rules**: Built-in smart filtering excludes heavy folders (`.git`, `.gradle`, `build`, `target`, `out`, `.idea`, `node_modules`, temporary files, and log files).
- **Auto-Sync on Save**: Automatically syncs modified files to the remote server whenever you press `Ctrl + S`.
- **Targeted Sync**: Right-click any file, package, or directory in the Project View or Editor to sync only that specific file or folder.

### 3. One-Click Remote JVM Debugging
- Automatically forwards JVM remote socket transport port `5005` to `localhost:5005`.
- Automatically connects IntelliJ's native remote debugger to the running application on the server as soon as the JVM socket listener opens.
- Set breakpoints, inspect variables, evaluate expressions, and step through code as if the application was running locally.

### 4. Bi-Directional SSH Port Forwarding & Port Scanner
- **Transparent Port Tunnels**: Map remote databases, message queues, and microservices directly to your local computer:
  - `localhost:8080` ➔ Remote Backend HTTP API
  - `localhost:5432` ➔ Remote PostgreSQL Database
  - `localhost:3306` ➔ Remote MySQL Database
  - `localhost:6379` ➔ Remote Redis Cache
  - `localhost:5672` / `15672` ➔ Remote RabbitMQ & Management UI
  - `localhost:27017` ➔ Remote MongoDB
  - `localhost:5173` / `3000` ➔ Frontend Dev Servers (Vite / React / Next.js)
- **Active Listening Port Scanner**: Scans remote `ss` / `netstat` sockets to detect active listening ports and processes with friendly process icons, allowing one-click tunnel creation.
- **Bi-Directional Support**: Supports both `Local -> Host` (ssh `-L`) and `Host -> Local` (ssh `-R`) forwarding directions.

### 5. Hardware Resource Monitor & Remote Task Manager
- **Live Health Metrics**: Monitor remote **CPU utilization**, **RAM consumption**, and **Disk usage** in real time directly from the IntelliJ Status Bar and Tool Window.
- **Interactive Remote Task Manager**: Inspect active Linux processes, memory usage, CPU load, and terminate runaway or hung processes with one click.

### 6. Remote File Explorer & Configuration Manager
- **Remote File Browser**: Navigate remote server directory trees, view file sizes and permissions, create directories, upload local files, and inspect files remotely.
- **Remote Config Editor**: Safely view and edit remote `.env`, `application.yml`, and `application.properties` files directly from IntelliJ with automatic backup creation (`.bak`) before saving.

### 7. Java / Kotlin Hot Reload & Fast Class Swap (`Alt + Shift + H`)
- **Sub-Second Code Reload**: Recompiles the current Java/Kotlin class locally and syncs bytecode directly to the remote server without restarting the application.
- **JVM Debugger HotSwap & DevTools Support**: Automatically triggers IntelliJ's native JVM HotSwap when debugging on port 5005, or triggers Spring Boot DevTools restart in < 1 second.

### 8. Sync Diff & Dry-Run Preview
- **Interactive Diff Viewer**: Preview all modified, added, and deleted files in a dedicated dialog before uploading.
- **Visual Status Badges**: Filter by `Added`, `Modified`, or `Deleted` files with instant search and one-click "🚀 Sync Now".

### 9. Server Health Alerts & Balloon Notifications
- **Proactive Resource Warnings**: Automatic balloon notifications when remote server **RAM >= 90%**, **CPU >= 90%**, or **Disk >= 95%** to prevent unexpected OOM crashes.
- **Smart Cooldown**: Intelligent debouncing avoids notification spam while keeping you alerted to critical bottlenecks.

### 10. Server Environments & Production Safety Protection
- **Color-Coded Badges**: Tag profiles as `DEV` (green), `STAGING` (amber), or `PROD` (red) visible across status bar and tool window.
- **Accidental Execution Guard**: Explicit confirmation dialogs before running, stopping, or syncing files on live `PRODUCTION` servers.

### 11. Execution Hooks & Auto-Open Browser
- **Pre-Run & Post-Run Hooks**: Execute custom bash commands on the server before building (e.g. `npm run build`, `./mvnw compile`) or when ready.
- **Auto-Open Browser**: Automatically launches your default browser to `http://localhost:8080` as soon as Spring Boot or Netty completes startup.

### 12. Unified Log Tool Window & Embedded Terminal
- **Colorized ANSI Console**: Dedicated "Remote Flow Log" tool window at the bottom of the IDE with real-time log streaming, ANSI color support, search filtering, log level categories (`ALL`, `RUN`, `SYNC`, `SSH`, `SYSTEM`), pause, and log export.
- **Integrated SSH Terminal**: Open interactive SSH terminal sessions to the active server directly inside IntelliJ with a single click.

### 13. Universal Cross-IDE & Multi-Stack Auto-Detection
- **All JetBrains IDEs Supported**: Seamlessly compatible with IntelliJ IDEA, PyCharm, WebStorm, GoLand, PhpStorm, CLion, RustRover, and Rider.
- **Auto-Detect Technology Stacks**: Automatically recognizes Node.js (`package.json`), Python (`pyproject.toml` / `requirements.txt`), Go (`go.mod`), Rust (`Cargo.toml`), PHP (`composer.json`), Maven (`pom.xml`), and Gradle (`build.gradle`), pre-filling optimal run, debug, test, and build commands.

### 14. 1-Click SSH Config Import & Jump Host (ProxyJump)
- **Import from `~/.ssh/config`**: Automatically discovers your existing OpenSSH hosts, users, ports, and private keys.
- **SSH Bastion / Jump Host**: Connect seamlessly to servers in private corporate VPCs through an SSH Jump Host / Bastion proxy.

### 15. Bidirectional Sync & Sub-Second Git-Aware Sync
- **Pull from Remote**: Download database migrations, generated files, or remote changes back into your local project with one click.
- **Git-Aware Diff Sync**: Sync only modified and untracked git files in milliseconds without scanning the entire workspace.

### 16. Remote Docker & Docker Compose Explorer
- **Container Explorer**: Inspect running containers, images, statuses, and port mappings in the dedicated Docker tab.
- **Lifecycle & Logs**: Start, stop, restart containers, and view live container logs directly inside the IDE.
- **1-Click Container Port Tunnel**: Map container internal ports directly to `localhost` in one click.

### 17. AI Coding Agent Bridge & Model Context Protocol (MCP)
- **Native AI CLI (`rf`)**: Run remote tests, builds, memory tuning, diffs, and diagnostics directly from your command line (`.\rf.cmd test`, `.\rf.cmd build`, `.\rf.cmd diff`, `.\rf.cmd mem 4g`, `.\rf.cmd env`, `.\rf.cmd terminal`, `.\rf.cmd docker`, `.\rf.cmd diagnostics`).
- **Model Context Protocol (MCP)**: Connect AI coding assistants (Cursor, Claude Code, Antigravity, Windsurf) to Remote Flow via `/api/mcp` for native tool execution with compiler error parsing.

### 18. Remote Memory & JVM Allocation Tuner (`Alt + Shift + M`)
- **Visual Memory Allocator**: Tune JVM Max Heap (`-Xmx`), Initial Heap (`-Xms`), Node.js Old Space (`--max-old-space-size`), and Docker limits with live remote server memory gauge (`free -m`).
- **Native Memory Presets**: Instant selection for 512 MB, 1 GB, 2 GB, 4 GB, 8 GB, or 16 GB, injected seamlessly into Gradle, Maven, Spring Boot, and Node.js runtimes.

### 19. 1-Click Embedded Terminal & Port Conflict Auto-Resolver (`Alt + Shift + T`)
- **Native IntelliJ Terminal Session**: Automatically opens an interactive SSH terminal inside IntelliJ's built-in Terminal tool window, auto-navigating to the remote project path with jump host support.
- **Intelligent Port Conflict Resolution**: Leverages IntelliJ's native `NetUtils` to automatically find available socket ports whenever local ports (e.g. 8080, 5432) are occupied.

### 20. Network Disconnect (Wi-Fi) Resilience & Background Process Safe Guard
- **SIGHUP Drop Protection**: Remote execution is wrapped in a detached process supervisor (`nohup` + `trap '' HUP PIPE`). Switching Wi-Fi networks or temporary internet drops will never kill your running remote server process.
- **Auto Re-Attachment**: When your connection restores, Remote Flow re-checks PID liveness, restores port forwarding, and re-attaches live log streaming seamlessly.
- **IDE Exit Confirmation Dialog**: Prompting whether to cleanly stop the server process or keep it running in the background whenever IntelliJ is closed.

### 21. Visual Diff & Side-by-Side Comparison with Server (`Alt + Shift + C`)
- **IntelliJ Native Diff Viewer**: Right-click any file or directory in Project View or Editor to compare with the remote server version side-by-side with full syntax highlighting.
- **Folder Sync Preview**: Inspect all modified, added, and deleted files with double-click diff inspection and 1-click sync.

### 22. Remote Environment Variables & Secret Vault
- **Encrypted Profile Env Vault**: Manage sensitive credentials, API keys, and configurations per server profile without checking them into Git.
- **1-Click `.env` Import**: Automatically parses and imports local `.env` files directly into your remote execution profile.

### 23. Real-Time Remote Performance Profiler & JFR (`Alt + Shift + P`)
- **Native Embedded Performance Panel**: Embedded directly in the Run tool window console, showing real-time **CPU %** (green area chart) and **Heap Memory** (blue area chart) of the running remote application.
- **1-Click Recording & JFR Capture**: Click **"Start Recording"** (or use `rf profile start`) to initiate remote Java Flight Recorder (JFR) profiling or CPU/Memory sampling on the remote JVM.
- **Interactive Hotspots & Source Navigation**: On **"Stop Recording"**, downloads and parses the `.jfr` snapshot, displaying CPU top method hotspots, memory allocations, and GC pause stats with double-click editor navigation and 1-click **"Open in IntelliJ Profiler"**.

---

## ⌨️ Default Keyboard Shortcuts

| Shortcut | Action | Description |
| :--- | :--- | :--- |
| `Alt + Shift + R` | **Remote Run** | Sync differential changes, build, and run application on active remote server |
| `Alt + Shift + D` | **Remote Debug** | Run on remote server with JVM debug mode on port `5005` and auto-attach |
| `Alt + Shift + H` | **Hot Reload** | Recompile and reload current class on remote server in < 1s without restart |
| `Alt + Shift + S` | **Remote Stop** | Terminate running remote application process |
| `Alt + Shift + U` | **Fast Sync** | Synchronize all project files to the active remote server |
| `Alt + Shift + C` | **Compare with Server** | Open visual side-by-side diff comparing local file or folder with remote server |
| `Alt + Shift + M` | **Remote Memory Tuner** | Open JVM heap allocation and remote memory tuner dialog |
| `Alt + Shift + T` | **Open SSH Terminal** | Open 1-click interactive SSH terminal session inside IntelliJ |
| `Alt + Shift + P` | **Performance Profiler** | Start/stop remote CPU & Memory recording or inspect profiling snapshots |
| `Alt + Shift + L` | **Show Logs** | Focus and open the unified Remote Flow Log console |
| `Shift + F10` | **Standard Run** | *(When Run Delegation is enabled)* Automatically routes to Remote Run |
| `Shift + F9` | **Standard Debug** | *(When Run Delegation is enabled)* Automatically routes to Remote Debug |

---

## 🛠️ Quick Start Guide

### Step 1: Install the Plugin
1. In IntelliJ IDEA, go to **Settings** (`Ctrl + Alt + S`) ➔ **Plugins**.
2. Click the gear icon ⚙️ ➔ **Install Plugin from Disk...**.
3. Select `remote-flow-plugin-1.0.0.zip` from `build/distributions/`.
4. Restart IntelliJ IDEA if prompted.

### Step 2: Configure Your Server Profile
1. Go to **Settings** ➔ **Tools** ➔ **Remote Flow** (or click **Settings** in the Remote Flow Tool Window on the right).
2. Enter your server connection credentials:
   - **Server Name**: e.g., `Dev Server (Ubuntu)`
   - **Host & Port**: e.g., `192.168.1.100`, port `22`
   - **Authentication**: Password or SSH Private Key (`.pem` / `id_rsa`)
   - **Remote Project Path**: e.g., `/home/ubuntu/remote-flow/myapp`
3. Click **Test Connection** to verify access and remote directory setup.
4. Click **Detect Remote Java** to automatically detect and configure `JAVA_HOME`.
5. Under **Forwarded Ports**, add your desired ports or use **Scan Active Ports** to discover running services.
6. Click **Apply** and **OK**.

### Step 3: Run & Debug Remotely
1. Select your active server from the **Remote Flow** tool window or status bar widget.
2. Select **Remote Flow** from the Run Configuration dropdown in the top toolbar.
3. Click the standard IntelliJ **Run** (green triangle / `Shift + F10`) or **Debug** (green bug / `Shift + F9`) button.
4. Watch real-time build and execution logs in the **Remote Flow Log** panel.
5. Once your application starts, open `http://localhost:8080` in your local browser!

---

## 🔧 Building from Source

```bash
# Clone the repository
git clone https://github.com/MoonKnight000/Remote-Flow-plugin.git
cd Remote-Flow-plugin

# Build and verify the plugin
./gradlew buildPlugin

# Run in an isolated IntelliJ sandbox instance
./gradlew runIde
```
The packaged plugin archive will be generated at `build/distributions/remote-flow-plugin-1.0.0.zip`.

---

## 👤 Author & Support

- **Author**: Murodjon Bobobekov
- **Email**: [murodjonbobobekov000@gmail.com](mailto:murodjonbobobekov000@gmail.com)
- **GitHub**: [@MoonKnight000](https://github.com/MoonKnight000)
- **Repository**: [Remote-Flow-plugin](https://github.com/MoonKnight000/Remote-Flow-plugin)

---

## 📄 License

This project is licensed under the MIT License — see the [LICENSE](LICENSE) file for details.
