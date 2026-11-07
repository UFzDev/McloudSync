package dev.ufz.mcloudsync.sync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Prevents the "Zombie Lock / Unsynced Crash Trap" ("Trampa C").
 *
 * When a player opens a synced world in Singleplayer, MCloudSync creates `.mcloud-local.lock`
 * inside `saves/<WorldName>/`. When the player exits normally and the Delta Push succeeds,
 * `.mcloud-local.lock` is deleted.
 *
 * If Minecraft crashes (Exit Code 1) or the PC loses power, `.mcloud-local.lock` remains on disk.
 * On the next startup or before anyone plays, MCloudSync detects this dirty journal and
 * performs an automatic crash-recovery upload so zero progress is lost!
 */
public class LocalCrashJournal {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public String holderUuid;
    public String holderName;
    public long sessionStartedEpochMs;
    public boolean dirty;

    public LocalCrashJournal() {
    }

    public LocalCrashJournal(String holderUuid, String holderName) {
        this.holderUuid = holderUuid;
        this.holderName = holderName;
        this.sessionStartedEpochMs = Instant.now().toEpochMilli();
        this.dirty = true;
    }

    public static Path getJournalPath(Path worldDir) {
        return worldDir.resolve(WorldSyncMetadata.LOCAL_LOCK_FILENAME);
    }

    public static boolean hasDirtyCrashJournal(Path worldDir) {
        if (worldDir == null || !Files.isDirectory(worldDir)) {
            return false;
        }
        Path path = getJournalPath(worldDir);
        if (!Files.isRegularFile(path)) {
            return false;
        }
        try {
            LocalCrashJournal journal = load(worldDir);
            return journal != null && journal.dirty;
        } catch (Exception e) {
            return true;
        }
    }

    public static void markSessionActive(Path worldDir, String holderUuid, String holderName) throws IOException {
        Files.createDirectories(worldDir);
        LocalCrashJournal journal = new LocalCrashJournal(holderUuid, holderName);
        try (Writer writer = Files.newBufferedWriter(getJournalPath(worldDir), StandardCharsets.UTF_8)) {
            GSON.toJson(journal, writer);
        }
    }

    public static LocalCrashJournal load(Path worldDir) throws IOException {
        Path path = getJournalPath(worldDir);
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return GSON.fromJson(reader, LocalCrashJournal.class);
        }
    }

    public static void clearSessionJournal(Path worldDir) throws IOException {
        if (worldDir == null) return;
        Files.deleteIfExists(getJournalPath(worldDir));
    }
}
