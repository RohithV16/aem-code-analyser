package io.github.rohithv16.aem.analyser.core.baseline;

import io.github.rohithv16.aem.analyser.core.model.report.Violation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Stable finding identity used by baselines, SARIF {@code partialFingerprints} and the GitLab
 * Code Climate exporter.
 *
 * <p>The fingerprint deliberately excludes line and column so moving code does not invalidate a
 * baseline, but it must keep everything that identifies <em>which</em> finding it is. The message
 * is therefore hashed verbatim: earlier versions normalised every digit and quoted string to
 * {@code #}, which collapsed all occurrences of a rule in one file to a single fingerprint (for
 * example every broken HTL reference became {@code '#'}). A baseline then grandfathered a brand
 * new broken reference because it looked identical to an accepted one. Repeated findings with the
 * same message are handled as a multiset by {@link BaselineFilter}, so verbatim messages do not
 * let one entry suppress unlimited duplicates.
 */
public final class Fingerprint {

    private Fingerprint() {
    }

    public static String of(Violation violation) {
        String raw = violation.ruleId() + "|"
                + (violation.subtype() == null ? "" : violation.subtype()) + "|"
                + normalizePath(violation.filePath()) + "|"
                + (violation.message() == null ? "" : violation.message());
        return sha256(raw).substring(0, 16);
    }

    /**
     * Normalises only path syntax, never finding identity: backslashes become '/' and a leading
     * {@code ./} is dropped, so the same file reached through different roots hashes the same.
     */
    static String normalizePath(String path) {
        if (path == null) {
            return "";
        }
        String normalized = path.replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
