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
            "size", "games", "seed", "delay", "tps", "port", "adminToken", "strategy", "parallelism",
            "huntDump", "autopsy", "autopsyEvery", "undig",
            "rolloutFree", "rolloutCount", "rngSpread",
            "episodeSeeds", "episodeRollouts", "episodeFree",
            "seedFrom", "rollouts", "out", "valueNet", "starveDiv", "everyNth", "phase1Policy", "rolloutPolicy", "mode", "in", "spawnCap", "dumpScores", "solverFree", "solverBudget", "starveMult",
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
    "webshow",
    "org.grakovne.snake.agent.app.WebShowKt",
    "Broadcast the show to browsers: ./gradlew webshow -Pport=8080 -Psize=40 -Ptps=2500"
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
    "tdlabel",
    "org.grakovne.snake.agent.app.TdLabelKt",
    "Fitted value iteration sweep: ./gradlew tdlabel -Pin=... -Pout=... -PvalueNet=..."
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

tasks.register<Jar>("fatJar") {
    group = "snake"
    description = "Self-contained webshow jar: java -jar snake-webshow.jar (-Dport=8080 -Dsize=40 ...)"
    archiveBaseName.set("snake-webshow")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes["Main-Class"] = "org.grakovne.snake.agent.app.WebShowKt" }
    from(sourceSets["main"].output)
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    if (file("data/value-net.onnx").exists()) {
        from("data/value-net.onnx")
    }
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}
