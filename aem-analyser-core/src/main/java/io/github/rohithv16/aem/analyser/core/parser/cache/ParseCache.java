package io.github.rohithv16.aem.analyser.core.parser.cache;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rohithv16.aem.analyser.core.discovery.HtlScanner;
import io.github.rohithv16.aem.analyser.core.export.VersionInfo;
import io.github.rohithv16.aem.analyser.core.parser.config.OsgiConfigDefinition;
import io.github.rohithv16.aem.analyser.core.parser.java.JavaSourceParseResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Content-hash parse cache: an unchanged file is parsed once per machine, not once per run.
 *
 * <p>Each entry lives at {@code <project>/target/aem-analyser/cache/parse-v1/<sha256>.json} and is
 * keyed by the SHA-256 of the file's bytes plus its normalized path (two identical files in
 * different modules must not evict each other), and validated against the analyser version, the
 * cache schema and {@code --max-file-size}. A hit returns the exact {@code JavaSourceParseResult},
 * HTL rows or OSGi definition the parser would have produced, so <strong>rules still evaluate the
 * whole graph</strong>: unlike {@code --changed-only}, a cache hit never drops a finding.</p>
 *
 * <p>Any unreadable or malformed entry is a miss, never a failure: the cache is an optimisation and
 * the analysis result must not depend on its health. {@code --no-cache} disables reads and
 * writes entirely.</p>
 */
public final class ParseCache {

    /** Bump when the stored shape or the parser semantics change. */
    public static final int SCHEMA = 2;

    static final String KIND_JAVA = "java";
    static final String KIND_HTL = "htl";
    static final String KIND_CONFIG = "config";

    /**
     * Fingerprint of the parser/scanner bytecode, so a locally rebuilt analyser never trusts an
     * entry produced by different parsing logic. A release version bump alone is not enough during
     * development, where every build reports the same SNAPSHOT version.
     */
    private static final String IMPLEMENTATION = implementationFingerprint();

    private final Path directory;
    private final String version;
    private final long maxFileSizeBytes;
    private final boolean enabled;
    private final ObjectMapper mapper;
    private final Set<String> used = ConcurrentHashMap.newKeySet();
    private final AtomicInteger hits = new AtomicInteger();
    private final AtomicInteger misses = new AtomicInteger();

    private ParseCache(Path directory, String version, long maxFileSizeBytes, boolean enabled) {
        this.directory = directory;
        this.version = version;
        this.maxFileSizeBytes = maxFileSizeBytes;
        this.enabled = enabled;
        this.mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /** Default location for a project: {@code target/aem-analyser/cache/parse-v1}. */
    public static ParseCache forProject(Path projectRoot, long maxFileSizeBytes) {
        return new ParseCache(projectRoot.resolve("target/aem-analyser/cache/parse-v1"),
                VersionInfo.current(), maxFileSizeBytes, true);
    }

    public static ParseCache at(Path directory, String version, long maxFileSizeBytes) {
        return new ParseCache(directory, version, maxFileSizeBytes, true);
    }

    /** Reads and writes nothing; every lookup misses and nothing is written or pruned. */
    public static ParseCache disabled() {
        return new ParseCache(null, VersionInfo.current(), 0, false);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Optional<JavaSourceParseResult> getJava(Path file) {
        Optional<ParseCacheCodec.JavaParseDto> dto = readDto(file, KIND_JAVA,
                ParseCacheCodec.JavaParseDto.class);
        return dto.map(value -> ParseCacheCodec.fromDto(value, file.toAbsolutePath().normalize()));
    }

    public void putJava(JavaSourceParseResult result) {
        write(result.filePath(), KIND_JAVA, ParseCacheCodec.toDto(result));
    }

    public Optional<HtlScanner.HtlScanResult> getHtl(Path file) {
        Optional<ParseCacheCodec.HtlDto> dto = readDto(file, KIND_HTL, ParseCacheCodec.HtlDto.class);
        return dto.map(value -> ParseCacheCodec.fromDto(value, file.toAbsolutePath().normalize()));
    }

    public void putHtl(Path file, HtlScanner.HtlScanResult result) {
        write(file, KIND_HTL, ParseCacheCodec.toDto(result));
    }

    public Optional<OsgiConfigDefinition> getConfig(Path file) {
        Optional<ParseCacheCodec.ConfigDto> dto = readDto(file, KIND_CONFIG, ParseCacheCodec.ConfigDto.class);
        return dto.map(value -> ParseCacheCodec.fromDto(value, file.toAbsolutePath().normalize()));
    }

    public void putConfig(OsgiConfigDefinition definition) {
        write(definition.filePath(), KIND_CONFIG, ParseCacheCodec.toDto(definition));
    }

    /** {@code 74 hits / 1 miss}; the console prints this line after the phase table. */
    public String summary() {
        if (!enabled) {
            return "disabled";
        }
        int hitCount = hits.get();
        int missCount = misses.get();
        return hitCount + (hitCount == 1 ? " hit" : " hits") + " / "
                + missCount + (missCount == 1 ? " miss" : " misses");
    }

    public int hits() {
        return hits.get();
    }

    public int misses() {
        return misses.get();
    }

    /**
     * Removes entries this run never consulted, so a deleted or renamed file does not keep its
     * parse result forever. Only meaningful for a full run; a {@code --changed-only} run sees a
     * subset of files and must not delete everything else.
     *
     * @return number of entries removed
     */
    public int prune() {
        if (!enabled || directory == null || !Files.isDirectory(directory)) {
            return 0;
        }
        int removed = 0;
        try (Stream<Path> stream = Files.list(directory)) {
            for (Path entry : stream.toList()) {
                String name = entry.getFileName().toString();
                if (!name.endsWith(".json") || used.contains(name.substring(0, name.length() - 5))) {
                    continue;
                }
                try {
                    if (Files.deleteIfExists(entry)) {
                        removed++;
                    }
                } catch (IOException ignored) {
                    // A locked entry stays for the next run; pruning is best-effort.
                }
            }
        } catch (IOException ignored) {
            return removed;
        }
        return removed;
    }

    // ── Storage ─────────────────────────────────────────────────────────────────

    private <T> Optional<T> readDto(Path file, String kind, Class<T> type) {
        if (!enabled || file == null) {
            return Optional.empty();
        }
        String key = keyOf(file);
        used.add(key);
        Path entry = directory.resolve(key + ".json");
        if (!Files.isRegularFile(entry)) {
            misses.incrementAndGet();
            return Optional.empty();
        }
        try {
            ParseCacheCodec.Entry stored = mapper.readValue(entry.toFile(), ParseCacheCodec.Entry.class);
            if (stored.schema() != SCHEMA
                    || !version.equals(stored.version())
                    || !IMPLEMENTATION.equals(stored.parserFingerprint())
                    || stored.maxFileSizeBytes() != maxFileSizeBytes
                    || !kind.equals(stored.kind())
                    || !file.toAbsolutePath().normalize().toString().equals(stored.filePath())
                    || stored.data() == null) {
                misses.incrementAndGet();
                return Optional.empty();
            }
            T dto = mapper.treeToValue(stored.data(), type);
            hits.incrementAndGet();
            return Optional.of(dto);
        } catch (IOException | RuntimeException e) {
            // Corrupt or half-written entry: treat as a miss and let the parser overwrite it.
            misses.incrementAndGet();
            return Optional.empty();
        }
    }

    private void write(Path file, String kind, Object data) {
        if (!enabled || file == null) {
            return;
        }
        Path normalized = file.toAbsolutePath().normalize();
        String key = keyOf(normalized);
        used.add(key);
        try {
            Files.createDirectories(directory);
            ParseCacheCodec.Entry entry = new ParseCacheCodec.Entry(SCHEMA, version, IMPLEMENTATION,
                    maxFileSizeBytes, normalized.toString(), kind, mapper.valueToTree(data));
            Path target = directory.resolve(key + ".json");
            Path tmp = directory.resolve(key + ".json.tmp");
            mapper.writeValue(tmp.toFile(), entry);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException ignored) {
            // A cache that cannot be written must not fail the analysis.
        }
    }

    private static String implementationFingerprint() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Class<?> type : List.of(
                    io.github.rohithv16.aem.analyser.core.parser.java.JavaSourceParser.class,
                    io.github.rohithv16.aem.analyser.core.parser.config.OsgiConfigParser.class,
                    HtlScanner.class,
                    ParseCacheCodec.class,
                    ParseCache.class)) {
                try (java.io.InputStream in =
                             type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                    if (in != null) {
                        digest.update(in.readAllBytes());
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            return "unknown";
        }
    }

    /**
     * SHA-256 over the file's bytes and its normalized path. Including the path keeps two files
     * with identical content from sharing one entry, which would otherwise hand the second file the
     * first file's paths and line numbers.
     */
    private String keyOf(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Files.readAllBytes(file));
            digest.update((byte) 0);
            digest.update(file.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Cannot hash " + file, e);
        }
    }

}
