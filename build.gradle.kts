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

// ── Locate an IntelliJ IDEA to build against ─────────────────────────────────
// A local installation is preferred: it avoids a ~1 GB SDK download. CI runners
// have none, so null here means "fall back to the downloadable Community SDK".
// Pass -PignoreLocalIde to force the CI path on a developer machine.
val ideaPath: String? = when {
    project.hasProperty("ignoreLocalIde")   -> null
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
    else -> null
}

// Version used only when no local installation is available (CI). Override with
// -PplatformVersion=... or a platformVersion entry in gradle.properties.
val platformVersion: String = providers.gradleProperty("platformVersion").getOrElse("2026.1")

if (ideaPath != null) {
    println("[LLM Copilot] Building against local install: $ideaPath")
} else {
    println("[LLM Copilot] No local IntelliJ IDEA found — downloading IntelliJ IDEA $platformVersion")
}

dependencies {
    intellijPlatform {
        // useInstaller = false resolves the platform zip from JetBrains' Maven
        // repository instead of an OS-specific installer, which is what works on CI.
        if (ideaPath != null) local(ideaPath)
        else intellijIdea(platformVersion) { useInstaller = false }
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
