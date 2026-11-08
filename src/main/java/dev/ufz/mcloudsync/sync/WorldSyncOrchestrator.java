package dev.ufz.mcloudsync.sync;

import dev.ufz.mcloudsync.MCloudSyncMod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * High-level orchestrator that executes atomic Pre-Launch Pulls and Post-Exit Pushes
 * for synced Minecraft worlds.
 */
public class WorldSyncOrchestrator {

    public interface ProgressListener {
        void onProgress(String phase, int completedFiles, int totalFiles);
    }

    public record SyncSessionResult(
            boolean canPlaySingleplayer,
            boolean shouldConnectLan,
            boolean isStaleLock,
            String lanTunnelUrl,
            String lockedByPlayerName,
            String message
    ) {
        // Keep a convenience 5-arg constructor that sets isStaleLock = false for backward compatibility with existing callers/tests!
        public SyncSessionResult(boolean canPlaySingleplayer, boolean shouldConnectLan, String lanTunnelUrl, String lockedByPlayerName, String message) {
            this(canPlaySingleplayer, shouldConnectLan, false, lanTunnelUrl, lockedByPlayerName, message);
        }
    }

    /**
     * Executed before opening a world in Singleplayer:
     * 1. Checks if the world is a synced world (`.mcloud-sync.json`).
     * 2. If a dirty crash journal exists locally (`.mcloud-local.lock`), performs crash recovery push first.
     * 3. Acquires the mutex lock on the coordinator.
     *    - If locked by a friend and LAN tunnel is open -> signals to join LAN directly.
     *    - If locked by a friend and not in LAN -> returns locked state so player can send a Join Request.
     * 4. Performs Delta Pull (downloads only new/changed `.mca`/`.dat` files).
     * 5. Swaps `level.dat` `Data.Player` to match `localPlayerUuid` so inventories never clone!
     * 6. Marks `.mcloud-local.lock` dirty for crash protection.
     */
    public static SyncSessionResult prepareWorldBeforeLaunch(
            Path worldDir,
            UUID localPlayerUuid,
            String localPlayerName,
            ProgressListener listener
    ) throws IOException, InterruptedException {
        return prepareWorldBeforeLaunch(worldDir, localPlayerUuid, localPlayerName, false, listener);
    }

    public static SyncSessionResult prepareWorldBeforeLaunch(
            Path worldDir,
            UUID localPlayerUuid,
            String localPlayerName,
            boolean forceTakeoverStale,
            ProgressListener listener
    ) throws IOException, InterruptedException {
        if (!WorldSyncMetadata.isSyncedWorld(worldDir)) {
            return new SyncSessionResult(true, false, null, null, "Local unsynced world");
        }

        WorldSyncMetadata meta = WorldSyncMetadata.load(worldDir);
        SyncCoordinatorClient client = new SyncCoordinatorClient(meta.coordinatorUrl, meta.authToken);
        String playerUuidStr = localPlayerUuid.toString();

        // Check if we crashed last time while holding the world or played offline
        if (LocalCrashJournal.hasDirtyCrashJournal(worldDir)) {
            LocalCrashJournal journal = LocalCrashJournal.load(worldDir);
            MCloudSyncMod.LOGGER.warn("[MCloudSync] Detected dirty crash journal in {} (baseRevision: {}, playedOffline: {}). Running recovery...",
                    worldDir.getFileName(), journal != null ? journal.baseRevision : 0, journal != null && journal.playedOffline);
            if (listener != null) listener.onProgress("Connecting to coordinator...", 0, 1);
            SyncCoordinatorClient.LockAcquireResult recoveryLock = client.acquireLock(meta.worldId, playerUuidStr, localPlayerName, forceTakeoverStale);
            if (recoveryLock.acquired()) {
                DeltaManifest remoteManifest = client.getRemoteManifest(meta.worldId);
                boolean isDivergent = journal != null && journal.baseRevision > 0 && remoteManifest.revision > journal.baseRevision;
                if (isDivergent) {
                    MCloudSyncMod.LOGGER.warn("[MCloudSync] Divergent offline conflict detected (remote rev: {}, local base rev: {}). Merging...",
                            remoteManifest.revision, journal.baseRevision);
                    mergeDivergentOfflineSession(worldDir, meta, client, localPlayerUuid, journal, remoteManifest, listener);
                } else {
                    pushWorldDelta(worldDir, meta, client, localPlayerUuid, listener);
                    LocalCrashJournal.clearSessionJournal(worldDir);
                }
            }
        }

        // Attempt to acquire mutex lock
        SyncCoordinatorClient.LockAcquireResult lockRes = client.acquireLock(meta.worldId, playerUuidStr, localPlayerName, forceTakeoverStale);
        if (!lockRes.acquired() && forceTakeoverStale) {
            SyncCoordinatorClient.WorldStatusResponse initialStatus = lockRes.status();
            if (initialStatus != null && initialStatus.state == SyncCoordinatorClient.LockState.LOCKED) {
                // Host may still be finishing their exit upload and unlock. Poll up to 45 times (2s apart = up to 90s).
                for (int i = 1; i <= 45; i++) {
                    String holder = (lockRes.status() != null && lockRes.status().holderName != null) ? lockRes.status().holderName : "previous host";
                    int elapsedSec = i * 2;
                    if (listener != null) {
                        listener.onProgress("Waiting for " + holder + " to finish saving & uploading (" + elapsedSec + "s)...", i, 45);
                    }
                    Thread.sleep(2000);
                    lockRes = client.acquireLock(meta.worldId, playerUuidStr, localPlayerName, true);
                    if (lockRes.acquired()) {
                        break;
                    }
                    if (lockRes.status() != null && lockRes.status().lanTunnelUrl != null && !lockRes.status().lanTunnelUrl.isBlank()) {
                        // The host is actually still hosting LAN; stop waiting to take over
                        break;
                    }
                }
            }
        }
        if (Thread.currentThread().isInterrupted()) {
            if (lockRes.acquired()) {
                try {
                    client.releaseLock(meta.worldId, playerUuidStr);
                } catch (Exception ignored) {
                }
            }
            throw new InterruptedException("World preparation was interrupted");
        }
        if (!lockRes.acquired()) {
            SyncCoordinatorClient.WorldStatusResponse status = lockRes.status();
            String holder = status != null && status.holderName != null ? status.holderName : "your friend";
            if (status != null && status.state == SyncCoordinatorClient.LockState.STALE) {
                return new SyncSessionResult(
                        false,
                        false,
                        true,
                        null,
                        holder,
                        holder + " lost connection (internet drop or power outage) without uploading their latest save!"
                );
            }
            String lanUrl = status != null ? status.lanTunnelUrl : null;
            if (lanUrl != null && !lanUrl.isBlank()) {
                return new SyncSessionResult(
                        false,
                        true,
                        lanUrl,
                        holder,
                        holder + " has opened this world to LAN via MCloud (" + lanUrl + ")"
                );
            }
            return new SyncSessionResult(
                    false,
                    false,
                    null,
                    holder,
                    "World is currently being played in Singleplayer by " + holder
            );
        }

        // We hold the lock! Perform Delta Pull from coordinator
        try {
            pullWorldDelta(worldDir, meta, client, listener);
            // Prepare level.dat for this specific player UUID (prevents inventory cloning!)
            LevelDatPlayerSwapper.prepareForLocalPlayer(worldDir, localPlayerUuid);
            // Write crash journal before Minecraft starts ticking
            LocalCrashJournal.markSessionActive(worldDir, playerUuidStr, localPlayerName, meta.lastSyncedRevision, false);
            return new SyncSessionResult(true, false, null, localPlayerName, "World synchronized and locked for " + localPlayerName);
        } catch (Exception e) {
            // Release lock if pull fails so the world doesn't stay locked
            try {
                client.releaseLock(meta.worldId, playerUuidStr);
            } catch (Exception ignored) {
            }
            throw e;
        }
    }

    /**
     * Executed when Singleplayer world stops (`SERVER_STOPPED`):
     * 1. Normalizes `level.dat` (moves `Data.Player` into `playerdata/<uuid>.dat`).
     * 2. Computes SHA-256 DeltaManifest and uploads only changed files to the coordinator.
     * 3. Commits the new revision and clears `.mcloud-local.lock`.
     * 4. Releases the mutex lock on the coordinator.
     */
    public static void finalizeWorldAfterExit(
            Path worldDir,
            UUID localPlayerUuid,
            ProgressListener listener
    ) throws IOException, InterruptedException {
        if (!WorldSyncMetadata.isSyncedWorld(worldDir)) {
            return;
        }

        WorldSyncMetadata meta = WorldSyncMetadata.load(worldDir);
        SyncCoordinatorClient client = new SyncCoordinatorClient(meta.coordinatorUrl, meta.authToken);
        String playerUuidStr = localPlayerUuid.toString();

        // Right at the start of finalizeWorldAfterExit (before pushWorldDelta), send a quick
        // heartbeat with empty string to clear lanTunnelUrl on the coordinator while keeping
        // the lock LOCKED during the final upload!
        try {
            client.sendHeartbeat(meta.worldId, playerUuidStr, "");
        } catch (Exception e) {
            MCloudSyncMod.LOGGER.debug("[MCloudSync] Failed to clear lanTunnelUrl before exit upload: {}", e.getMessage());
        }

        try {
            pushWorldDelta(worldDir, meta, client, localPlayerUuid, listener);
            LocalCrashJournal.clearSessionJournal(worldDir);
        } finally {
            try {
                client.releaseLock(meta.worldId, playerUuidStr);
            } catch (Exception e) {
                MCloudSyncMod.LOGGER.error("[MCloudSync] Failed to release lock cleanly for world {}", meta.worldId, e);
            }
        }
    }

    /**
     * Downloads only modified/new files from the coordinator and deletes obsolete local files.
     */
    public static void pullWorldDelta(
            Path worldDir,
            WorldSyncMetadata meta,
            SyncCoordinatorClient client,
            ProgressListener listener
    ) throws IOException, InterruptedException {
        DeltaManifest remoteManifest = client.getRemoteManifest(meta.worldId);
        if (remoteManifest.revision <= 0 && remoteManifest.files.isEmpty()) {
            // First time this world is registered on the server; nothing to pull yet
            return;
        }

        // Normalize local level.dat before diffing so we don't lose any local playerdata
        LevelDatPlayerSwapper.normalizeForUpload(worldDir, null);

        DeltaManifest localManifest = DeltaManifest.buildFromDirectory(worldDir, meta.lastSyncedRevision);
        DeltaManifest.DiffResult diff = DeltaManifest.diff(remoteManifest, localManifest);

        int total = diff.toTransfer().size();
        int current = 0;
        for (String relPath : diff.toTransfer()) {
            current++;
            if (listener != null) {
                listener.onProgress("Downloading " + relPath, current, total);
            }
            DeltaManifest.FileEntry entry = remoteManifest.files.get(relPath);
            Path targetFile = resolveSafeChild(worldDir, relPath);
            client.downloadFile(meta.worldId, relPath, targetFile, entry != null ? entry.sha256() : null);
        }

        for (String relDelete : diff.toDelete()) {
            Path obsoleteFile = resolveSafeChild(worldDir, relDelete);
            Files.deleteIfExists(obsoleteFile);
        }

        meta.lastSyncedRevision = remoteManifest.revision;
        meta.save(worldDir);
        saveManifestCache(worldDir, remoteManifest);
    }

    /**
     * Normalizes `level.dat`, computes local SHA-256 manifest, uploads only changed files, and commits revision.
     */
    public static void pushWorldDelta(
            Path worldDir,
            WorldSyncMetadata meta,
            SyncCoordinatorClient client,
            UUID localPlayerUuid,
            ProgressListener listener
    ) throws IOException, InterruptedException {
        // Step 1: Extract Data.Player into playerdata/<uuid>.dat and strip Data.Player from level.dat
        LevelDatPlayerSwapper.normalizeForUpload(worldDir, localPlayerUuid);

        // Step 2: Fetch remote manifest and compute delta
        DeltaManifest remoteManifest = client.getRemoteManifest(meta.worldId);
        long nextRevision = Math.max(remoteManifest.revision, meta.lastSyncedRevision) + 1L;
        DeltaManifest localManifest = DeltaManifest.buildFromDirectory(worldDir, nextRevision);
        DeltaManifest.DiffResult diff = DeltaManifest.diff(localManifest, remoteManifest);

        int total = diff.toTransfer().size();
        int current = 0;
        for (String relPath : diff.toTransfer()) {
            current++;
            if (listener != null) {
                listener.onProgress("Uploading " + relPath, current, total);
            }
            DeltaManifest.FileEntry entry = localManifest.files.get(relPath);
            Path sourceFile = resolveSafeChild(worldDir, relPath);
            client.uploadFile(meta.worldId, relPath, sourceFile, entry.sha256());
        }

        // Step 3: Commit manifest (even if 0 files changed, updates revision or confirms state)
        DeltaManifest committed = client.commitManifest(meta.worldId, localPlayerUuid.toString(), localManifest);
        meta.lastSyncedRevision = committed.revision;
        meta.save(worldDir);
        saveManifestCache(worldDir, committed);

        // Step 4: Restore local player into level.dat so local world remains immediately playable offline if needed
        LevelDatPlayerSwapper.prepareForLocalPlayer(worldDir, localPlayerUuid);
    }

    /**
     * Performs a smart player-aware merge when both the local player and a friend
     * played offline while the coordinator was unreachable.
     * 1. Creates a timestamped safety backup of local player files in `.mcloud-conflict-backup`.
     * 2. Normalizes `level.dat` to extract the local player's inventory to `playerdata/<uuid>.dat`.
     * 3. For any file belonging to ANOTHER player (`playerdata/`, `cobblemonplayerdata/`, `advancements/`, `stats/`),
     *    downloads the remote version from the coordinator (preserving friend's progress 100%).
     * 4. For region/dimension files:
     *    - If modified remotely AND not modified locally since `lastSyncedRevision`, downloads from coordinator.
     *    - If modified locally, keeps our local version.
     * 5. Uploads the merged world delta via `pushWorldDelta` (committing revision = remoteRevision + 1)
     *    and clears the crash journal.
     */
    public static void mergeDivergentOfflineSession(
            Path worldDir,
            WorldSyncMetadata meta,
            SyncCoordinatorClient client,
            UUID localPlayerUuid,
            LocalCrashJournal journal,
            DeltaManifest remoteManifest,
            ProgressListener listener
    ) throws IOException, InterruptedException {
        // Step 1: Create timestamped safety backup of local player files
        Path backupDir = worldDir.resolve(".mcloud-conflict-backup").resolve("conflict-" + System.currentTimeMillis());
        Files.createDirectories(backupDir);
        createConflictBackup(worldDir, backupDir);
        MCloudSyncMod.LOGGER.info("[MCloudSync] Created safety backup before merge at {}", backupDir);

        // Step 2: Normalize local level.dat so local player's latest inventory is saved in playerdata/<uuid>.dat
        LevelDatPlayerSwapper.normalizeForUpload(worldDir, localPlayerUuid);

        // Step 3: Build local manifest after normalization
        long candidateRev = Math.max(remoteManifest.revision, meta.lastSyncedRevision) + 1L;
        DeltaManifest localManifest = DeltaManifest.buildFromDirectory(worldDir, candidateRev);
        String localUuidStr = (localPlayerUuid != null) ? localPlayerUuid.toString() : "";

        // Download friend's files and remote region files that were not touched locally
        for (Map.Entry<String, DeltaManifest.FileEntry> entry : remoteManifest.files.entrySet()) {
            String relPath = entry.getKey();
            DeltaManifest.FileEntry remoteEntry = entry.getValue();

            boolean isFriendPlayerData = (relPath.startsWith("playerdata/")
                    || relPath.startsWith("cobblemonplayerdata/")
                    || relPath.startsWith("advancements/")
                    || relPath.startsWith("stats/"))
                    && (!localUuidStr.isEmpty() && !relPath.contains(localUuidStr));

            boolean isRegionOrWorldData = relPath.startsWith("region/")
                    || relPath.startsWith("entities/")
                    || relPath.startsWith("poi/")
                    || relPath.startsWith("DIM-1/")
                    || relPath.startsWith("DIM1/")
                    || relPath.startsWith("dimensions/");

            if (isFriendPlayerData) {
                // Friend's progress: download remote file to preserve friend's Pokemon & inventory 100%!
                if (listener != null) listener.onProgress("Preserving friend progress: " + relPath, 0, 0);
                Path targetFile = resolveSafeChild(worldDir, relPath);
                client.downloadFile(meta.worldId, relPath, targetFile, remoteEntry.sha256());
            } else if (isRegionOrWorldData) {
                DeltaManifest.FileEntry localEntry = localManifest.files.get(relPath);
                if (localEntry == null) {
                    // Only exists remotely -> download
                    Path targetFile = resolveSafeChild(worldDir, relPath);
                    client.downloadFile(meta.worldId, relPath, targetFile, remoteEntry.sha256());
                } else if (!localEntry.sha256().equals(remoteEntry.sha256())) {
                    // Content differs. Check if modified locally:
                    boolean modifiedLocally = isFileModifiedLocally(worldDir, relPath, localEntry, journal);
                    if (!modifiedLocally) {
                        // Modified remotely AND NOT modified locally -> download remote version
                        Path targetFile = resolveSafeChild(worldDir, relPath);
                        client.downloadFile(meta.worldId, relPath, targetFile, remoteEntry.sha256());
                    } else {
                        // Modified locally -> keep local version!
                        MCloudSyncMod.LOGGER.info("[MCloudSync] Conflict resolution: keeping local version of {}", relPath);
                    }
                }
            } else if (!localManifest.files.containsKey(relPath)) {
                // Any other remote file that doesn't exist locally: download
                Path targetFile = resolveSafeChild(worldDir, relPath);
                client.downloadFile(meta.worldId, relPath, targetFile, remoteEntry.sha256());
            }
        }

        // Step 4: Commit merged world delta and clear journal
        pushWorldDelta(worldDir, meta, client, localPlayerUuid, listener);
        LocalCrashJournal.clearSessionJournal(worldDir);
        MCloudSyncMod.LOGGER.info("[MCloudSync] Divergent offline session successfully merged and pushed to coordinator (rev: {})", meta.lastSyncedRevision);
    }

    private static void createConflictBackup(Path worldDir, Path backupDir) {
        try {
            copyIfExists(worldDir.resolve("level.dat"), backupDir.resolve("level.dat"));
            copyIfExists(worldDir.resolve("level.dat_old"), backupDir.resolve("level.dat_old"));

            String[] playerDirs = new String[]{"playerdata", "cobblemonplayerdata", "advancements", "stats"};
            for (String dirName : playerDirs) {
                Path sourceDir = worldDir.resolve(dirName);
                if (Files.isDirectory(sourceDir)) {
                    Path destDir = backupDir.resolve(dirName);
                    Files.createDirectories(destDir);
                    try (var stream = Files.walk(sourceDir)) {
                        stream.forEach(source -> {
                            try {
                                Path dest = destDir.resolve(sourceDir.relativize(source));
                                if (Files.isDirectory(source)) {
                                    Files.createDirectories(dest);
                                } else {
                                    Files.copy(source, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                                }
                            } catch (IOException ignored) {
                            }
                        });
                    }
                }
            }
        } catch (Exception e) {
            MCloudSyncMod.LOGGER.error("[MCloudSync] Failed to create complete conflict backup", e);
        }
    }

    private static void copyIfExists(Path source, Path dest) {
        try {
            if (Files.isRegularFile(source)) {
                Files.createDirectories(dest.getParent());
                Files.copy(source, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
        }
    }

    private static boolean isFileModifiedLocally(
            Path worldDir,
            String relPath,
            DeltaManifest.FileEntry localEntry,
            LocalCrashJournal journal
    ) {
        DeltaManifest cache = loadManifestCache(worldDir);
        if (cache != null && cache.files.containsKey(relPath)) {
            String baseSha = cache.files.get(relPath).sha256();
            return !localEntry.sha256().equals(baseSha);
        }

        if (journal != null && journal.sessionStartedEpochMs > 0) {
            try {
                Path localFile = resolveSafeChild(worldDir, relPath);
                if (Files.isRegularFile(localFile)) {
                    long lastMod = Files.getLastModifiedTime(localFile).toMillis();
                    return lastMod >= (journal.sessionStartedEpochMs - 1000L);
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    public static final String MANIFEST_CACHE_FILENAME = ".mcloud-manifest-cache.json";

    public static void saveManifestCache(Path worldDir, DeltaManifest manifest) {
        if (worldDir == null || manifest == null) return;
        try {
            Path p = worldDir.resolve(MANIFEST_CACHE_FILENAME);
            Files.writeString(p, manifest.toJson(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            MCloudSyncMod.LOGGER.debug("[MCloudSync] Failed to write manifest cache: {}", e.getMessage());
        }
    }

    public static DeltaManifest loadManifestCache(Path worldDir) {
        if (worldDir == null) return null;
        try {
            Path p = worldDir.resolve(MANIFEST_CACHE_FILENAME);
            if (Files.isRegularFile(p)) {
                String raw = Files.readString(p, StandardCharsets.UTF_8);
                return DeltaManifest.fromJson(raw);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Bootstraps a brand-new shared world folder on a friend's PC from an Invite Token (`mcloudsync://...`).
     */
    public static Path cloneWorldFromInviteToken(
            Path savesDir,
            String folderName,
            String inviteToken,
            UUID localPlayerUuid,
            ProgressListener listener
    ) throws IOException, InterruptedException {
        WorldSyncMetadata meta = WorldSyncMetadata.fromInviteToken(inviteToken);
        String safeFolder = (folderName != null && !folderName.isBlank())
                ? folderName.replaceAll("[\\\\/:*?\"<>|]", "_")
                : meta.worldName.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (safeFolder.isBlank()) {
            safeFolder = "MCloud_Shared_World";
        }

        Path worldDir = savesDir.resolve(safeFolder);
        Files.createDirectories(worldDir);
        meta.save(worldDir);

        SyncCoordinatorClient client = new SyncCoordinatorClient(meta.coordinatorUrl, meta.authToken);
        pullWorldDelta(worldDir, meta, client, listener);
        if (localPlayerUuid != null) {
            LevelDatPlayerSwapper.prepareForLocalPlayer(worldDir, localPlayerUuid);
        }
        return worldDir;
    }

    private static Path resolveSafeChild(Path rootDir, String relativePath) throws IOException {
        Path resolved = rootDir.resolve(relativePath).normalize();
        if (!resolved.startsWith(rootDir.normalize())) {
            throw new IOException("Blocked path traversal attempt: " + relativePath);
        }
        return resolved;
    }
}
