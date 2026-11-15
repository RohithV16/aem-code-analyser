package io.github.rohithv16.aem.analyser.core.sonar.checks;

import org.sonar.check.Rule;
import org.sonar.java.checks.helpers.ExpressionsHelper;
import org.sonar.plugins.java.api.IssuableSubscriptionVisitor;
import org.sonar.plugins.java.api.tree.CompilationUnitTree;
import org.sonar.plugins.java.api.tree.ExpressionTree;
import org.sonar.plugins.java.api.tree.ImportClauseTree;
import org.sonar.plugins.java.api.tree.ImportTree;
import org.sonar.plugins.java.api.tree.Tree;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Ensures duplicate imports are detected deterministically on the AST
 * corresponding to SonarJava rule S1128, even in offline or partial-classpath environments.
 */
@Rule(key = "S1128")
public class DuplicateImportCheck extends IssuableSubscriptionVisitor {

    @Override
    public List<Tree.Kind> nodesToVisit() {
        return List.of(Tree.Kind.COMPILATION_UNIT);
    }

    @Override
    public void visitNode(Tree tree) {
        if (!(tree instanceof CompilationUnitTree cut)) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (ImportClauseTree clause : cut.imports()) {
            if (clause.is(Tree.Kind.IMPORT) && clause instanceof ImportTree imp) {
                if (imp.qualifiedIdentifier() instanceof ExpressionTree expr) {
                    String name = ExpressionsHelper.concatenate(expr);
                    if (!seen.add(name)) {
                        reportIssue(imp, "Remove this duplicated import.");
                    }
                }
            }
        }
    }
}
