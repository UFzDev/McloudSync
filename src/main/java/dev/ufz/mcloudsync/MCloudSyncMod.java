package dev.ufz.mcloudsync;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.ufz.mcloudsync.config.MCloudSyncConfig;
import dev.ufz.mcloudsync.sync.ActiveWorldSessionManager;
import dev.ufz.mcloudsync.sync.SyncCoordinatorClient;
import dev.ufz.mcloudsync.sync.WorldSyncMetadata;
import dev.ufz.mcloudsync.sync.WorldSyncOrchestrator;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Main entrypoint for MCloud Sync (`mcloudsync`).
 * 100% companion to MCloud (`mcloud`): manages Singleplayer world mutex locks,
 * SHA-256 delta file synchronization, `level.dat` UUID inventory isolation,
 * and automatic LAN tunnel handoffs.
 */
public class MCloudSyncMod implements ModInitializer {
    public static final String MOD_ID = "mcloudsync";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("[MCloudSync] Initializing P2P/Coordinator World Mutex & Delta Sync Engine (Companion to MCloud)...");
        MCloudSyncConfig.get();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> registerCommands(dispatcher));

        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPED.register(this::onServerStopped);

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (server.isDedicatedServer()) {
                return;
            }
            server.execute(() -> {
                try {
                    String name = (handler.getPlayer() != null) ? handler.getPlayer().getScoreboardName() : "guest";
                    LOGGER.info("[MCloudSync] Player {} disconnected from LAN session. Flushing player inventories and Cobblemon data to disk...", name);
                    server.getPlayerList().saveAll();
                } catch (Exception e) {
                    LOGGER.error("[MCloudSync] Failed to save player data on guest disconnect", e);
                }
            });
        });
    }

    private void onServerStarted(MinecraftServer server) {
        if (server.isDedicatedServer()) {
            return;
        }
        try {
            Path worldDir = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            if (WorldSyncMetadata.isSyncedWorld(worldDir)) {
                UUID hostUuid = getLocalPlayerUuid();
                if (hostUuid != null) {
                    ActiveWorldSessionManager.startSession(worldDir, hostUuid);
                }
            }
        } catch (Exception e) {
            LOGGER.error("[MCloudSync] Error starting session heartbeat on SERVER_STARTED", e);
        }
    }

    private void onServerStopped(MinecraftServer server) {
        if (server.isDedicatedServer()) {
            return;
        }
        ActiveWorldSessionManager.stopSession();
        try {
            Path worldDir = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            if (WorldSyncMetadata.isSyncedWorld(worldDir)) {
                UUID hostUuid = getLocalPlayerUuid();
                if (hostUuid != null) {
                    LOGGER.info("[MCloudSync] Singleplayer world stopped. Running Delta Push & releasing Mutex lock...");
                    WorldSyncOrchestrator.finalizeWorldAfterExit(
                            worldDir,
                            hostUuid,
                            (phase, done, total) -> LOGGER.info("[MCloudSync] {} ({}/{})", phase, done, total)
                    );
                    LOGGER.info("[MCloudSync] World synchronized and unlocked successfully!");
                }
            }
        } catch (Exception e) {
            LOGGER.error("[MCloudSync] Failed to finalize and upload world on SERVER_STOPPED (crash journal kept for next launch)", e);
        }
    }

    private static UUID getLocalPlayerUuid() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.getUser() != null) {
                return mc.getUser().getProfileId();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String getLocalPlayerName() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.getUser() != null) {
                return mc.getUser().getName();
            }
        } catch (Throwable ignored) {
        }
        return "Player";
    }

    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("mcloudsync")
                .then(Commands.literal("status")
                        .executes(ctx -> {
                            CommandSourceStack src = ctx.getSource();
                            Path worldDir = src.getServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
                            if (!WorldSyncMetadata.isSyncedWorld(worldDir)) {
                                src.sendSuccess(() -> Component.literal("MCloud Sync » This world is NOT linked yet. Use ")
                                        .withStyle(ChatFormatting.YELLOW)
                                        .append(Component.literal("/mcloudsync init <coordinatorUrl> [authToken]").withStyle(ChatFormatting.AQUA))
                                        .append(Component.literal(" to enable syncing.").withStyle(ChatFormatting.YELLOW)), false);
                                return 1;
                            }
                            try {
                                WorldSyncMetadata meta = WorldSyncMetadata.load(worldDir);
                                MutableComponent msg = Component.empty()
                                        .append(Component.literal("MCloud Sync ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
                                        .append(Component.literal("» ").withStyle(ChatFormatting.DARK_GRAY))
                                        .append(Component.literal("ENABLED\n").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD))
                                        .append(Component.literal("World ID: ").withStyle(ChatFormatting.GRAY))
                                        .append(Component.literal(meta.worldId + "\n").withStyle(ChatFormatting.WHITE))
                                        .append(Component.literal("Coordinator: ").withStyle(ChatFormatting.GRAY))
                                        .append(Component.literal(meta.coordinatorUrl + "\n").withStyle(ChatFormatting.AQUA))
                                        .append(Component.literal("Revision: ").withStyle(ChatFormatting.GRAY))
                                        .append(Component.literal(String.valueOf(meta.lastSyncedRevision)).withStyle(ChatFormatting.GREEN));
                                src.sendSuccess(() -> msg, false);
                            } catch (Exception e) {
                                src.sendFailure(Component.literal("MCloud Sync » Error reading metadata: " + e.getMessage()));
                            }
                            return 1;
                        }))
                .then(Commands.literal("init")
                        .executes(ctx -> initCurrentWorld(ctx.getSource(), MCloudSyncConfig.get().defaultCoordinatorUrl, MCloudSyncConfig.get().defaultAuthToken))
                        .then(Commands.argument("coordinatorUrl", StringArgumentType.string())
                                .executes(ctx -> initCurrentWorld(
                                        ctx.getSource(),
                                        StringArgumentType.getString(ctx, "coordinatorUrl"),
                                        MCloudSyncConfig.get().defaultAuthToken
                                ))
                                .then(Commands.argument("authToken", StringArgumentType.string())
                                        .executes(ctx -> initCurrentWorld(
                                                ctx.getSource(),
                                                StringArgumentType.getString(ctx, "coordinatorUrl"),
                                                StringArgumentType.getString(ctx, "authToken")
                                        )))))
                .then(Commands.literal("invite")
                        .executes(ctx -> {
                            CommandSourceStack src = ctx.getSource();
                            Path worldDir = src.getServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
                            if (!WorldSyncMetadata.isSyncedWorld(worldDir)) {
                                src.sendFailure(Component.literal("MCloud Sync » Enable sync first with /mcloudsync init"));
                                return 0;
                            }
                            try {
                                WorldSyncMetadata meta = WorldSyncMetadata.load(worldDir);
                                String token = meta.toInviteToken();
                                ClickEvent copyEvent = createCopyClickEvent(token);
                                HoverEvent hoverEvent = createShowTextHoverEvent("Click to copy Invite Token to clipboard");

                                MutableComponent copyBtn = Component.literal("[COPY INVITE TOKEN]")
                                        .withStyle(style -> {
                                            style = style.withColor(ChatFormatting.GREEN).withBold(true);
                                            if (copyEvent != null) style = style.withClickEvent(copyEvent);
                                            if (hoverEvent != null) style = style.withHoverEvent(hoverEvent);
                                            return style;
                                        });

                                MutableComponent msg = Component.empty()
                                        .append(Component.literal("MCloud Sync ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
                                        .append(Component.literal("» ").withStyle(ChatFormatting.DARK_GRAY))
                                        .append(Component.literal("Send this token to your friend so they can link the world:\n").withStyle(ChatFormatting.WHITE))
                                        .append(copyBtn);
                                src.sendSuccess(() -> msg, false);
                            } catch (Exception e) {
                                src.sendFailure(Component.literal("MCloud Sync » Failed to generate invite token: " + e.getMessage()));
                            }
                            return 1;
                        }))
                .then(Commands.literal("push")
                        .executes(ctx -> {
                            CommandSourceStack src = ctx.getSource();
                            MinecraftServer server = src.getServer();
                            Path worldDir = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
                            if (!WorldSyncMetadata.isSyncedWorld(worldDir)) {
                                src.sendFailure(Component.literal("MCloud Sync » World is not enabled for sync. Run /mcloudsync init first."));
                                return 0;
                            }
                            src.sendSuccess(() -> Component.literal("MCloud Sync » Saving chunks and uploading delta changes in background...").withStyle(ChatFormatting.YELLOW), false);
                            server.saveEverything(false, true, true);
                            UUID hostUuid = getLocalPlayerUuid() != null ? getLocalPlayerUuid() : UUID.randomUUID();
                            String hostName = getLocalPlayerName();

                            Thread uploader = new Thread(() -> {
                                try {
                                    WorldSyncMetadata meta = WorldSyncMetadata.load(worldDir);
                                    SyncCoordinatorClient client = new SyncCoordinatorClient(meta.coordinatorUrl, meta.authToken);
                                    client.acquireLock(meta.worldId, hostUuid.toString(), hostName, true);
                                    WorldSyncOrchestrator.pushWorldDelta(worldDir, meta, client, hostUuid, null);
                                    src.sendSuccess(() -> Component.literal("MCloud Sync » Delta upload complete! (Revision " + meta.lastSyncedRevision + ")").withStyle(ChatFormatting.GREEN), false);
                                } catch (Exception e) {
                                    LOGGER.error("[MCloudSync] Manual push failed", e);
                                    src.sendFailure(Component.literal("MCloud Sync » Upload failed: " + e.getMessage()));
                                }
                            }, "MCloudSync-ManualPush");
                            uploader.setDaemon(true);
                            uploader.start();
                            return 1;
                        }))
                .then(Commands.literal("openlan")
                        .executes(ctx -> {
                            CommandSourceStack src = ctx.getSource();
                            Minecraft mc = Minecraft.getInstance();
                            boolean ok = ActiveWorldSessionManager.openWorldToMCloudLan(mc);
                            if (ok) {
                                src.sendSuccess(() -> Component.literal("MCloud Sync » Opening LAN & starting MCloud tunnel for your friend...").withStyle(ChatFormatting.GREEN), true);
                                return 1;
                            } else {
                                src.sendFailure(Component.literal("MCloud Sync » Could not publish Singleplayer world to LAN."));
                                return 0;
                            }
                        }))
        );
    }

    private static int initCurrentWorld(CommandSourceStack src, String coordinatorUrl, String authToken) {
        MinecraftServer server = src.getServer();
        Path worldDir = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        String worldName = server.getWorldData().getLevelName();
        try {
            WorldSyncMetadata meta = WorldSyncMetadata.isSyncedWorld(worldDir)
                    ? WorldSyncMetadata.load(worldDir)
                    : new WorldSyncMetadata(UUID.randomUUID().toString(), worldName, coordinatorUrl, authToken);
            meta.coordinatorUrl = WorldSyncMetadata.normalizeUrl(coordinatorUrl);
            meta.authToken = authToken != null ? authToken.trim() : "";
            meta.worldName = worldName;
            meta.enabled = true;
            meta.save(worldDir);

            UUID hostUuid = getLocalPlayerUuid();
            if (hostUuid != null) {
                ActiveWorldSessionManager.startSession(worldDir, hostUuid);
            }

            String token = meta.toInviteToken();
            ClickEvent copyEvent = createCopyClickEvent(token);
            HoverEvent hoverEvent = createShowTextHoverEvent("Click to copy Invite Token to clipboard");

            MutableComponent copyBtn = Component.literal("[COPY INVITE TOKEN]")
                    .withStyle(style -> {
                        style = style.withColor(ChatFormatting.GREEN).withBold(true);
                        if (copyEvent != null) style = style.withClickEvent(copyEvent);
                        if (hoverEvent != null) style = style.withHoverEvent(hoverEvent);
                        return style;
                    });

            MutableComponent msg = Component.empty()
                    .append(Component.literal("----------------------------------------\n").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.literal("MCloud Sync ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
                    .append(Component.literal("» ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.literal("World Sync Activated!\n").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD))
                    .append(Component.literal("Coordinator: ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(meta.coordinatorUrl + "\n").withStyle(ChatFormatting.AQUA))
                    .append(copyBtn)
                    .append(Component.literal("  (Run ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal("/mcloudsync push").withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(" or Save & Quit to upload)\n").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal("----------------------------------------").withStyle(ChatFormatting.DARK_GRAY));

            src.sendSuccess(() -> msg, false);
            return 1;
        } catch (Exception e) {
            src.sendFailure(Component.literal("MCloud Sync » Failed to initialize world sync: " + e.getMessage()));
            return 0;
        }
    }

    private static ClickEvent createCopyClickEvent(String text) {
        try {
            Class<?> copyClass = null;
            for (String className : new String[]{
                    "net.minecraft.network.chat.ClickEvent$CopyToClipboard",
                    "net.minecraft.text.ClickEvent$CopyToClipboard"
            }) {
                try {
                    copyClass = Class.forName(className);
                    break;
                } catch (ClassNotFoundException ignored) {
                }
            }
            if (copyClass != null) {
                Constructor<?> ctor = copyClass.getDeclaredConstructor(String.class);
                ctor.setAccessible(true);
                return (ClickEvent) ctor.newInstance(text);
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
                            if (constant.toString().equals("COPY_TO_CLIPBOARD")) {
                                ctor.setAccessible(true);
                                return (ClickEvent) ctor.newInstance(constant, text);
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
