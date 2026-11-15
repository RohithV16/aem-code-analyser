package io.github.rohithv16.aem.analyser.core.baseline;

import io.github.rohithv16.aem.analyser.core.model.report.Violation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Stable finding identity used by baselines.
 *
 * <p>Fingerprints deliberately exclude line/column so moving code does not invalidate a baseline.
 * Volatile parts of the message (numbers, quoted names) are normalised so a changed count or PID does
 * not create churn either.
 */
public final class Fingerprint {

    private Fingerprint() {
    }

    public static String of(Violation violation) {
        String normalizedMessage = normalize(violation.message());
        String raw = violation.ruleId() + "|"
                + (violation.subtype() == null ? "" : violation.subtype()) + "|"
                + violation.filePath() + "|"
                + normalizedMessage;
        return sha256(raw).substring(0, 16);
    }

    static String normalize(String message) {
        if (message == null) {
            return "";
        }
        return message
                .replaceAll("\\d+", "#")
                .replaceAll("'[^']*'", "'#'")
                .replaceAll("\"[^\"]*\"", "\"#\"")
                .trim();
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
