package dev.ufz.mcloudsync.sync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Computes and compares SHA-256 file manifests for a Minecraft world directory.
 * Enables fast Delta Synchronization (uploading/downloading only modified .mca/.dat files)
 * and completely bypasses Cloudflare's 100 MB HTTP 413 payload limit.
 */
public class DeltaManifest {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Set<String> EXCLUDED_EXACT_NAMES = Set.of(
            "session.lock",
            WorldSyncMetadata.METADATA_FILENAME,
            WorldSyncMetadata.LOCAL_LOCK_FILENAME,
            ".DS_Store",
            "Thumbs.db"
    );

    public long revision = 0L;
    public Map<String, FileEntry> files = new LinkedHashMap<>();

    public DeltaManifest() {
    }

    public DeltaManifest(long revision, Map<String, FileEntry> files) {
        this.revision = revision;
        this.files = new LinkedHashMap<>(files);
    }

    public record FileEntry(String sha256, long size) {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof FileEntry that)) return false;
            return size == that.size && Objects.equals(sha256, that.sha256);
        }

        @Override
        public int hashCode() {
            return Objects.hash(sha256, size);
        }
    }

    public record DiffResult(
            List<String> toTransfer, // Files added or modified compared to target
            List<String> toDelete    // Files present in target that no longer exist in source
    ) {
        public boolean isEmpty() {
            return toTransfer.isEmpty() && toDelete.isEmpty();
        }
    }

    /**
     * Scans a world directory and builds a deterministic, sorted DeltaManifest.
     */
    public static DeltaManifest buildFromDirectory(Path worldDir, long revision) throws IOException {
        if (!Files.isDirectory(worldDir)) {
            return new DeltaManifest(revision, Collections.emptyMap());
        }

        Map<String, FileEntry> sortedEntries = new TreeMap<>();

        Files.walkFileTree(worldDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(worldDir)) {
                    String folderName = dir.getFileName().toString();
                    if (folderName.startsWith(".git") || folderName.equals("cache") || folderName.equals("backups")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!attrs.isRegularFile()) {
                    return FileVisitResult.CONTINUE;
                }
                Path relPath = worldDir.relativize(file);
                String normalizedPath = normalizeRelativePath(relPath);
                if (shouldExclude(normalizedPath, file.getFileName().toString())) {
                    return FileVisitResult.CONTINUE;
                }
                String sha256 = computeSha256(file);
                sortedEntries.put(normalizedPath, new FileEntry(sha256, attrs.size()));
                return FileVisitResult.CONTINUE;
            }
        });

        return new DeltaManifest(revision, sortedEntries);
    }

    /**
     * Computes what needs to be transferred or deleted to make `target` identical to `source`.
     * - For PUSH (upload): source = localManifest, target = remoteManifest
     * - For PULL (download): source = remoteManifest, target = localManifest
     */
    public static DiffResult diff(DeltaManifest source, DeltaManifest target) {
        Map<String, FileEntry> srcFiles = (source != null && source.files != null) ? source.files : Collections.emptyMap();
        Map<String, FileEntry> tgtFiles = (target != null && target.files != null) ? target.files : Collections.emptyMap();

        List<String> toTransfer = new ArrayList<>();
        List<String> toDelete = new ArrayList<>();

        for (Map.Entry<String, FileEntry> entry : srcFiles.entrySet()) {
            String path = entry.getKey();
            FileEntry srcFile = entry.getValue();
            FileEntry tgtFile = tgtFiles.get(path);
            if (tgtFile == null || !srcFile.equals(tgtFile)) {
                toTransfer.add(path);
            }
        }

        for (String path : tgtFiles.keySet()) {
            if (!srcFiles.containsKey(path)) {
                toDelete.add(path);
            }
        }

        Collections.sort(toTransfer);
        Collections.sort(toDelete);
        return new DiffResult(toTransfer, toDelete);
    }

    public static boolean shouldExclude(String relativePath, String fileName) {
        if (EXCLUDED_EXACT_NAMES.contains(fileName)) {
            return true;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".tmp") || lower.endsWith(".bak") || lower.endsWith(".old")
                || lower.endsWith("_old") || lower.endsWith(".lock")) {
            // Note: level.dat_old is excluded to save bandwidth; level.dat is authoritative
            return true;
        }
        return relativePath.contains("..") || relativePath.startsWith("/");
    }

    public static String normalizeRelativePath(Path relativePath) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < relativePath.getNameCount(); i++) {
            if (i > 0) sb.append('/');
            sb.append(relativePath.getName(i).toString());
        }
        return sb.toString();
    }

    public static String computeSha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            try (InputStream in = Files.newInputStream(file)) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public static String computeSha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    public static DeltaManifest fromJson(String json) {
        if (json == null || json.isBlank()) {
            return new DeltaManifest(0L, Collections.emptyMap());
        }
        DeltaManifest parsed = GSON.fromJson(json, DeltaManifest.class);
        if (parsed == null) {
            return new DeltaManifest(0L, Collections.emptyMap());
        }
        if (parsed.files == null) {
            parsed.files = new LinkedHashMap<>();
        }
        return parsed;
    }
}
