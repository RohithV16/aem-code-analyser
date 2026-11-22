plugins {
    java
    id("io.github.rohithv16.aem-analyser")
}

version = "1.0.0"
group = "com.example.aem"

repositories {
    mavenCentral()
}

aemAnalyser {
    // -PaemFailOn=NONE keeps the proof run green when it only needs the reports.
    failOn = providers.gradleProperty("aemFailOn").getOrElse("ERROR")
    enableSonar = false
}
