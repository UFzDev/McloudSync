package dev.ufz.mcloudsync.sync;

import dev.ufz.mcloud.tunnel.CloudflaredManager;
import dev.ufz.mcloudsync.MCloudSyncMod;
import dev.ufz.mcloudsync.config.MCloudSyncConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.HttpUtil;
import net.minecraft.world.level.GameType;

import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Background daemon running while a synced Singleplayer world is active.
 * - Sends periodic heartbeats to the MCloudSync Coordinator to keep the session mutex alive.
 * - Publishes the active MCloud Cloudflare tunnel URL (`*.trycloudflare.com`) if the world is opened to LAN.
 * - Listens for friend Join Requests and displays an in-game Toast + clickable `[OPEN LAN WITH MCLOUD]` button.
 */
public class ActiveWorldSessionManager {
    private static ScheduledExecutorService scheduler;
    private static Path currentWorldDir;
    private static WorldSyncMetadata currentMeta;
    private static UUID currentPlayerUuid;
    private static final Set<String> notifiedRequesters = new HashSet<>();

    public static synchronized void startSession(Path worldDir, UUID playerUuid) {
        stopSession();
        if (!WorldSyncMetadata.isSyncedWorld(worldDir)) {
            return;
        }
        try {
            currentWorldDir = worldDir;
            currentMeta = WorldSyncMetadata.load(worldDir);
            currentPlayerUuid = playerUuid;
            notifiedRequesters.clear();

            int interval = Math.max(5, MCloudSyncConfig.get().heartbeatIntervalSeconds);
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "MCloudSync-Heartbeat");
                t.setDaemon(true);
                return t;
            });

            scheduler.scheduleAtFixedRate(ActiveWorldSessionManager::tickHeartbeat, 5, interval, TimeUnit.SECONDS);
            MCloudSyncMod.LOGGER.info("[MCloudSync] Started active session heartbeat for world '{}' ({})",
                    currentMeta.worldName, currentMeta.worldId);
        } catch (Exception e) {
            MCloudSyncMod.LOGGER.error("[MCloudSync] Failed to start session heartbeat", e);
        }
    }

    public static synchronized void stopSession() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        currentWorldDir = null;
        currentMeta = null;
        currentPlayerUuid = null;
        notifiedRequesters.clear();
    }

    public static synchronized Path getCurrentWorldDir() {
        return currentWorldDir;
    }

    public static synchronized WorldSyncMetadata getCurrentMeta() {
        return currentMeta;
    }

    private static void tickHeartbeat() {
        WorldSyncMetadata meta;
        UUID playerUuid;
        synchronized (ActiveWorldSessionManager.class) {
            meta = currentMeta;
            playerUuid = currentPlayerUuid;
        }
        if (meta == null || playerUuid == null) {
            return;
        }

        try {
            String activeLanUrl = null;
            if (CloudflaredManager.isRunning() && CloudflaredManager.getActiveUrl() != null) {
                activeLanUrl = CloudflaredManager.getActiveUrl().replace("https://", "");
            }

            SyncCoordinatorClient client = new SyncCoordinatorClient(meta.coordinatorUrl, meta.authToken);
            SyncCoordinatorClient.WorldStatusResponse status = client.sendHeartbeat(
                    meta.worldId,
                    playerUuid.toString(),
                    activeLanUrl
            );

            if (status != null && status.joinRequests != null && !status.joinRequests.isEmpty()) {
                for (SyncCoordinatorClient.JoinRequestInfo req : status.joinRequests) {
                    if (req.requesterUuid != null && notifiedRequesters.add(req.requesterUuid)) {
                        String friendName = req.requesterName != null ? req.requesterName : "A friend";
                        onFriendJoinRequest(friendName);
                    }
                }
            }
        } catch (Exception e) {
            MCloudSyncMod.LOGGER.debug("[MCloudSync] Heartbeat tick warning: {}", e.getMessage());
        }
    }

    private static void onFriendJoinRequest(String friendName) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (MCloudSyncConfig.get().autoOpenLanOnFriendRequest) {
                openWorldToMCloudLan(mc);
                return;
            }

            // Show native Minecraft Toast in top-right corner
            try {
                mc.getToasts().addToast(new SystemToast(
                        SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                        Component.literal("MCloud Sync » " + friendName + " wants to join!").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
                        Component.literal("Click in chat or run /mcloudsync openlan").withStyle(ChatFormatting.AQUA)
                ));
            } catch (Throwable ignored) {
            }

            // Send clickable prompt in chat
            ClickEvent runCmd = createRunCommandClickEvent("/mcloudsync openlan");
            HoverEvent hover = createShowTextHoverEvent("Click to open LAN & start MCloud tunnel automatically");

            MutableComponent openBtn = Component.literal("[OPEN LAN WITH MCLOUD]")
                    .withStyle(style -> {
                        style = style.withColor(ChatFormatting.GREEN).withBold(true);
                        if (runCmd != null) style = style.withClickEvent(runCmd);
                        if (hover != null) style = style.withHoverEvent(hover);
                        return style;
                    });

            MutableComponent chatMsg = Component.empty()
                    .append(Component.literal("----------------------------------------\n").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.literal("MCloud Sync ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
                    .append(Component.literal("» ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.literal(friendName).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD))
                    .append(Component.literal(" requested to join your world!\n").withStyle(ChatFormatting.WHITE))
                    .append(openBtn)
                    .append(Component.literal("\n----------------------------------------").withStyle(ChatFormatting.DARK_GRAY));

            if (mc.gui != null && mc.gui.getChat() != null) {
                mc.gui.getChat().addMessage(chatMsg);
            } else if (mc.player != null) {
                mc.player.sendSystemMessage(chatMsg);
            }
        });
    }

    /**
     * Publishes the active Singleplayer IntegratedServer to LAN (which triggers MCloud's
     * IntegratedServerLanMixin -> CloudflaredManager) and registers the tunnel URL with the coordinator.
     */
    public static boolean openWorldToMCloudLan(Minecraft mc) {
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null) {
            return false;
        }
        if (!server.isPublished()) {
            int port = HttpUtil.getAvailablePort();
            GameType defaultGameMode = server.getDefaultGameType();
            boolean allowCommands = server.getWorldData().isAllowCommands();
            boolean published = server.publishServer(defaultGameMode, allowCommands, port);
            if (!published) {
                return false;
            }
        } else if (!CloudflaredManager.isRunning()) {
            CloudflaredManager.start(server.getPort());
        }

        // Wait asynchronously for CloudflaredManager to emit the URL, then push an immediate heartbeat
        Thread notifier = new Thread(() -> {
            for (int i = 0; i < 60; i++) {
                if (CloudflaredManager.isRunning() && CloudflaredManager.getActiveUrl() != null) {
                    tickHeartbeat();
                    break;
                }
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                    break;
                }
            }
        }, "MCloudSync-LanUrlPublisher");
        notifier.setDaemon(true);
        notifier.start();
        return true;
    }

    private static ClickEvent createRunCommandClickEvent(String command) {
        try {
            Class<?> runCmdClass = null;
            for (String className : new String[]{
                    "net.minecraft.network.chat.ClickEvent$RunCommand",
                    "net.minecraft.text.ClickEvent$RunCommand"
            }) {
                try {
                    runCmdClass = Class.forName(className);
                    break;
                } catch (ClassNotFoundException ignored) {
                }
            }
            if (runCmdClass != null) {
                Constructor<?> ctor = runCmdClass.getDeclaredConstructor(String.class);
                ctor.setAccessible(true);
                return (ClickEvent) ctor.newInstance(command);
            }
        } catch (Throwable ignored) {
        }

        try {
            for (Constructor<?> ctor : ClickEvent.class.getDeclaredConstructors()) {
                if (ctor.getParameterCount() == 2) {
                    Class<?> p1 = ctor.getParameterTypes()[0];
                    Class<?> p2 = ctor.getParameterTypes()[1];
                    if (p1.isEnum() && p2 == String.class) {
                        for (Object constant : p1.getEnumConstants()) {
                            if (constant.toString().equals("RUN_COMMAND")) {
                                ctor.setAccessible(true);
                                return (ClickEvent) ctor.newInstance(constant, command);
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static HoverEvent createShowTextHoverEvent(String text) {
        try {
            Class<?> showTextClass = null;
            for (String className : new String[]{
                    "net.minecraft.network.chat.HoverEvent$ShowText",
                    "net.minecraft.text.HoverEvent$ShowText"
            }) {
                try {
                    showTextClass = Class.forName(className);
                    break;
                } catch (ClassNotFoundException ignored) {
                }
            }
            if (showTextClass != null) {
                for (Constructor<?> ctor : showTextClass.getDeclaredConstructors()) {
                    if (ctor.getParameterCount() == 1 && Component.class.isAssignableFrom(ctor.getParameterTypes()[0])) {
                        ctor.setAccessible(true);
                        return (HoverEvent) ctor.newInstance(Component.literal(text));
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            for (Constructor<?> ctor : HoverEvent.class.getDeclaredConstructors()) {
                if (ctor.getParameterCount() == 2) {
                    Class<?> p1 = ctor.getParameterTypes()[0];
                    Class<?> p2 = ctor.getParameterTypes()[1];
                    if (p1.isEnum() && Component.class.isAssignableFrom(p2)) {
                        for (Object constant : p1.getEnumConstants()) {
                            if (constant.toString().equals("SHOW_TEXT")) {
                                ctor.setAccessible(true);
                                return (HoverEvent) ctor.newInstance(constant, Component.literal(text));
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
