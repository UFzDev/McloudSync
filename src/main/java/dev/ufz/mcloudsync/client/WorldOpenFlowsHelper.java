package dev.ufz.mcloudsync.client;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class WorldOpenFlowsHelper {
    private static final Set<String> PREPARED_LEVELS = ConcurrentHashMap.newKeySet();

    private WorldOpenFlowsHelper() {}

    public static void markLevelPrepared(String levelId) {
        if (levelId != null) {
            PREPARED_LEVELS.add(levelId);
        }
    }

    public static boolean consumeLevelPrepared(String levelId) {
        return levelId != null && PREPARED_LEVELS.remove(levelId);
    }
}
