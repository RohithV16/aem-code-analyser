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
import java.util.zip.ZipEntry;

/**
 * FEAT-PARSE-06: high-speed ASM scanner extracting Declarative Services component PIDs
 * and service interfaces from external dependency JARs (Adobe SDK, community modules).
 */
public final class DependencyBytecodeIndexer {

    private static final String COMPONENT_ANNOTATION = "Lorg/osgi/service/component/annotations/Component;";

    public DependencyIndex index(List<Path> jarFiles) {
        DependencyIndex index = new DependencyIndex();
        for (Path jar : jarFiles) {
            if (!Files.isRegularFile(jar) || !jar.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                continue;
            }
            try (JarFile jarFile = new JarFile(jar.toFile())) {
                var entries = jarFile.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                        continue;
                    }
                    try (InputStream in = jarFile.getInputStream(entry)) {
                        visitClass(in, index);
                    } catch (Exception e) {
                        // malformed class: skip entry
                    }
                }
            } catch (IOException e) {
                // unreadable jar: skip
            }
        }
        return index;
    }

    private void visitClass(InputStream in, DependencyIndex index) throws IOException {
        new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
            private String className;

            @Override
            public void visit(int version, int access, String name, String signature,
                              String superName, String[] interfaces) {
                this.className = name.replace('/', '.');
                for (String iface : interfaces) {
                    index.registerServiceInterface(iface.replace('/', '.'));
                }
            }

            @Override
            public org.objectweb.asm.AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if (COMPONENT_ANNOTATION.equals(descriptor) && className != null) {
                    return new org.objectweb.asm.AnnotationVisitor(Opcodes.ASM9) {
                        private String explicitPid;

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
                    };
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
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
