package io.github.rohithv16.aem.analyser.core.parser.vault;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the two workflow roots a project can ship as content: launchers under
 * {@code conf/global/settings/workflow/launcher} and models under
 * {@code .../settings/workflow/models} (or the legacy {@code var/workflow/models}).
 *
 * <p>These are {@code .content.xml} nodes, so every piece of information the rule needs is an
 * attribute, and the file is read as text with the line of each attribute kept. Line numbers are
 * what make a broken launcher actionable: the model id and the class name are single attributes in
 * a file that can describe dozens of nodes.
 *
 * <p>Only the two property families are extracted — {@code cq:workflowModel} on launchers and
 * {@code process} on model steps — because those are the links that fail silently. A glob that
 * matches no authored content is not modelled: content is authored on the environment, so the
 * project's own package cannot prove a glob wrong.
 */
public final class WorkflowContentParser {

    private static final Pattern ATTRIBUTE =
            Pattern.compile("(?:^|\\s)([A-Za-z0-9_:.\\-]+)=\"([^\"]*)\"");

    private static final String LAUNCHER_DIRECTORY = "/settings/workflow/launcher/";
    private static final String MODEL_DIRECTORY = "/settings/workflow/models/";
    private static final String LEGACY_MODEL_DIRECTORY = "/var/workflow/models/";

    /** A launcher node and the model id it activates. */
    public record Launcher(String nodePath, String modelPath, Path file, int line) {
    }

    /** A workflow model shipped by the project, with the PROCESS steps it declares. */
    public record WorkflowModel(String nodePath, String name, Path file, List<ProcessStep> steps) {
    }

    /** One {@code process="..."} attribute of a model. */
    public record ProcessStep(String className, Path file, int line) {
    }

    /** Everything the two roots declare. */
    public record WorkflowContent(List<Launcher> launchers, List<WorkflowModel> models) {

        public static WorkflowContent empty() {
            return new WorkflowContent(List.of(), List.of());
        }
    }

    public WorkflowContent scan(List<Path> contentXmlFiles) {
        List<Launcher> launchers = new ArrayList<>();
        List<WorkflowModel> models = new ArrayList<>();
        if (contentXmlFiles == null) {
            return WorkflowContent.empty();
        }
        for (Path file : contentXmlFiles) {
            String normalized = file.toAbsolutePath().normalize().toString().replace('\\', '/');
            if (!normalized.contains("/jcr_root/")) {
                continue;
            }
            boolean launcherPath = normalized.contains(LAUNCHER_DIRECTORY);
            boolean modelPath = normalized.contains(MODEL_DIRECTORY)
                    || normalized.contains(LEGACY_MODEL_DIRECTORY);
            if (!launcherPath && !modelPath) {
                continue;
            }
            String text;
            try {
                text = Files.readString(file);
            } catch (IOException unreadable) {
                continue;
            }
            if (launcherPath && text.contains("cq:WorkflowLauncher")) {
                Launcher launcher = readLauncher(file, normalized, text);
                if (launcher != null) {
                    launchers.add(launcher);
                }
            }
            if (modelPath && text.contains("cq:WorkflowModel")) {
                models.add(readModel(file, normalized, text));
            }
        }
        return new WorkflowContent(List.copyOf(launchers), List.copyOf(models));
    }

    private Launcher readLauncher(Path file, String normalized, String text) {
        AttributeScan scan = new AttributeScan(text);
        Matcher matcher = ATTRIBUTE.matcher(text);
        String modelPath = null;
        int line = 1;
        while (matcher.find()) {
            if ("cq:workflowModel".equals(matcher.group(1)) || "workflowModel".equals(matcher.group(1))) {
                modelPath = matcher.group(2).strip();
                line = scan.lineOf(matcher.start());
            }
        }
        if (modelPath == null || modelPath.isEmpty()) {
            return null;
        }
        return new Launcher(nodePathOf(normalized), modelPath, file, line);
    }

    private WorkflowModel readModel(Path file, String normalized, String text) {
        AttributeScan scan = new AttributeScan(text);
        Matcher matcher = ATTRIBUTE.matcher(text);
        List<ProcessStep> steps = new ArrayList<>();
        while (matcher.find()) {
            if (!"process".equals(matcher.group(1))) {
                continue;
            }
            String value = matcher.group(2).strip();
            if (value.isEmpty() || value.startsWith("/")) {
                // A submodel reference, not a class.
                continue;
            }
            steps.add(new ProcessStep(value, file, scan.lineOf(matcher.start())));
        }
        String nodePath = nodePathOf(normalized);
        String name = nodePath.substring(nodePath.lastIndexOf('/') + 1);
        return new WorkflowModel(nodePath, name, file, List.copyOf(steps));
    }

    /** {@code conf/global/settings/workflow/launcher/acme/.content.xml} -> {@code conf/.../acme}. */
    private String nodePathOf(String normalized) {
        int index = normalized.indexOf("/jcr_root/");
        String nodePath = index < 0 ? normalized : normalized.substring(index + "/jcr_root/".length());
        if (nodePath.endsWith("/.content.xml")) {
            return nodePath.substring(0, nodePath.length() - "/.content.xml".length());
        }
        if (nodePath.endsWith(".content.xml")) {
            return nodePath.substring(0, nodePath.length() - ".content.xml".length());
        }
        return nodePath;
    }

    /** Maps a character offset to a 1-based line without rescanning the file per attribute. */
    private static final class AttributeScan {

        private final String text;
        private int scannedUpTo;
        private int line = 1;

        AttributeScan(String text) {
            this.text = text;
        }

        int lineOf(int offset) {
            for (int i = scannedUpTo; i < offset; i++) {
                if (text.charAt(i) == '\n') {
                    line++;
                }
            }
            scannedUpTo = Math.max(scannedUpTo, offset);
            return line;
        }
    }
}
