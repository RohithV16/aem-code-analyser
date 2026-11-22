plugins {
    `java-gradle-plugin`
    `maven-publish`
}

group = "io.github.rohithv16"
version = providers.fileContents(layout.projectDirectory.file("../docs/_data/version.yml"))
        .asText.map { text -> Regex("version:\\s*(\\S+)").find(text)?.groupValues?.get(1) ?: "1.1.0-SNAPSHOT" }
        .get()

repositories {
    mavenLocal()
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    // The plugin drives the core API in-process: no `java -jar`, so Gradle can cache the task on
    // the project tree instead of shelling out to a process it cannot introspect.
    implementation("io.github.rohithv16:aem-analyser-core:${version}")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

gradlePlugin {
    plugins {
        create("aemAnalyser") {
            id = "io.github.rohithv16.aem-analyser"
            implementationClass = "io.github.rohithv16.aem.analyser.gradle.AemAnalyserPlugin"
            displayName = "AEM Code Analyser"
            description = "Cross-module AEM static analysis as a cacheable Gradle task."
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:all")
}
