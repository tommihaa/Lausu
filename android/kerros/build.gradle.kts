plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.3")
}

// Testit lukevat saman datan ja samat tapaukset kuin Python-toteutus.
tasks.test {
    useJUnitPlatform()
    val repo = rootProject.projectDir.parentFile
    systemProperty("lausu.data", File(repo, "addon/globalPlugins/lausu/data").path)
    systemProperty("lausu.tapaukset", File(repo, "tests/tapaukset.txt").path)
    inputs.dir(File(repo, "addon/globalPlugins/lausu/data"))
    inputs.file(File(repo, "tests/tapaukset.txt"))
    // Valinnainen vertailu Python-toteutukseen (tests/vertailukorpus.py).
    val vertailu = providers.gradleProperty("lausu.vertailu").orNull
    if (vertailu != null) {
        val f = rootProject.file(vertailu)
        systemProperty("lausu.vertailu", f.path)
        inputs.file(f)
    }
    testLogging {
        events("failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
