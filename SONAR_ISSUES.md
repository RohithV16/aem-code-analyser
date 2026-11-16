# SonarQube Issues

- **Repository:** `RohithV16/aem-code-analyser`
- **Collected:** 2026-11-16
- **Source:** VS Code SonarQube for IDE analysis and workspace Problems view
- **Scope:** Java source and test files in the CLI, core, and Maven-plugin modules

The VS Code Problems view did not expose Sonar rule keys or severity levels, so they are not
inferred here. Compiler/deprecation diagnostics and Maven project-refresh warnings are excluded.
Repeated diagnostics for the same issue are listed once.

## CLI

### `aem-analyser-cli/src/main/java/io/github/rohithv16/aem/analyser/cli/AnalyserCli.java`

- **Line 4:** Remove unused import `AemCodeAnalyser`.
- **Line 206:** Replace `System.out` with a logger.
- **Line 213:** Reduce `execute` cognitive complexity (51; allowed 15).
- **Line 242:** Extract the nested `try` block into a separate method.
- **Line 301:** Prefer `printf` over `String.format`.
- **Line 495:** Prefer `printf` over `String.format`.
- **Line 520:** Extract the repeated `"pom.xml"` literal into a constant.
- **Line 725:** Reduce `checkCoverageStatus` cognitive complexity (20; allowed 15).
- **Line 796:** Remove unused private method `resolveMavenCommand`.
- **Lines 888, 892:** Remove or explain the empty catch blocks.
- **Line 897:** Reduce `exitCode` cognitive complexity (58; allowed 15).
- **Line 904:** Extract the repeated separator string into a constant.
- **Lines 911, 919, 932, 937:** Prefer `printf` over `String.format`.
- **Line 961:** Merge the branch whose body duplicates the preceding branch.

## Core

### `aem-analyser-core/src/main/java/io/github/rohithv16/aem/analyser/core/config/AnalyserConfig.java`

- **Line 7:** Remove unused import `LinkedHashMap`.

### `aem-analyser-core/src/main/java/io/github/rohithv16/aem/analyser/core/discovery/HtlScanner.java`

- **Line 6:** Remove unused import `BufferedReader`.
- **Line 25:** Remove unused private field `SLY_USE_PATTERN`.

### `aem-analyser-core/src/main/java/io/github/rohithv16/aem/analyser/core/discovery/JcrComponentScanner.java`

- **Line 5:** Remove unused import `BufferedReader`.

### `aem-analyser-core/src/main/java/io/github/rohithv16/aem/analyser/core/discovery/ModuleClassifier.java`

- **Line 14:** Remove unused private field `osgiConfigScanner`.
- **Line 16:** Reduce `classify` cognitive complexity (34; allowed 15).
- **Line 76:** Reduce `classifyContentPackage` cognitive complexity (23; allowed 15).

### `aem-analyser-core/src/main/java/io/github/rohithv16/aem/analyser/core/discovery/ProjectDiscoveryEngine.java`

- **Line 22:** Remove unused import `Locale`.
- **Line 60:** Extract the repeated `"pom.xml"` literal into a constant.
- **Line 138:** Remove unused parameter `activeProfiles`.
- **Line 143:** Replace the nested ternary with an independent statement.
- **Line 188:** Reduce `detectBundleSymbolicName` cognitive complexity (29; allowed 15).

### `aem-analyser-core/src/main/java/io/github/rohithv16/aem/analyser/core/graph/ReferenceGraphBuilder.java`

- **Lines 3, 5, 21, 27, 35, 38:** Remove unused imports.
- **Line 4:** Remove the unnecessary same-package import `MetatypeNameNormalizer`.
- **Line 61:** Reduce `build` cognitive complexity (142; allowed 15).
- **Line 128:** Extract the repeated `"Configuration "` literal into a constant.
- **Line 192:** Reduce the number of `break`/`continue` statements in the loop.
- **Line 478:** Remove unused parameter `configPropertyNodes`.

### `aem-analyser-core/src/main/java/io/github/rohithv16/aem/analyser/core/graph/ReferenceGraphIndex.java`

- **Lines 7, 15:** Remove unused imports.

### `aem-analyser-core/src/main/java/io/github/rohithv16/aem/analyser/core/model/graph/node/OSGiOcdNode.java`

- **Line 7:** Remove unused import `EnumSet`.
- **Line 16:** Override `equals` in this class.

### `aem-analyser-core/src/main/java/io/github/rohithv16/aem/analyser/core/parser/bytecode/DependencyBytecodeIndexer.java`

- **Line 16:** Remove unused import `ZipEntry`.
- **Line 30:** Reduce `index` cognitive complexity (22; allowed 15).
- **Line 44:** Extract the nested `try` block into a separate method.
- **Line 64:** Reduce `visitClass` cognitive complexity (19; allowed 15).

### `aem-analyser-core/src/main/java/io/github/rohithv16/aem/analyser/core/parser/config/OsgiConfigDefinition.java`

- **Line 4:** Remove unused import `DataType`.

### `aem-analyser-core/src/main/java/io/github/rohithv16/aem/analyser/core/parser/config/OsgiConfigParser.java`

- **Line 6:** Remove unused import `Files`.

### `aem-analyser-core/src/main/java/io/github/rohithv16/aem/analyser/core/parser/java/JavaSourceParser.java`

- **Line 19:** Remove unused import `AnnotationExpr`.
- **Line 86:** Reduce `parseInternal` cognitive complexity (33; allowed 15).
- **Lines 133, 176:** Reduce the number of `break`/`continue` statements in each loop.
- **Lines 248, 250:** Replace the nested ternary expression with independent statements.

### `aem-analyser-core/src/main/java/io/github/rohithv16/aem/analyser/core/parser/java/visitor/MetatypeOcdVisitor.java`

- **Line 9:** Remove unused import `MethodDeclaration`.
- **Line 71:** Reduce the parameter count of `extractAttributeDefinition` (8; allowed 7).

### `aem-analyser-core/src/main/java/io/github/rohithv16/aem/analyser/core/parser/java/visitor/SubserviceCallExtractor.java`

- **Lines 10, 11:** Remove unused imports `NameExpr` and `StringLiteralExpr`.
- **Line 31:** Reduce `extract` cognitive complexity (32; allowed 15).
- **Line 40:** Rename variable `var`, which matches a restricted identifier.
- **Line 57:** Remove the useless initial assignment to `resolution`.
- **Line 89:** Reduce `resolveFromMapExpression` cognitive complexity (30; allowed 15).
- **Line 124:** Reduce `extractStringFromMapCall` cognitive complexity (32; allowed 15).

### `aem-analyser-core/src/main/java/io/github/rohithv16/aem/analyser/core/rules/impl/AemDead001Rule.java`

- **Lines 4, 5, 16, 24:** Remove unused imports.
- **Line 42:** Reduce `evaluate` cognitive complexity (23; allowed 15).
- **Line 50:** Reduce the number of `break`/`continue` statements in the loop.

### `aem-analyser-core/src/main/java/io/github/rohithv16/aem/analyser/core/rules/impl/AemDead002BRule.java`

- **Line 16:** Remove unused import `Set`.
- **Line 31:** Reduce `evaluate` cognitive complexity (16; allowed 15).

### `aem-analyser-core/src/main/java/io/github/rohithv16/aem/analyser/core/rules/impl/AemDup001Rule.java`

- **Lines 18, 19:** Remove unused imports `Objects` and `Set`.
- **Line 38:** Reduce `evaluate` cognitive complexity (24; allowed 15).
- **Line 169:** Remove or explain the empty catch block.
- **Lines 173–175:** Prefer `List.sort()` over `Collections.sort()`.
- **Line 183:** Group the regex alternatives to make operator precedence explicit.
- **Line 185:** Prefer `Stream.toList()` over `collect(Collectors.toList())`.

### `aem-analyser-core/src/main/java/io/github/rohithv16/aem/analyser/core/rules/catalogs/PlatformPidCatalog.java`

- **Lines 4, 5:** Remove unused imports `Locale` and `Set`.

### `aem-analyser-core/src/main/java/io/github/rohithv16/aem/analyser/core/sonar/SonarBridgeComponents.java`

- **Line 64:** Reduce `createNewIssueProxy` cognitive complexity (81; allowed 15).
- **Line 96:** Extract the repeated `"java:"` literal into a constant.
- **Lines 104, 231, 272:** Remove or explain the empty catch blocks.
- **Line 209:** Reduce `reportIssue` cognitive complexity (18; allowed 15).
- **Line 221:** Replace the nested ternary with an independent statement.

## Tests

### `aem-analyser-core/src/test/java/io/github/rohithv16/aem/analyser/core/discovery/ModuleClassifierTest.java`

- **Lines 7, 8:** Remove unused imports `List` and `Set`.

### `aem-analyser-core/src/test/java/io/github/rohithv16/aem/analyser/core/rules/DuplicateRulesTest.java`

- **Lines 11, 12:** Remove unused imports `ModuleDescriptor` and `CoverageReport`.

### `aem-analyser-core/src/test/java/io/github/rohithv16/aem/analyser/core/git/GitDiffResolverTest.java`

- **Line 6:** Remove unused import `IOException`.

### `aem-analyser-maven-plugin/src/test/java/io/github/rohithv16/aem/analyser/maven/MojoParameterTest.java`

- **Line 50:** Remove the unused local variable `ignored` and its useless assignment.
