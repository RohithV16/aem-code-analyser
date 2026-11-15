package io.github.rohithv16.aem.analyser.core.model.graph.node;

import io.github.rohithv16.aem.analyser.core.model.graph.NodeType;
import io.github.rohithv16.aem.analyser.core.model.graph.SourceLocation;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * A Java type declaration discovered by AST parsing.
 */
public class JavaClassNode extends AbstractPolyglotNode {

    public enum Kind { CLASS, INTERFACE, ANNOTATION, ENUM }

    private final String fqn;
    private final Kind kind;
    private final Set<ModifierKind> modifiers;
    private final String superClass;
    private final Set<String> interfaces;
    private boolean exported;

    public JavaClassNode(String fqn,
                         Kind kind,
                         Set<ModifierKind> modifiers,
                         String superClass,
                         Set<String> interfaces,
                         Path filePath,
                         SourceLocation sourceLocation) {
        super(fqn, filePath, sourceLocation);
        this.fqn = fqn;
        this.kind = kind;
        this.modifiers = modifiers.isEmpty() ? EnumSet.noneOf(ModifierKind.class) : EnumSet.copyOf(modifiers);
        this.superClass = superClass;
        this.interfaces = new LinkedHashSet<>(interfaces);
    }

    public enum ModifierKind { PUBLIC, ABSTRACT, FINAL }

    @Override
    public NodeType getNodeType() {
        return NodeType.JAVA_CLASS;
    }

    public String getFqn() {
        return fqn;
    }

    public Kind getKind() {
        return kind;
    }

    public Set<ModifierKind> getModifiers() {
        return EnumSet.copyOf(modifiers);
    }

    public Optional<String> getSuperClass() {
        return Optional.ofNullable(superClass);
    }

    public Set<String> getInterfaces() {
        return interfaces;
    }

    public String getPackageName() {
        int idx = fqn.lastIndexOf('.');
        return idx > 0 ? fqn.substring(0, idx) : "";
    }

    public boolean isExported() {
        return exported;
    }

    public void setExported(boolean exported) {
        this.exported = exported;
    }
}
