package dev.ufz.mcloudsync.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.ufz.mcloudsync.MCloudSyncMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class MCloudSyncConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static MCloudSyncConfig instance;

    public boolean enabled = true;
    public String defaultCoordinatorUrl = "http://127.0.0.1:8787";
    public String defaultAuthToken = "";
    public int heartbeatIntervalSeconds = 20;
    public boolean autoOpenLanOnFriendRequest = false;

    public static synchronized MCloudSyncConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    public static Path getConfigPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("mcloudsync.json");
    }

    public static MCloudSyncConfig load() {
        Path path = getConfigPath();
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                MCloudSyncConfig loaded = GSON.fromJson(reader, MCloudSyncConfig.class);
                if (loaded != null) {
                    return loaded;
                }
            } catch (Exception e) {
                MCloudSyncMod.LOGGER.error("[MCloudSync] Failed to read config/mcloudsync.json, using defaults", e);
            }
        }
        MCloudSyncConfig defaults = new MCloudSyncConfig();
        defaults.save();
        return defaults;
    }

    public void save() {
        Path path = getConfigPath();
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            MCloudSyncMod.LOGGER.error("[MCloudSync] Failed to save config/mcloudsync.json", e);
        }
    }
}
