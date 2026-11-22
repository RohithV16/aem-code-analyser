package io.github.rohithv16.aem.analyser.core.discovery;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Streaming view of the HTML/HTL markup that rules need to judge an element, without a DOM.
 *
 * <p>The analyser already has an HTL expression scanner, but accessibility is a property of the
 * <em>element</em>: an {@code <img>} with no {@code alt}, or an anchor whose only child is an icon.
 * This scanner returns one {@link MarkupTag} per start tag with its attributes, the text content of
 * the element, its inner markup and the source position, so a rule can decide from facts instead of
 * line-level regular expressions.</p>
 *
 * <p>Content inside HTML comments, {@code <script>} and {@code <style>} is masked before tags are
 * read. That is not cosmetic: a client-side template builds anchor markup inside a JavaScript
 * string ({@code html += '<a href="' + url + '">'}), and scanning it as markup reports a link that
 * never exists in the DOM.</p>
 */
public final class HtlMarkupScanner {

    /** Elements that never have a closing tag, so their content is always empty. */
    private static final Set<String> VOID_ELEMENTS = Set.of(
            "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta",
            "param", "source", "track", "wbr");

    private static final long MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024;

    private static final Pattern COMMENT = Pattern.compile("<!--.*?(?:-->|$)", Pattern.DOTALL);
    private static final Pattern SCRIPT = Pattern.compile(
            "<script\\b[^>]*>.*?(?:</script\\s*>|$)", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern STYLE = Pattern.compile(
            "<style\\b[^>]*>.*?(?:</style\\s*>|$)", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    /** {@code name}, {@code name="value"}, {@code name='value'} or an unquoted value. */
    private static final Pattern ATTRIBUTE = Pattern.compile(
            "([^\\s\"'<>/=]+)(?:\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s\"'=<>`]+))?");
    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    private static final Pattern INNER_IMG_WITH_ALT = Pattern.compile(
            "<img\\b[^>]*\\balt\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')",
            Pattern.CASE_INSENSITIVE);

    /**
     * One start tag. {@code innerHtml} is {@code null} when the element has no closing tag in the
     * file (an unclosed anchor), which rules must treat as "cannot tell" rather than empty.
     */
    public record MarkupTag(Path filePath, String name, Map<String, String> attributes,
                            String innerHtml, String textContent, int line, int column) {

        public boolean hasAttribute(String attributeName) {
            return attributes.containsKey(attributeName.toLowerCase(Locale.ROOT));
        }

        public String attribute(String attributeName) {
            return attributes.get(attributeName.toLowerCase(Locale.ROOT));
        }

        public boolean hasNonBlankAttribute(String attributeName) {
            String value = attribute(attributeName);
            return value != null && !value.strip().isEmpty();
        }

        public boolean is(String elementName) {
            return name.equalsIgnoreCase(elementName);
        }
    }

    /** Scans every template in a shared inventory; oversized or unreadable files are skipped. */
    public List<MarkupTag> scanTemplates(FileInventory inventory) {
        List<MarkupTag> tags = new ArrayList<>();
        if (inventory == null) {
            return tags;
        }
        for (Path template : inventory.templates()) {
            tags.addAll(scanFile(template));
        }
        return tags;
    }

    /** Scans one file; a file that cannot be read or is too large yields no tags. */
    public List<MarkupTag> scanFile(Path file) {
        try {
            if (file == null || !Files.isRegularFile(file) || Files.size(file) > MAX_FILE_SIZE_BYTES) {
                return List.of();
            }
            return scan(Files.readString(file), file);
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * Scans markup already in memory.
     *
     * @param filePath the path recorded on each tag; may be {@code null} in tests
     */
    public List<MarkupTag> scan(String content, Path filePath) {
        if (content == null || content.isEmpty()) {
            return List.of();
        }
        char[] masked = maskNonMarkup(content);
        int[] lineStarts = lineStarts(masked);
        List<MarkupTag> tags = new ArrayList<>();
        int index = 0;
        while (index < masked.length) {
            int open = indexOf(masked, '<', index);
            if (open < 0) {
                break;
            }
            int nameStart = open + 1;
            if (nameStart >= masked.length || !Character.isLetter(masked[nameStart])) {
                index = nameStart;
                continue;
            }
            int close = tagEnd(masked, nameStart);
            if (close < 0) {
                break;
            }
            TagHeader header = parseHeader(content, masked, nameStart, close);
            if (header != null) {
                String inner = null;
                String text = "";
                if (!VOID_ELEMENTS.contains(header.name())) {
                    int innerStart = close + 1;
                    int innerEnd = closingTag(masked, innerStart, header.name());
                    if (innerEnd >= 0) {
                        inner = content.substring(innerStart, innerEnd);
                        text = stripMarkup(inner);
                    }
                }
                int offset = open;
                tags.add(new MarkupTag(filePath, header.name(), header.attributes(), inner, text,
                        lineOf(lineStarts, offset), columnOf(lineStarts, offset)));
            }
            index = close + 1;
        }
        return tags;
    }

    private record TagHeader(String name, Map<String, String> attributes) {
    }

    private static TagHeader parseHeader(String content, char[] masked, int nameStart, int close) {
        int nameEnd = nameStart;
        while (nameEnd < close && isNameChar(masked[nameEnd])) {
            nameEnd++;
        }
        if (nameEnd == nameStart) {
            return null;
        }
        String name = content.substring(nameStart, nameEnd).toLowerCase(Locale.ROOT);
        int attributesEnd = close;
        // A self-closing slash is not an attribute.
        int cursor = close - 1;
        while (cursor >= nameEnd && Character.isWhitespace(masked[cursor])) {
            cursor--;
        }
        if (cursor >= nameEnd && masked[cursor] == '/') {
            attributesEnd = cursor;
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        Matcher attribute = ATTRIBUTE.matcher(content.substring(nameEnd, attributesEnd));
        while (attribute.find()) {
            String attributeName = attribute.group(1).toLowerCase(Locale.ROOT);
            if (attributeName.isEmpty() || "/".equals(attributeName)) {
                continue;
            }
            String rawValue = attribute.group(2);
            String value = rawValue == null ? "" : unquote(rawValue);
            // The first occurrence wins, matching how a browser treats a duplicated attribute.
            attributes.putIfAbsent(attributeName, value);
        }
        return new TagHeader(name, attributes);
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == ':' || c == '_';
    }

    private static String unquote(String raw) {
        if (raw.length() >= 2) {
            char first = raw.charAt(0);
            char last = raw.charAt(raw.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return raw.substring(1, raw.length() - 1);
            }
        }
        return raw;
    }

    private static int tagEnd(char[] masked, int from) {
        char quote = 0;
        for (int i = from; i < masked.length; i++) {
            char c = masked[i];
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '>') {
                return i;
            }
        }
        return -1;
    }

    private static int closingTag(char[] masked, int from, String name) {
        for (int i = from; i + 1 < masked.length; i++) {
            if (masked[i] != '<' || masked[i + 1] != '/') {
                continue;
            }
            int start = i + 2;
            int end = start;
            while (end < masked.length && isNameChar(masked[end])) {
                end++;
            }
            if (end > start && name.equalsIgnoreCase(new String(masked, start, end - start))) {
                return i;
            }
        }
        return -1;
    }

    private static String stripMarkup(String inner) {
        String withoutTags = TAG.matcher(inner).replaceAll(" ");
        return withoutTags
                .replace("&nbsp;", " ")
                .replace("&#160;", " ")
                .strip();
    }

    /**
     * Blanks comments, scripts and styles while preserving offsets and line breaks, so tag
     * positions still map to the original file.
     */
    private static char[] maskNonMarkup(String content) {
        char[] masked = content.toCharArray();
        mask(masked, COMMENT.matcher(content));
        mask(masked, SCRIPT.matcher(content));
        mask(masked, STYLE.matcher(content));
        return masked;
    }

    private static void mask(char[] masked, Matcher matcher) {
        while (matcher.find()) {
            for (int i = matcher.start(); i < matcher.end(); i++) {
                if (masked[i] != '\n' && masked[i] != '\r') {
                    masked[i] = ' ';
                }
            }
        }
    }

    private static int indexOf(char[] chars, char needle, int from) {
        for (int i = from; i < chars.length; i++) {
            if (chars[i] == needle) {
                return i;
            }
        }
        return -1;
    }

    private static int[] lineStarts(char[] chars) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < chars.length; i++) {
            if (chars[i] == '\n') {
                starts.add(i + 1);
            }
        }
        return starts.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int lineOf(int[] lineStarts, int offset) {
        int low = 0;
        int high = lineStarts.length - 1;
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (lineStarts[middle] <= offset) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return low + 1;
    }

    private static int columnOf(int[] lineStarts, int offset) {
        return offset - lineStarts[lineOf(lineStarts, offset) - 1] + 1;
    }

    /** True when an {@code <img>} inside {@code innerHtml} carries a non-blank alt. */
    public static boolean hasImageWithAlt(String innerHtml) {
        if (innerHtml == null) {
            return false;
        }
        Matcher matcher = INNER_IMG_WITH_ALT.matcher(innerHtml);
        while (matcher.find()) {
            String value = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            if (value != null && !value.strip().isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
