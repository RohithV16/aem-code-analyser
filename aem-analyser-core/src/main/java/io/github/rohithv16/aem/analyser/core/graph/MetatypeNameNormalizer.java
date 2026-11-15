package io.github.rohithv16.aem.analyser.core.graph;

/**
 * Deterministic single-pass OSGi Metatype 1.4 (§105.10) method name normalizer.
 *
 * <p>Conversion rules (applied after stripping a single leading underscore):
 * <ul>
 *   <li>{@code $$} -> {@code $}</li>
 *   <li>{@code $_$} -> {@code -}</li>
 *   <li>{@code __} -> {@code _}</li>
 *   <li>{@code _} -> {@code .}</li>
 * </ul>
 * An explicit {@code @AttributeDefinition(id = "...")} always takes precedence.
 */
public final class MetatypeNameNormalizer {

    private MetatypeNameNormalizer() {
    }

    public static String normalizeMethodName(String methodName, String explicitAttributeId) {
        if (explicitAttributeId != null && !explicitAttributeId.trim().isEmpty()) {
            return explicitAttributeId.trim();
        }
        if (methodName == null || methodName.isEmpty()) {
            return methodName;
        }

        String raw = methodName.startsWith("_") ? methodName.substring(1) : methodName;
        StringBuilder sb = new StringBuilder(raw.length());
        int i = 0;
        int len = raw.length();

        while (i < len) {
            char c = raw.charAt(i);
            if (c == '$' && i + 1 < len && raw.charAt(i + 1) == '$') {
                sb.append('$');
                i += 2;
            } else if (c == '$' && i + 2 < len && raw.charAt(i + 1) == '_' && raw.charAt(i + 2) == '$') {
                sb.append('-');
                i += 3;
            } else if (c == '_' && i + 1 < len && raw.charAt(i + 1) == '_') {
                sb.append('_');
                i += 2;
            } else if (c == '_') {
                sb.append('.');
                i += 1;
            } else {
                sb.append(c);
                i += 1;
            }
        }
        return sb.toString();
    }
}
