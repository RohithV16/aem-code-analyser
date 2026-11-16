package io.github.rohithv16.aem.analyser.core.parser.bytecode;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * FEAT-PARSE-06: high-speed ASM scanner extracting Declarative Services component PIDs
 * and service interfaces from external dependency JARs (Adobe SDK, community modules).
 */
public final class DependencyBytecodeIndexer {

    private static final String COMPONENT_ANNOTATION = "Lorg/osgi/service/component/annotations/Component;";

    public DependencyIndex index(List<Path> jarFiles) {
        return index(jarFiles, new io.github.rohithv16.aem.analyser.core.util.DiagnosticSink());
    }

    public DependencyIndex index(List<Path> jarFiles,
                                 io.github.rohithv16.aem.analyser.core.util.DiagnosticSink diagnostics) {
        DependencyIndex index = new DependencyIndex();
        for (Path jar : jarFiles) {
            if (isJarArchive(jar)) {
                indexJar(jar, index, diagnostics);
            }
        }
        return index;
    }

    private static boolean isJarArchive(Path jar) {
        return Files.isRegularFile(jar)
                && jar.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar");
    }

    /** Indexes every class entry of one archive; an unreadable archive is reported, never fatal. */
    private void indexJar(Path jar, DependencyIndex index,
                          io.github.rohithv16.aem.analyser.core.util.DiagnosticSink diagnostics) {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            var entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!entry.isDirectory() && entry.getName().endsWith(".class")) {
                    indexEntry(jarFile, jar, entry, index, diagnostics);
                }
            }
        } catch (IOException e) {
            report(diagnostics, io.github.rohithv16.aem.analyser.core.model.report.DiagnosticKind.DEPENDENCY_INDEX,
                    jar, io.github.rohithv16.aem.analyser.core.util.DiagnosticSink.reasonOf(e));
        }
    }

    private void indexEntry(JarFile jarFile, Path jar, JarEntry entry, DependencyIndex index,
                            io.github.rohithv16.aem.analyser.core.util.DiagnosticSink diagnostics) {
        try (InputStream in = jarFile.getInputStream(entry)) {
            visitClass(in, index);
        } catch (Exception e) {
            report(diagnostics,
                    io.github.rohithv16.aem.analyser.core.model.report.DiagnosticKind.MALFORMED_CLASS,
                    jar, entry.getName() + ": "
                            + io.github.rohithv16.aem.analyser.core.util.DiagnosticSink.reasonOf(e));
        }
    }

    private static void report(io.github.rohithv16.aem.analyser.core.util.DiagnosticSink diagnostics,
                               io.github.rohithv16.aem.analyser.core.model.report.DiagnosticKind kind,
                               Path jar, String detail) {
        if (diagnostics != null) {
            diagnostics.add(kind, jar, detail);
        }
    }

    private void visitClass(InputStream in, DependencyIndex index) throws IOException {
        new ClassReader(in).accept(new ComponentClassVisitor(index),
                ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }

    /** Collects a class's interfaces and its Declarative Services component PID. */
    private static final class ComponentClassVisitor extends ClassVisitor {

        private final DependencyIndex index;
        private String className;

        ComponentClassVisitor(DependencyIndex index) {
            super(Opcodes.ASM9);
            this.index = index;
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                          String superName, String[] interfaces) {
            className = name.replace('/', '.');
            for (String iface : interfaces) {
                index.registerServiceInterface(iface.replace('/', '.'));
            }
        }

        @Override
        public org.objectweb.asm.AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            if (!COMPONENT_ANNOTATION.equals(descriptor) || className == null) {
                return null;
            }
            return new ComponentAnnotationVisitor(index, className);
        }
    }

    /** Reads {@code configurationPid} / {@code configurationPolicy} from one {@code @Component}. */
    private static final class ComponentAnnotationVisitor
            extends org.objectweb.asm.AnnotationVisitor {

        private final DependencyIndex index;
        private final String className;
        private String explicitPid;

        ComponentAnnotationVisitor(DependencyIndex index, String className) {
            super(Opcodes.ASM9);
            this.index = index;
            this.className = className;
        }

        @Override
        public void visit(String name, Object value) {
            if ("configurationPid".equals(name) && value instanceof String pid) {
                explicitPid = pid;
            }
            if ("configurationPolicy".equals(name)
                    && value instanceof org.objectweb.asm.Type type
                    && type.getClassName().endsWith("IGNORE")) {
                index.registerIgnoredPolicy(className);
            }
        }

        @Override
        public void visitEnd() {
            index.registerComponentPid(explicitPid != null ? explicitPid : className);
        }
    }

    /**
     * Mutable accumulation buffer for a single indexing run.
     */
    public static final class DependencyIndex {

        private final Set<String> componentPids = new java.util.HashSet<>();
        private final Set<String> serviceInterfaces = new java.util.HashSet<>();
        private final Set<String> ignoredPolicyClasses = new java.util.HashSet<>();

        void registerComponentPid(String pid) {
            if (pid != null) {
                componentPids.add(pid);
            }
        }

        void registerServiceInterface(String fqn) {
            if (fqn != null) {
                serviceInterfaces.add(fqn);
            }
        }

        void registerIgnoredPolicy(String className) {
            if (className != null) {
                ignoredPolicyClasses.add(className);
            }
        }

        public Set<String> getComponentPids() {
            return Set.copyOf(componentPids);
        }

        public Set<String> getServiceInterfaces() {
            return Set.copyOf(serviceInterfaces);
        }

        public Set<String> getIgnoredPolicyClasses() {
            return Set.copyOf(ignoredPolicyClasses);
        }
    }
}
