package io.github.rohithv16.aem.analyser.core.rules.metadata;

import io.github.rohithv16.aem.analyser.core.model.report.RuleId;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Renders and compares {@code rule-ids.lock}, the committed inventory of rule ids.
 *
 * <p>A lock file is not documentation: it turns an id removal or a status change into a diff a
 * reviewer can see. Removing {@code AEM-DEAD-003C} from {@link RuleId} without editing the lock
 * fails the build with the id named, so dropping a rule has to be a deliberate, reviewed change
 * rather than a side effect of renaming a constant.
 *
 * <p>The format is one pipe-separated row per id ({@code id|status|since|replacedBy}), sorted by id,
 * with {@code #} comments. It is deliberately line-oriented so a code review reads it like a
 * changelog.
 */
public final class RuleIdsLock {

    public static final String FILE_NAME = "rule-ids.lock";
    private static final String HEADER = String.join("\n",
            "# Generated from RuleId. Do not hand-edit: change the enum and run scripts/check-rule-ids.sh.",
            "# id|status|since|replacedBy");

    private RuleIdsLock() {
    }

    /** The exact lock-file text for the current enum, with a trailing newline. */
    public static String render() {
        Map<String, String> rows = rows(RuleId.values());
        StringBuilder text = new StringBuilder(HEADER).append('\n');
        for (String id : new TreeSet<>(rows.keySet())) {
            text.append(rows.get(id)).append('\n');
        }
        return text.toString();
    }

    /**
     * @param rules the ids the analyser currently declares
     * @return id -> lock row ({@code id|status|since|replacedBy})
     */
    public static Map<String, String> rows(RuleId[] rules) {
        Map<String, String> rows = new LinkedHashMap<>();
        for (RuleId rule : rules) {
            rows.put(rule.id(), rule.id() + "|" + rule.status().wireName() + "|" + rule.since() + "|"
                    + rule.replacedBy().orElse(""));
        }
        return rows;
    }

    /** Parse a lock file into id -> full lock row (ignoring blank lines and comments). */
    public static Map<String, String> parse(String lockText) {
        Map<String, String> rows = new LinkedHashMap<>();
        for (String line : lockText.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] parts = trimmed.split("\\|", -1);
            if (parts.length >= 2) {
                rows.put(parts[0], trimmed);
            }
        }
        return rows;
    }

    /**
     * Compares a committed lock with the enum.
     *
     * @return human-readable differences, empty when they agree. Removals are named first because
     *         that is the change that re-opens baselined findings.
     */
    public static List<String> diff(String expectedLock, RuleId[] actualRules) {
        Map<String, String> actual = rows(actualRules);
        Map<String, String> expected = parse(expectedLock);
        List<String> problems = new ArrayList<>();
        for (String id : new TreeSet<>(expected.keySet())) {
            if (!actual.containsKey(id)) {
                problems.add("rule id " + id + " is in " + FILE_NAME + " but no longer declared in RuleId — "
                        + "removal is a two-release event: mark it retired for one release, then delete it "
                        + "and update the lock");
            }
        }
        for (String id : new TreeSet<>(actual.keySet())) {
            if (!expected.containsKey(id)) {
                problems.add("rule id " + id + " is declared in RuleId but missing from " + FILE_NAME);
            } else if (!expected.get(id).equals(actual.get(id))) {
                problems.add("rule id " + id + " changed from '" + expected.get(id) + "' to '"
                        + actual.get(id) + "' — update " + FILE_NAME + " after review");
            }
        }
        return problems;
    }
}
