package io.github.rohithv16.aem.analyser.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

/**
 * Registers the {@code aemAnalyser} extension and the {@code aemAnalyserCheck} task, and wires the
 * task into {@code check} so a Gradle build fails on findings the way the Maven build does.
 *
 * <p>The task declares the project tree as an input and the reports as outputs, so
 * {@code --build-cache} can replay an unchanged tree and {@code --configuration-cache} does not
 * invalidate. That is the whole reason this exists: a {@code JavaExec} wrapper around the CLI jar
 * declares nothing and can never be {@code UP-TO-DATE}.</p>
 */
public final class AemAnalyserPlugin implements Plugin<Project> {

    /** Extension name, so {@code aemAnalyser { ... }} resolves. */
    public static final String EXTENSION_NAME = "aemAnalyser";

    /** Task name wired into {@code check}. */
    public static final String TASK_NAME = "aemAnalyserCheck";

    @Override
    public void apply(Project project) {
        AemAnalyserExtension extension = project.getExtensions()
                .create(EXTENSION_NAME, AemAnalyserExtension.class, project);

        project.getTasks().register(TASK_NAME, AemAnalyserCheckTask.class, task -> {
            task.setGroup("verification");
            task.setDescription("Analyses the whole AEM project for cross-module defects.");
            task.getProjectDirectory().convention(project.getLayout().getProjectDirectory());
            task.getProjectFiles().from(project.fileTree(project.getProjectDir(), tree -> {
                // Reports are written inside the tree, so the build directory cannot be an input.
                tree.exclude("build/**", ".gradle/**", "target/**", "node_modules/**",
                        ".git/**", "**/*.class", "out/**");
            }));
            task.getOutputDirectory().convention(
                    project.getLayout().getBuildDirectory().dir("aem-analyser"));
            task.getFailOn().convention(extension.getFailOn());
            task.getEnableSonar().convention(extension.getEnableSonar());
            task.getMaxWarnings().convention(extension.getMaxWarnings());
        });

        project.getTasks().named("check").configure(task -> task.dependsOn(TASK_NAME));
    }
}
