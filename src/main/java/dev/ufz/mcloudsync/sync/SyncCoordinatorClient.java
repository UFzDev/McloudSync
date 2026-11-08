package dev.ufz.mcloudsync.sync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * HTTP/REST Client for communicating with the lightweight MCloudSync Coordinator Server.
 * Uses only Java 21 standard library (`java.net.http.HttpClient`) for zero external bloat.
 */
public class SyncCoordinatorClient {
    private static final Gson GSON = new GsonBuilder().create();
    private static final HttpClient SHARED_HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final String baseUrl;
    private final String authToken;
    private final HttpClient httpClient;

    public SyncCoordinatorClient(String baseUrl, String authToken) {
        this.baseUrl = WorldSyncMetadata.normalizeUrl(baseUrl);
        this.authToken = authToken != null ? authToken.trim() : "";
        this.httpClient = SHARED_HTTP_CLIENT;
    }

    public enum LockState {
        FREE,
        LOCKED,
        STALE
    }

    public static class WorldStatusResponse {
        public String worldId;
        public LockState state = LockState.FREE;
        public String holderUuid;
        public String holderName;
        public long acquiredAt;
        public long lastHeartbeatAt;
        public long revision;
        public String lanTunnelUrl;
        public List<JoinRequestInfo> joinRequests = new ArrayList<>();
    }

    public static class JoinRequestInfo {
        public String requesterUuid;
        public String requesterName;
        public long requestedAt;

        public JoinRequestInfo() {
        }

        public JoinRequestInfo(String requesterUuid, String requesterName, long requestedAt) {
            this.requesterUuid = requesterUuid;
            this.requesterName = requesterName;
            this.requestedAt = requestedAt;
        }
    }

    public record LockAcquireResult(boolean acquired, WorldStatusResponse status, String message) {
    }

    private HttpRequest.Builder requestBuilder(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(this.baseUrl + path))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "MCloudSync/1.1.1");
        if (!this.authToken.isEmpty()) {
            builder.header("Authorization", "Bearer " + this.authToken);
        }
        return builder;
    }

    public boolean checkHealth() {
        try {
            HttpRequest req = requestBuilder("/api/v1/health")
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    public WorldStatusResponse getWorldStatus(String worldId) throws IOException, InterruptedException {
        String path = "/api/v1/worlds/" + encodeSegment(worldId) + "/status";
        HttpRequest req = requestBuilder(path)
                .timeout(Duration.ofSeconds(8))
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new IOException("GET " + path + " failed with HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return GSON.fromJson(resp.body(), WorldStatusResponse.class);
    }

    public LockAcquireResult acquireLock(String worldId, String holderUuid, String holderName, boolean forceTakeoverIfStale)
            throws IOException, InterruptedException {
        String path = "/api/v1/worlds/" + encodeSegment(worldId) + "/lock";
        LockPayload payload = new LockPayload(holderUuid, holderName, forceTakeoverIfStale);
        HttpRequest req = requestBuilder(path)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        WorldStatusResponse status = null;
        try {
            status = GSON.fromJson(resp.body(), WorldStatusResponse.class);
        } catch (Exception ignored) {
        }
        if (resp.statusCode() == 200) {
            return new LockAcquireResult(true, status, "Lock acquired");
        } else if (resp.statusCode() == 409) {
            return new LockAcquireResult(false, status, "World is currently in use by " + (status != null ? status.holderName : "another player"));
        } else {
            throw new IOException("POST " + path + " failed with HTTP " + resp.statusCode() + ": " + resp.body());
        }
    }

    public WorldStatusResponse sendHeartbeat(String worldId, String holderUuid, String lanTunnelUrl)
            throws IOException, InterruptedException {
        String path = "/api/v1/worlds/" + encodeSegment(worldId) + "/heartbeat";
        HeartbeatPayload payload = new HeartbeatPayload(holderUuid, lanTunnelUrl);
        HttpRequest req = requestBuilder(path)
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new IOException("Heartbeat failed with HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return GSON.fromJson(resp.body(), WorldStatusResponse.class);
    }

    public WorldStatusResponse releaseLock(String worldId, String holderUuid) throws IOException, InterruptedException {
        String path = "/api/v1/worlds/" + encodeSegment(worldId) + "/unlock";
        UnlockPayload payload = new UnlockPayload(holderUuid);
        HttpRequest req = requestBuilder(path)
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new IOException("Unlock failed with HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return GSON.fromJson(resp.body(), WorldStatusResponse.class);
    }

    public WorldStatusResponse requestJoinLan(String worldId, String requesterUuid, String requesterName)
            throws IOException, InterruptedException {
        String path = "/api/v1/worlds/" + encodeSegment(worldId) + "/join-request";
        JoinRequestPayload payload = new JoinRequestPayload(requesterUuid, requesterName);
        HttpRequest req = requestBuilder(path)
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new IOException("Join request failed with HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return GSON.fromJson(resp.body(), WorldStatusResponse.class);
    }

    public DeltaManifest getRemoteManifest(String worldId) throws IOException, InterruptedException {
        String path = "/api/v1/worlds/" + encodeSegment(worldId) + "/manifest";
        HttpRequest req = requestBuilder(path)
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() == 404) {
            return new DeltaManifest(0L, java.util.Collections.emptyMap());
        }
        if (resp.statusCode() != 200) {
            throw new IOException("GET manifest failed with HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return DeltaManifest.fromJson(resp.body());
    }

    public void downloadFile(String worldId, String relativePath, Path targetFile, String expectedSha256)
            throws IOException, InterruptedException {
        String path = "/api/v1/worlds/" + encodeSegment(worldId) + "/files/" + encodeRelativePath(relativePath);
        HttpRequest req = requestBuilder(path)
                .timeout(Duration.ofMinutes(2))
                .GET()
                .build();
        Files.createDirectories(targetFile.getParent());
        Path tempFile = targetFile.resolveSibling(targetFile.getFileName().toString() + ".mcloud.tmp");
        try {
            HttpResponse<Path> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofFile(tempFile));
            if (resp.statusCode() != 200) {
                throw new IOException("Failed to download " + relativePath + " (HTTP " + resp.statusCode() + ")");
            }
            if (expectedSha256 != null && !expectedSha256.isBlank()) {
                String actualSha256 = DeltaManifest.computeSha256(tempFile);
                if (!expectedSha256.equalsIgnoreCase(actualSha256)) {
                    throw new IOException("SHA-256 mismatch for downloaded file " + relativePath
                            + ": expected " + expectedSha256 + " but got " + actualSha256);
                }
            }
            Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    public void uploadFile(String worldId, String relativePath, Path sourceFile, String sha256)
            throws IOException, InterruptedException {
        String path = "/api/v1/worlds/" + encodeSegment(worldId) + "/files/" + encodeRelativePath(relativePath);
        HttpRequest req = requestBuilder(path)
                .timeout(Duration.ofMinutes(2))
                .header("Content-Type", "application/octet-stream")
                .header("X-File-Sha256", sha256)
                .PUT(HttpRequest.BodyPublishers.ofFile(sourceFile))
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200 && resp.statusCode() != 201) {
            throw new IOException("Failed to upload " + relativePath + " (HTTP " + resp.statusCode() + "): " + resp.body());
        }
    }

    public DeltaManifest commitManifest(String worldId, String holderUuid, DeltaManifest newManifest)
            throws IOException, InterruptedException {
        String path = "/api/v1/worlds/" + encodeSegment(worldId) + "/commit";
        CommitPayload payload = new CommitPayload(holderUuid, newManifest);
        HttpRequest req = requestBuilder(path)
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new IOException("Commit manifest failed with HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return DeltaManifest.fromJson(resp.body());
    }

    private static String encodeSegment(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String encodeRelativePath(String relativePath) {
        String[] parts = relativePath.split("/");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append('/');
            sb.append(encodeSegment(parts[i]));
        }
        return sb.toString();
    }

    private record LockPayload(String holderUuid, String holderName, boolean forceTakeoverIfStale) {
    }

    private record HeartbeatPayload(String holderUuid, String lanTunnelUrl) {
    }

    private record UnlockPayload(String holderUuid) {
    }

    private record JoinRequestPayload(String requesterUuid, String requesterName) {
    }

    private record CommitPayload(String holderUuid, DeltaManifest manifest) {
    }
}
