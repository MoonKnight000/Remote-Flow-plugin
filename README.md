# RemoteFlow: Ultra-light Remote Development & Docker Compose Plugin for IntelliJ IDEA

A zero-memory-overhead IntelliJ IDEA plugin designed to develop locally while building, running, and managing Docker containers remotely via secure SSH tunnels.

## Features

1. **Remote Docker Compose Controller:**
   - One-click docker compose up -d --build
   - docker compose down, estart, and ps
   - Real-time ANSI colored log streaming inside IntelliJ's ConsoleView.

2. **Automatic Zero-Overhead Port Forwarding:**
   - Instantly creates local tunnels for remote services:
     - localhost:6379 -> Remote Redis
     - localhost:5672 -> Remote RabbitMQ
     - localhost:15672 -> Remote RabbitMQ Management
     - localhost:8080 -> Remote App HTTP
   - Allows testing directly with local .http files (e.g. lat.http) or local clients.

3. **Differential File Sync:**
   - Excludes heavy local build artifacts (.git, .gradle, uild, .idea).
   - Syncs code diffs quickly using delta streaming.

4. **Zero Headless IDE Overhead:**
   - Does NOT run the heavy JetBrains Gateway / Headless IDE backend on the server.
   - Saves 2GB - 4GB RAM on the remote server.

## How to Run & Test the Plugin

1. Open this project (C:\Users\Murodjon\IdeaProjects\remote-flow-plugin) in IntelliJ IDEA.
2. Open the Gradle tool window on the right.
3. Run the task: intellijPlatform -> runIde (or execute ./gradlew runIde).
4. A sandbox IntelliJ IDEA window will open with the **RemoteFlow** tool window docked on the right side.
