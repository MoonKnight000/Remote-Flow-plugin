package uz.remote.flow.stack

import uz.remote.flow.ssh.ForwardDirection
import uz.remote.flow.ssh.PortMapping
import java.io.File

enum class TechStack(val displayName: String) {
    GRADLE("Java / Kotlin (Gradle)"),
    MAVEN("Java / Kotlin (Maven)"),
    NODE("Node.js / TypeScript"),
    PYTHON("Python"),
    GO("Go (Golang)"),
    RUST("Rust"),
    PHP("PHP (Laravel / Symfony)"),
    GENERIC("Generic / Custom")
}

data class StackPreset(
    val stack: TechStack,
    val runCommand: String,
    val debugCommand: String,
    val testCommand: String,
    val buildCommand: String,
    val defaultPorts: MutableList<PortMapping>,
    val recommendedExcludes: String
)

object ProjectStackDetector {

    fun detect(projectDir: File): StackPreset {
        if (!projectDir.exists() || !projectDir.isDirectory) {
            return genericPreset()
        }

        // 1. Check Gradle
        if (File(projectDir, "build.gradle").exists() || 
            File(projectDir, "build.gradle.kts").exists() || 
            File(projectDir, "gradlew").exists()
        ) {
            return StackPreset(
                stack = TechStack.GRADLE,
                runCommand = "./gradlew bootRun",
                debugCommand = "./gradlew bootRun --debug-jvm",
                testCommand = "./gradlew test",
                buildCommand = "./gradlew build -x test",
                defaultPorts = mutableListOf(
                    PortMapping(8080, 8080, "Spring Boot App", true, ForwardDirection.LOCAL_TO_REMOTE),
                    PortMapping(5005, 5005, "JVM Remote Debug", true, ForwardDirection.LOCAL_TO_REMOTE),
                    PortMapping(5432, 5432, "PostgreSQL", false, ForwardDirection.LOCAL_TO_REMOTE),
                    PortMapping(6379, 6379, "Redis", false, ForwardDirection.LOCAL_TO_REMOTE)
                ),
                recommendedExcludes = ".git, .gradle, build, .idea, out, target, *.log, *.tmp, *.class"
            )
        }

        // 2. Check Maven
        if (File(projectDir, "pom.xml").exists() || File(projectDir, "mvnw").exists()) {
            return StackPreset(
                stack = TechStack.MAVEN,
                runCommand = "./mvnw spring-boot:run",
                debugCommand = "./mvnw spring-boot:run -Dspring-boot.run.jvmArguments=\"-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005\"",
                testCommand = "./mvnw test",
                buildCommand = "./mvnw clean package -DskipTests",
                defaultPorts = mutableListOf(
                    PortMapping(8080, 8080, "Spring Boot App", true, ForwardDirection.LOCAL_TO_REMOTE),
                    PortMapping(5005, 5005, "JVM Remote Debug", true, ForwardDirection.LOCAL_TO_REMOTE),
                    PortMapping(5432, 5432, "PostgreSQL", false, ForwardDirection.LOCAL_TO_REMOTE),
                    PortMapping(6379, 6379, "Redis", false, ForwardDirection.LOCAL_TO_REMOTE)
                ),
                recommendedExcludes = ".git, target, .idea, *.log, *.tmp, *.class"
            )
        }

        // 3. Check Node.js / TypeScript
        val pkgJson = File(projectDir, "package.json")
        if (pkgJson.exists()) {
            val hasPnpm = File(projectDir, "pnpm-lock.yaml").exists()
            val hasYarn = File(projectDir, "yarn.lock").exists()
            val hasBun = File(projectDir, "bun.lockb").exists() || File(projectDir, "bun.lock").exists()
            val pm = when {
                hasBun -> "bun"
                hasPnpm -> "pnpm"
                hasYarn -> "yarn"
                else -> "npm"
            }
            val runCmd = if (pm == "bun") "bun run dev" else "$pm run dev"
            val buildCmd = if (pm == "bun") "bun run build" else "$pm run build"
            val testCmd = if (pm == "bun") "bun test" else "$pm test"

            return StackPreset(
                stack = TechStack.NODE,
                runCommand = runCmd,
                debugCommand = "node --inspect=0.0.0.0:9229 node_modules/.bin/vite",
                testCommand = testCmd,
                buildCommand = buildCmd,
                defaultPorts = mutableListOf(
                    PortMapping(3000, 3000, "Frontend / Web", true, ForwardDirection.LOCAL_TO_REMOTE),
                    PortMapping(5173, 5173, "Vite Dev Server", false, ForwardDirection.LOCAL_TO_REMOTE),
                    PortMapping(9229, 9229, "Node Inspector", false, ForwardDirection.LOCAL_TO_REMOTE),
                    PortMapping(5432, 5432, "PostgreSQL", false, ForwardDirection.LOCAL_TO_REMOTE),
                    PortMapping(6379, 6379, "Redis", false, ForwardDirection.LOCAL_TO_REMOTE)
                ),
                recommendedExcludes = ".git, node_modules, dist, .next, .nuxt, build, .idea, *.log"
            )
        }

        // 4. Check Python
        if (File(projectDir, "pyproject.toml").exists() || 
            File(projectDir, "requirements.txt").exists() || 
            File(projectDir, "Pipfile").exists() || 
            File(projectDir, "manage.py").exists()
        ) {
            val isDjango = File(projectDir, "manage.py").exists()
            val isFastApi = File(projectDir, "main.py").exists()
            val runCmd = when {
                isDjango -> "python manage.py runserver 0.0.0.0:8000"
                isFastApi -> "uvicorn main:app --host 0.0.0.0 --port 8000 --reload"
                else -> "python main.py"
            }
            return StackPreset(
                stack = TechStack.PYTHON,
                runCommand = runCmd,
                debugCommand = "python -m debugpy --listen 0.0.0.0:5678 $runCmd",
                testCommand = "pytest",
                buildCommand = "pip install -r requirements.txt",
                defaultPorts = mutableListOf(
                    PortMapping(8000, 8000, "Python Web App", true, ForwardDirection.LOCAL_TO_REMOTE),
                    PortMapping(5678, 5678, "debugpy Debugger", false, ForwardDirection.LOCAL_TO_REMOTE),
                    PortMapping(5432, 5432, "PostgreSQL", false, ForwardDirection.LOCAL_TO_REMOTE),
                    PortMapping(6379, 6379, "Redis", false, ForwardDirection.LOCAL_TO_REMOTE)
                ),
                recommendedExcludes = ".git, __pycache__, .venv, venv, .idea, *.pyc, *.log, .pytest_cache"
            )
        }

        // 5. Check Go
        if (File(projectDir, "go.mod").exists()) {
            return StackPreset(
                stack = TechStack.GO,
                runCommand = "go run .",
                debugCommand = "dlv debug --headless --listen=:40000 --api-version=2 --accept-multiclient",
                testCommand = "go test ./...",
                buildCommand = "go build -o app .",
                defaultPorts = mutableListOf(
                    PortMapping(8080, 8080, "Go Web Server", true, ForwardDirection.LOCAL_TO_REMOTE),
                    PortMapping(40000, 40000, "Delve Debugger", false, ForwardDirection.LOCAL_TO_REMOTE),
                    PortMapping(5432, 5432, "PostgreSQL", false, ForwardDirection.LOCAL_TO_REMOTE),
                    PortMapping(6379, 6379, "Redis", false, ForwardDirection.LOCAL_TO_REMOTE)
                ),
                recommendedExcludes = ".git, bin, app, .idea, *.log"
            )
        }

        // 6. Check Rust
        if (File(projectDir, "Cargo.toml").exists()) {
            return StackPreset(
                stack = TechStack.RUST,
                runCommand = "cargo run",
                debugCommand = "cargo run",
                testCommand = "cargo test",
                buildCommand = "cargo build --release",
                defaultPorts = mutableListOf(
                    PortMapping(8080, 8080, "Rust Web Server", true, ForwardDirection.LOCAL_TO_REMOTE),
                    PortMapping(5432, 5432, "PostgreSQL", false, ForwardDirection.LOCAL_TO_REMOTE)
                ),
                recommendedExcludes = ".git, target, .idea, *.log"
            )
        }

        // 7. Check PHP
        if (File(projectDir, "composer.json").exists()) {
            val isLaravel = File(projectDir, "artisan").exists()
            val runCmd = if (isLaravel) "php artisan serve --host=0.0.0.0 --port=8000" else "php -S 0.0.0.0:8000"
            val testCmd = if (isLaravel) "php artisan test" else "vendor/bin/phpunit"
            return StackPreset(
                stack = TechStack.PHP,
                runCommand = runCmd,
                debugCommand = runCmd,
                testCommand = testCmd,
                buildCommand = "composer install",
                defaultPorts = mutableListOf(
                    PortMapping(8000, 8000, "PHP Web Server", true, ForwardDirection.LOCAL_TO_REMOTE),
                    PortMapping(9003, 9003, "Xdebug", false, ForwardDirection.LOCAL_TO_REMOTE),
                    PortMapping(3306, 3306, "MySQL", false, ForwardDirection.LOCAL_TO_REMOTE),
                    PortMapping(6379, 6379, "Redis", false, ForwardDirection.LOCAL_TO_REMOTE)
                ),
                recommendedExcludes = ".git, vendor, .idea, storage/logs, *.log"
            )
        }

        return genericPreset()
    }

    private fun genericPreset(): StackPreset {
        return StackPreset(
            stack = TechStack.GENERIC,
            runCommand = "./run.sh",
            debugCommand = "./debug.sh",
            testCommand = "./test.sh",
            buildCommand = "./build.sh",
            defaultPorts = mutableListOf(
                PortMapping(8080, 8080, "Web Server", true, ForwardDirection.LOCAL_TO_REMOTE),
                PortMapping(5432, 5432, "PostgreSQL", false, ForwardDirection.LOCAL_TO_REMOTE)
            ),
            recommendedExcludes = ".git, .idea, out, build, *.log, *.tmp"
        )
    }
}
