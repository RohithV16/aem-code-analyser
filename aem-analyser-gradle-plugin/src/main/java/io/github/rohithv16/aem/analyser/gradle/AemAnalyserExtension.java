package io.github.rohithv16.aem.analyser.gradle;

import javax.inject.Inject;

import org.gradle.api.Project;
import org.gradle.api.provider.Property;

/**
 * {@code aemAnalyser { failOn = "WARNING"; enableSonar = false }} — the same three knobs the Maven
 * plugin exposes, deliberately: a project that moves between the two builds must not discover a
 * different gate.
 */
public abstract class AemAnalyserExtension {

    /** Minimum severity that fails the build: {@code NONE}, {@code ERROR}, {@code WARNING}, {@code INFO}. */
    public abstract Property<String> getFailOn();

    /** Whether the SonarJava phase runs. Off halves the runtime on a large tree. */
    public abstract Property<Boolean> getEnableSonar();

    /** Fail only above this many warnings; {@code -1} disables the threshold. */
    public abstract Property<Integer> getMaxWarnings();

    @Inject
    public AemAnalyserExtension(Project project) {
        getFailOn().convention("ERROR");
        getEnableSonar().convention(true);
        getMaxWarnings().convention(-1);
    }
}
