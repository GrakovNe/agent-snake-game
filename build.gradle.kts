plugins {
    kotlin("jvm") version "2.4.0"
}

group = "org.grakovne"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jfree:jfreechart:1.5.5")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

fun registerRun(name: String, mainClassName: String, description: String) =
    tasks.register<JavaExec>(name) {
        group = "snake"
        this.description = description
        mainClass.set(mainClassName)
        classpath = sourceSets["main"].runtimeClasspath
        listOf(
            "size", "games", "seed", "delay", "strategy", "parallelism",
            "huntDump", "autopsy", "autopsyEvery",
        ).forEach { key ->
            (project.findProperty(key) as? String)?.let { systemProperty(key, it) }
        }
    }

registerRun(
    "show",
    "org.grakovne.snake.agent.app.ShowKt",
    "Watch a strategy play with the Swing UI: ./gradlew show -Psize=40 -Pdelay=20 -Pstrategy=greedy"
)
registerRun(
    "benchmark",
    "org.grakovne.snake.agent.app.BenchmarkKt",
    "Headless benchmark: ./gradlew benchmark -Psize=30 -Pgames=200 -Pseed=42 -Pstrategy=greedy"
)
registerRun(
    "probe",
    "org.grakovne.snake.agent.app.ProbeKt",
    "Dump terminal boards of lost games: ./gradlew probe -Psize=15 -Pgames=30 -Pstrategy=safe"
)
registerRun(
    "arena",
    "org.grakovne.snake.agent.app.ArenaMainKt",
    "Parallel multi-strategy leaderboard on a shared seed set: ./gradlew arena -Pgames=100"
)
