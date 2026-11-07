package dev.ufz.mcloudsync.sync;

import dev.ufz.mcloudsync.MCloudSyncMod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
            String lanTunnelUrl,
            String lockedByPlayerName,
            String message
    ) {
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
        if (!WorldSyncMetadata.isSyncedWorld(worldDir)) {
            return new SyncSessionResult(true, false, null, null, "Local unsynced world");
        }

        WorldSyncMetadata meta = WorldSyncMetadata.load(worldDir);
        SyncCoordinatorClient client = new SyncCoordinatorClient(meta.coordinatorUrl, meta.authToken);
        String playerUuidStr = localPlayerUuid.toString();

        // Check if we crashed last time while holding the world
        if (LocalCrashJournal.hasDirtyCrashJournal(worldDir)) {
            MCloudSyncMod.LOGGER.warn("[MCloudSync] Detected dirty crash journal in {}. Running automatic crash recovery upload...", worldDir.getFileName());
            if (listener != null) listener.onProgress("Recovering crashed session...", 0, 1);
            SyncCoordinatorClient.LockAcquireResult recoveryLock = client.acquireLock(meta.worldId, playerUuidStr, localPlayerName, true);
            if (recoveryLock.acquired()) {
                pushWorldDelta(worldDir, meta, client, localPlayerUuid, listener);
                LocalCrashJournal.clearSessionJournal(worldDir);
            }
        }

        // Attempt to acquire mutex lock
        SyncCoordinatorClient.LockAcquireResult lockRes = client.acquireLock(meta.worldId, playerUuidStr, localPlayerName, true);
        if (!lockRes.acquired()) {
            SyncCoordinatorClient.WorldStatusResponse status = lockRes.status();
            String holder = status != null && status.holderName != null ? status.holderName : "your friend";
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
            LocalCrashJournal.markSessionActive(worldDir, playerUuidStr, localPlayerName);
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

        // Step 4: Restore local player into level.dat so local world remains immediately playable offline if needed
        LevelDatPlayerSwapper.prepareForLocalPlayer(worldDir, localPlayerUuid);
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
