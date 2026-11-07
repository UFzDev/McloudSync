package dev.ufz.mcloudsync.sync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.UUID;

/**
 * Per-world configuration stored inside saves/<WorldName>/.mcloud-sync.json.
 * Guarantees strict opt-in behavior: only worlds with this marker file are managed by MCloudSync.
 */
public class WorldSyncMetadata {
    public static final String METADATA_FILENAME = ".mcloud-sync.json";
    public static final String LOCAL_LOCK_FILENAME = ".mcloud-local.lock";
    public static final String INVITE_PREFIX = "mcloudsync://";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public boolean enabled = true;
    public String worldId = UUID.randomUUID().toString();
    public String worldName = "Shared World";
    public String coordinatorUrl = "http://127.0.0.1:8787";
    public String authToken = "";
    public long lastSyncedRevision = 0L;

    public WorldSyncMetadata() {
    }

    public WorldSyncMetadata(String worldId, String worldName, String coordinatorUrl, String authToken) {
        this.enabled = true;
        this.worldId = worldId;
        this.worldName = worldName;
        this.coordinatorUrl = normalizeUrl(coordinatorUrl);
        this.authToken = authToken != null ? authToken.trim() : "";
        this.lastSyncedRevision = 0L;
    }

    public static String normalizeUrl(String url) {
        if (url == null) return "";
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    public static boolean isSyncedWorld(Path worldDir) {
        if (worldDir == null || !Files.isDirectory(worldDir)) {
            return false;
        }
        Path metaFile = worldDir.resolve(METADATA_FILENAME);
        if (!Files.isRegularFile(metaFile)) {
            return false;
        }
        try {
            WorldSyncMetadata meta = load(worldDir);
            return meta != null && meta.enabled && meta.worldId != null && !meta.worldId.isBlank();
        } catch (Exception e) {
            return false;
        }
    }

    public static WorldSyncMetadata load(Path worldDir) throws IOException {
        Path metaFile = worldDir.resolve(METADATA_FILENAME);
        if (!Files.exists(metaFile)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(metaFile, StandardCharsets.UTF_8)) {
            WorldSyncMetadata meta = GSON.fromJson(reader, WorldSyncMetadata.class);
            if (meta != null && meta.coordinatorUrl != null) {
                meta.coordinatorUrl = normalizeUrl(meta.coordinatorUrl);
            }
            return meta;
        }
    }

    public void save(Path worldDir) throws IOException {
        Files.createDirectories(worldDir);
        Path metaFile = worldDir.resolve(METADATA_FILENAME);
        try (Writer writer = Files.newBufferedWriter(metaFile, StandardCharsets.UTF_8)) {
            GSON.toJson(this, writer);
        }
    }

    /**
     * Generates a compact shareable invite token (mcloudsync://<base64url>) so a friend
     * can bootstrap and link the shared world without manual configuration.
     */
    public String toInviteToken() {
        InvitePayload payload = new InvitePayload(
                this.worldId,
                this.worldName,
                normalizeUrl(this.coordinatorUrl),
                this.authToken
        );
        String json = new Gson().toJson(payload);
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
        return INVITE_PREFIX + encoded;
    }

    /**
     * Parses an invite token back into a WorldSyncMetadata instance.
     */
    public static WorldSyncMetadata fromInviteToken(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Invite token cannot be empty");
        }
        String cleaned = token.trim();
        if (cleaned.startsWith(INVITE_PREFIX)) {
            cleaned = cleaned.substring(INVITE_PREFIX.length());
        }
        byte[] decodedBytes;
        try {
            decodedBytes = Base64.getUrlDecoder().decode(cleaned);
        } catch (IllegalArgumentException e) {
            decodedBytes = Base64.getDecoder().decode(cleaned);
        }
        String json = new String(decodedBytes, StandardCharsets.UTF_8);
        InvitePayload payload = GSON.fromJson(json, InvitePayload.class);
        if (payload == null || payload.worldId == null || payload.worldId.isBlank()
                || payload.coordinatorUrl == null || payload.coordinatorUrl.isBlank()) {
            throw new IllegalArgumentException("Invalid or incomplete invite token payload");
        }
        return new WorldSyncMetadata(
                payload.worldId,
                payload.worldName != null && !payload.worldName.isBlank() ? payload.worldName : "Shared World",
                payload.coordinatorUrl,
                payload.authToken
        );
    }

    private record InvitePayload(String worldId, String worldName, String coordinatorUrl, String authToken) {
    }
}
