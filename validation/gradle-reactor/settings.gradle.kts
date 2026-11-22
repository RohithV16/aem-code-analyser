/*
 * Fixture for the Gradle plugin's functional proof. It is deliberately NOT part of the Maven
 * reactor: it only exists so `gradle aemAnalyserCheck` can be run twice against a tree that does
 * not change, which is the only way to show the task is cacheable.
 */
pluginManagement {
    // The included build's own dependency on aem-analyser-core resolves against these repositories,
    // not against the plugin project's: an includeBuild plugin classpath is built from here.
    repositories {
        mavenLocal()
        gradlePluginPortal()
    }
    includeBuild("../../aem-analyser-gradle-plugin")
}

rootProject.name = "aem-analyser-gradle-fixture"
