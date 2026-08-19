plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.17.0"
}

group   = "com.llmcopilot"
version = "2.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = "17"
    targetCompatibility = "17"
    options.release.set(17)
}

// ── Locate installed IntelliJ IDEA ───────────────────────────────────────────
val ideaPath: String = when {
    project.hasProperty("ideaPath")         -> project.property("ideaPath").toString()
    project.hasProperty("intellijIdeaPath") -> project.property("intellijIdeaPath").toString()
    file("/Applications/IntelliJ IDEA 2026.1.app").exists()  -> "/Applications/IntelliJ IDEA 2026.1.app"
    file("/Applications/IntelliJ IDEA 2026.2.app").exists()  -> "/Applications/IntelliJ IDEA 2026.2.app"
    file("/Applications/IntelliJ IDEA.app").exists()          -> "/Applications/IntelliJ IDEA.app"
    file("/Applications/IntelliJ IDEA CE.app").exists()       -> "/Applications/IntelliJ IDEA CE.app"
    file("${System.getProperty("user.home")}/Applications/IntelliJ IDEA.app").exists() ->
        "${System.getProperty("user.home")}/Applications/IntelliJ IDEA.app"
    file("C:/Program Files/JetBrains/IntelliJ IDEA 2026.1").exists() ->
        "C:/Program Files/JetBrains/IntelliJ IDEA 2026.1"
    file("C:/Program Files/JetBrains/IntelliJ IDEA").exists() ->
        "C:/Program Files/JetBrains/IntelliJ IDEA"
    else -> throw GradleException(
        "\nCannot find IntelliJ IDEA. Add to gradle.properties:\n" +
        "  intellijIdeaPath=/Applications/IntelliJ IDEA 2026.1.app\n"
    )
}

println("[LLM Copilot] Building against: $ideaPath")

dependencies {
    intellijPlatform {
        local(ideaPath)
        pluginVerifier()
        zipSigner()
    }
    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.mockito:mockito-core:5.20.0")
}

intellijPlatform {
    pluginConfiguration {
        name = "LLM Copilot"
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "261"
            untilBuild = provider { null }
        }
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    // Tests run on the IDE's JetBrains Runtime, which can be newer than the
    // JDK the mocking engine officially supports. Allow the fallback.
    systemProperty("net.bytebuddy.experimental", "true")
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.SHORT
    }
}

tasks.named("buildSearchableOptions") { enabled = false }
