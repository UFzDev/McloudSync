package dev.ufz.mcloudsync.sync;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Solves the critical Singleplayer Cloned Inventory Trap ("Trampa A").
 *
 * In Minecraft Singleplayer (IntegratedServer), the host player's inventory, position,
 * dimensions, and NBT state are stored inside `level.dat` -> `Data` -> `Player`.
 * When a second player downloads the world and opens it in Singleplayer, Minecraft
 * loads `Data.Player` regardless of whose UUID is playing, causing the second player
 * to spawn inside the first player's body and overwrite their inventory!
 *
 * This swapper enforces dedicated-server-like isolation:
 * 1. Before uploading (`normalizeForUpload`):
 *    - Extracts `Data.Player` (if present and has a valid UUID) into `playerdata/<uuid>.dat`
 *    - Removes `Data.Player` from `level.dat` so the shared `level.dat` is player-neutral.
 * 2. Before launching Singleplayer (`prepareForLocalPlayer`):
 *    - Looks up `playerdata/<localPlayerUuid>.dat`.
 *    - If present, injects it into `level.dat` -> `Data.Player`.
 *    - If absent (first time this player joins the world), removes `Data.Player` from `level.dat`
 *      so Minecraft spawns them fresh at the World Spawn without cloning their friend's character.
 */
public final class LevelDatPlayerSwapper {
    private static final String DATA_KEY = "Data";
    private static final String PLAYER_KEY = "Player";
    private static final String UUID_KEY = "UUID";

    private LevelDatPlayerSwapper() {
    }

    /**
     * Prepares `level.dat` inside `worldDir` right before `localPlayerUuid` opens the world in Singleplayer.
     *
     * @param worldDir        Path to the world save folder (`saves/<WorldName>`)
     * @param localPlayerUuid UUID of the local player opening the world
     * @return true if `level.dat` was updated, false if `level.dat` does not exist yet
     */
    public static boolean prepareForLocalPlayer(Path worldDir, UUID localPlayerUuid) throws IOException {
        if (worldDir == null || localPlayerUuid == null) {
            return false;
        }
        Path levelDatPath = worldDir.resolve("level.dat");
        if (!Files.isRegularFile(levelDatPath)) {
            return false;
        }

        CompoundTag root = NbtIo.readCompressed(levelDatPath, NbtAccounter.unlimitedHeap());
        if (!root.contains(DATA_KEY, 10)) { // 10 = TAG_Compound
            return false;
        }

        CompoundTag dataTag = root.getCompound(DATA_KEY);
        Path playerDataDir = worldDir.resolve("playerdata");
        Files.createDirectories(playerDataDir);

        // Step 1: If `Data.Player` currently holds someone else's data, back it up to their `playerdata/<uuid>.dat`
        backupEmbeddedPlayerIfPresent(dataTag, playerDataDir);

        // Step 2: Load `playerdata/<localPlayerUuid>.dat` if it exists
        Path localPlayerFile = playerDataDir.resolve(localPlayerUuid.toString() + ".dat");
        if (Files.isRegularFile(localPlayerFile)) {
            CompoundTag localPlayerTag = NbtIo.readCompressed(localPlayerFile, NbtAccounter.unlimitedHeap());
            localPlayerTag.putUUID(UUID_KEY, localPlayerUuid);
            dataTag.put(PLAYER_KEY, localPlayerTag);
        } else {
            // Player has never played in this world before: remove `Data.Player` so they spawn clean!
            dataTag.remove(PLAYER_KEY);
        }

        NbtIo.writeCompressed(root, levelDatPath);
        return true;
    }

    /**
     * Normalizes `level.dat` after a Singleplayer session ends and before computing the DeltaManifest for upload.
     * Ensures the active player's latest `Data.Player` state is saved to `playerdata/<uuid>.dat` and stripped from `level.dat`.
     *
     * @param worldDir       Path to the world save folder
     * @param fallbackUuid   Fallback UUID of the local host player in case `Data.Player` lacks an explicit `UUID` int-array tag
     * @return true if `level.dat` was processed
     */
    public static boolean normalizeForUpload(Path worldDir, UUID fallbackUuid) throws IOException {
        if (worldDir == null) {
            return false;
        }
        Path levelDatPath = worldDir.resolve("level.dat");
        if (!Files.isRegularFile(levelDatPath)) {
            return false;
        }

        CompoundTag root = NbtIo.readCompressed(levelDatPath, NbtAccounter.unlimitedHeap());
        if (!root.contains(DATA_KEY, 10)) {
            return false;
        }

        CompoundTag dataTag = root.getCompound(DATA_KEY);
        Path playerDataDir = worldDir.resolve("playerdata");
        Files.createDirectories(playerDataDir);

        if (dataTag.contains(PLAYER_KEY, 10)) {
            CompoundTag playerTag = dataTag.getCompound(PLAYER_KEY);
            UUID targetUuid = extractPlayerUuid(playerTag, fallbackUuid);
            if (targetUuid != null) {
                playerTag.putUUID(UUID_KEY, targetUuid);
                Path playerFile = playerDataDir.resolve(targetUuid.toString() + ".dat");
                NbtIo.writeCompressed(playerTag, playerFile);
            }
            dataTag.remove(PLAYER_KEY);
            NbtIo.writeCompressed(root, levelDatPath);
        }

        return true;
    }

    private static void backupEmbeddedPlayerIfPresent(CompoundTag dataTag, Path playerDataDir) throws IOException {
        if (!dataTag.contains(PLAYER_KEY, 10)) {
            return;
        }
        CompoundTag currentPlayerTag = dataTag.getCompound(PLAYER_KEY);
        UUID embeddedUuid = extractPlayerUuid(currentPlayerTag, null);
        if (embeddedUuid != null) {
            Path targetFile = playerDataDir.resolve(embeddedUuid.toString() + ".dat");
            // Only write if the playerdata file doesn't exist yet, or always keep the latest embedded snapshot
            NbtIo.writeCompressed(currentPlayerTag, targetFile);
        }
    }

    private static UUID extractPlayerUuid(CompoundTag playerTag, UUID fallback) {
        if (playerTag != null && playerTag.hasUUID(UUID_KEY)) {
            try {
                return playerTag.getUUID(UUID_KEY);
            } catch (Exception ignored) {
            }
        }
        return fallback;
    }
}
