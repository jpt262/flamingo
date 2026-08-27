package com.flamingo.edgar;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * R1 raw-first storage. Every network response lands here BEFORE any parsing,
 * keyed {sourceHost}/{sha256(url)}/{fetched_at}. Parsers may only consume what
 * this store returned — no exceptions (build order R1).
 */
public final class RawStore {

    public record Stored(String objectKey, String sha256, byte[] bytes) {}

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final Path root;

    public RawStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    /**
     * Persists bytes immutably under the R1 key layout and returns content +
     * integrity metadata. Overwrites nothing: each fetch instant gets its own
     * leaf, preserving every historical response (R8 spirit at the file layer).
     */
    public Stored put(String url, byte[] bytes, Instant fetchedAt) {
        try {
            String source = hostOf(url);
            String urlHash = sha256Hex(url.getBytes(StandardCharsets.UTF_8));
            String ts = TS.format(fetchedAt);
            // defensive uniqueness within the same second for the same url
            Path dir = root.resolve(source).resolve(urlHash);
            Files.createDirectories(dir);
            Path target = dir.resolve(ts + ".bin");
            int n = 1;
            while (Files.exists(target)) {
                target = dir.resolve(ts + "_" + n + ".bin");
                n++;
            }
            Files.write(target, bytes);
            return new Stored(rel(root, target), sha256Hex(bytes), bytes);
        } catch (IOException e) {
            throw new UncheckedIOException("raw-first persist failed for " + url, e);
        }
    }

    /** Convenience overload using wall-clock now. */
    public Stored put(String url, byte[] bytes) {
        return put(url, bytes, Instant.now());
    }

    public boolean contains(String objectKey) {
        return Files.exists(root.resolve(objectKey));
    }

    public byte[] read(String objectKey) {
        try {
            return Files.readAllBytes(root.resolve(objectKey));
        } catch (IOException e) {
            throw new UncheckedIOException("raw store read failed: " + objectKey, e);
        }
    }

    private static String hostOf(String url) {
        String rest = url.replaceFirst("^https?://", "");
        int slash = rest.indexOf('/');
        String authority = slash > 0 ? rest.substring(0, slash) : rest;
        // strip credentials defensively, keep port out of paths for tidiness
        return authority.replaceAll("[^a-zA-Z0-9._-]", "_").toLowerCase();
    }

    private static String rel(Path root, Path p) {
        return root.relativize(p).toString().replace('\\', '/');
    }

    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder(64);
            for (byte b : md.digest(data)) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
