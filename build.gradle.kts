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
    implementation("com.microsoft.onnxruntime:onnxruntime:1.20.0")
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
            "size", "games", "seed", "delay", "tps", "strategy", "parallelism",
            "huntDump", "autopsy", "autopsyEvery", "undig",
            "rolloutFree", "rolloutCount", "rngSpread",
            "episodeSeeds", "episodeRollouts", "episodeFree",
            "seedFrom", "rollouts", "out", "valueNet", "starveDiv", "everyNth",
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
    "tune",
    "org.grakovne.snake.agent.app.TuneKt",
    "Knob search over SafeGreedy configs: ./gradlew tune -Psize=15 -Pgames=200"
)
registerRun(
    "harvest",
    "org.grakovne.snake.agent.app.HarvestKt",
    "Value-net training data, shardable: ./gradlew harvest -Psize=30 -Pgames=200 -PseedFrom=0"
)
registerRun(
    "collect",
    "org.grakovne.snake.agent.app.CollectKt",
    "Self-play dataset for the loop-shape value: ./gradlew collect -Psize=30 -Pgames=1000"
)
registerRun(
    "fit",
    "org.grakovne.snake.agent.app.FitKt",
    "Ridge regression on collected loop features: ./gradlew fit -Psize=30"
)
registerRun(
    "arena",
    "org.grakovne.snake.agent.app.ArenaMainKt",
    "Parallel multi-strategy leaderboard on a shared seed set: ./gradlew arena -Pgames=100"
)
