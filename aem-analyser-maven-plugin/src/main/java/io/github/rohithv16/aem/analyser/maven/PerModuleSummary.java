package io.github.rohithv16.aem.analyser.maven;

import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Issue #44: the per-module console table and the bucketing the per-module writers use.
 *
 * <p>Rendering is a pure function of the buckets so the table can be asserted in a unit test without
 * a Maven harness, and so the same rows feed the log and (if ever needed) a machine-readable summary.
 */
final class PerModuleSummary {

    /** One table row: a module and its findings by severity. */
    record Row(String module, long errors, long warnings, long infos) {

        long total() {
            return errors + warnings + infos;
        }

        static Row of(String module, List<Violation> violations) {
            long errors = 0;
            long warnings = 0;
            long infos = 0;
            for (Violation violation : violations) {
                Severity severity = violation.severity();
                if (severity == Severity.ERROR) {
                    errors++;
                } else if (severity == Severity.WARNING) {
                    warnings++;
                } else {
                    infos++;
                }
            }
            return new Row(module, errors, warnings, infos);
        }
    }

    private static final String HEADER_MODULE = "MODULE";

    private PerModuleSummary() {
    }

    /** Buckets violations by owning module, ordering rows alphabetically with {@code _reactor} last. */
    static Map<String, List<Violation>> bucket(ModuleAttributor attributor, List<Violation> violations) {
        Map<String, List<Violation>> buckets = new LinkedHashMap<>();
        for (Violation violation : violations == null ? List.<Violation>of() : violations) {
            buckets.computeIfAbsent(attributor.moduleFor(violation.filePath()),
                    key -> new ArrayList<>()).add(violation);
        }
        return buckets;
    }

    /** Turns buckets into rows: alphabetical, with the {@code _reactor} bucket always last. */
    static List<Row> rows(Map<String, List<Violation>> buckets) {
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<String, List<Violation>> entry : buckets.entrySet()) {
            rows.add(Row.of(entry.getKey(), entry.getValue()));
        }
        rows.sort(Comparator
                .comparing((Row row) -> ModuleAttributor.REACTOR_BUCKET.equals(row.module()))
                .thenComparing(Row::module));
        return rows;
    }

    /**
     * Renders the {@code module · errors · warnings} table, one line per entry, without a trailing
     * newline. Column widths follow the widest module name so the output stays aligned.
     */
    static List<String> render(List<Row> rows) {
        List<String> lines = new ArrayList<>();
        if (rows == null || rows.isEmpty()) {
            lines.add("AEM Code Analyser: per-module reports requested, but the run produced no findings.");
            return lines;
        }
        int moduleWidth = HEADER_MODULE.length();
        for (Row row : rows) {
            moduleWidth = Math.max(moduleWidth, row.module().length());
        }
        long errors = 0;
        long warnings = 0;
        long infos = 0;
        for (Row row : rows) {
            errors += row.errors();
            warnings += row.warnings();
            infos += row.infos();
        }
        lines.add(String.format(Locale.ROOT, "AEM Code Analyser: per-module findings (%d module(s), %d finding(s))",
                rows.size(), errors + warnings + infos));
        lines.add(header(moduleWidth));
        for (Row row : rows) {
            lines.add(String.format(Locale.ROOT, "  %-" + moduleWidth + "s  %6d  %8d  %5d  %5d",
                    row.module(), row.errors(), row.warnings(), row.infos(), row.total()));
        }
        lines.add(String.format(Locale.ROOT, "  %-" + moduleWidth + "s  %6d  %8d  %5d  %5d",
                "TOTAL", errors, warnings, infos, errors + warnings + infos));
        return lines;
    }

    private static String header(int moduleWidth) {
        return String.format(Locale.ROOT, "  %-" + moduleWidth + "s  %6s  %8s  %5s  %5s",
                HEADER_MODULE, "ERRORS", "WARNINGS", "INFOS", "TOTAL");
    }
}
