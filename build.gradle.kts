plugins {
    id("org.jetbrains.kotlin.jvm") version "2.2.21"
    id("org.jetbrains.intellij.platform") version "2.0.1"
}

group = "uz.remote.flow"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
        intellijDependencies()
        localPlatformArtifacts()
    }
}

dependencies {
    intellijPlatform {
        local(file("C:/Users/Murodjon/AppData/Local/Programs/IntelliJ IDEA"))
        instrumentationTools()
    }

    implementation("com.hierynomus:sshj:0.38.0")
    implementation("org.slf4j:slf4j-simple:2.0.13")
}

intellijPlatform {
    pluginConfiguration {
        name = "Remote Flow"
        version = "1.0.0"
        vendor {
            name = "Murodjon"
            email = "murodjon@dev.local"
            url = "https://github.com/murodjon"
        }
        description = "Lightweight Remote Server Runner and Gateway-style Manager developed by Murodjon."
    }
}

tasks.compileJava {
    options.release.set(21)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

tasks.instrumentCode {
    enabled = false
}

tasks.buildSearchableOptions {
    enabled = false
}
