package dev.ufz.mcloudsync.client;

import dev.ufz.mcloud.network.MCloudAddressRegistry;
import dev.ufz.mcloudsync.MCloudSyncMod;
import dev.ufz.mcloudsync.config.MCloudSyncConfig;
import dev.ufz.mcloudsync.sync.ActiveWorldSessionManager;
import dev.ufz.mcloudsync.sync.LevelDatPlayerSwapper;
import dev.ufz.mcloudsync.sync.LocalCrashJournal;
import dev.ufz.mcloudsync.sync.SyncCoordinatorClient;
import dev.ufz.mcloudsync.sync.WorldSyncMetadata;
import dev.ufz.mcloudsync.sync.WorldSyncOrchestrator;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.storage.LevelSummary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * UI Screens for MCloudSync:
 * 1. `LinkSharedWorldScreen`: Paste an `mcloudsync://...` invite token to clone & link a friend's world.
 * 2. `WorldSyncManagerScreen`: 1-click enable sync on an existing local world, copy invite tokens, or push deltas.
 * 3. `SyncingWorldScreen`: Smooth async progress screen displayed while acquiring locks and pulling deltas.
 * 4. `WorldLockedScreen`: Shown when trying to open a world currently in use by a friend.
 */
public class MCloudSyncScreens {

    // =========================================================================
    // 1. LINK SHARED WORLD SCREEN (Clone from friend's invite token)
    // =========================================================================
    public static class LinkSharedWorldScreen extends Screen {
        private final Screen parent;
        private EditBox tokenBox;
        private EditBox folderBox;
        private Button cloneButton;
        private Component statusText = Component.empty();

        public LinkSharedWorldScreen(Screen parent) {
            super(Component.literal("MCloud Sync — Link Shared World"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            int centerX = this.width / 2;
            int centerY = this.height / 2;

            this.folderBox = new EditBox(this.font, centerX - 150, centerY - 35, 300, 20, Component.literal("Folder Name"));
            this.folderBox.setMaxLength(64);
            this.folderBox.setValue("Shared_World");
            this.addRenderableWidget(this.folderBox);

            this.tokenBox = new EditBox(this.font, centerX - 150, centerY + 10, 300, 20, Component.literal("Invite Token"));
            this.tokenBox.setMaxLength(2048);
            this.tokenBox.setHint(Component.literal("Paste mcloudsync://... token here").withStyle(ChatFormatting.DARK_GRAY));

            // Auto-fill folder name when a valid invite token is pasted!
            this.tokenBox.setResponder(rawToken -> {
                String trimmed = rawToken.trim();
                if (trimmed.startsWith(WorldSyncMetadata.INVITE_PREFIX)) {
                    try {
                        WorldSyncMetadata meta = WorldSyncMetadata.fromInviteToken(trimmed);
                        if (meta != null && meta.worldName != null && !meta.worldName.isBlank()) {
                            String safeName = meta.worldName.replaceAll("[^a-zA-Z0-9_.-]", "_");
                            this.folderBox.setValue(safeName);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            this.addRenderableWidget(this.tokenBox);

            this.cloneButton = Button.builder(
                    Component.literal("Download & Link World").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD),
                    btn -> startCloneAsync()
            ).bounds(centerX - 150, centerY + 45, 145, 20).build();
            this.addRenderableWidget(this.cloneButton);

            this.addRenderableWidget(Button.builder(
                    Component.literal("Cancel"),
                    btn -> this.minecraft.setScreen(this.parent)
            ).bounds(centerX + 5, centerY + 45, 145, 20).build());
        }

        private void startCloneAsync() {
            String rawToken = this.tokenBox.getValue().trim();
            String folderName = this.folderBox.getValue().trim();
            if (rawToken.isEmpty()) {
                this.statusText = Component.literal("Please paste a valid mcloudsync:// invite token!").withStyle(ChatFormatting.RED);
                return;
            }

            this.cloneButton.active = false;
            this.statusText = Component.literal("Connecting to coordinator and downloading world...").withStyle(ChatFormatting.YELLOW);

            Path savesDir = this.minecraft.getLevelSource().getBaseDir();
            UUID playerUuid = this.minecraft.getUser().getProfileId();

            Thread worker = new Thread(() -> {
                try {
                    WorldSyncOrchestrator.cloneWorldFromInviteToken(
                            savesDir,
                            folderName,
                            rawToken,
                            playerUuid,
                            (phase, done, total) -> this.minecraft.execute(() ->
                                    this.statusText = Component.literal(phase + " (" + done + "/" + total + ")").withStyle(ChatFormatting.AQUA))
                    );
                    this.minecraft.execute(() -> {
                        this.statusText = Component.literal("World linked and synchronized! Returning to World List...").withStyle(ChatFormatting.GREEN);
                        this.minecraft.setScreen(this.parent);
                    });
                } catch (Exception e) {
                    MCloudSyncMod.LOGGER.error("[MCloudSync] Failed to link shared world", e);
                    this.minecraft.execute(() -> {
                        this.cloneButton.active = true;
                        this.statusText = Component.literal("Error: " + e.getMessage()).withStyle(ChatFormatting.RED);
                    });
                }
            }, "MCloudSync-CloneWorker");
            worker.setDaemon(true);
            worker.start();
        }

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            this.renderBackground(graphics, mouseX, mouseY, partialTick);
            super.render(graphics, mouseX, mouseY, partialTick);
            int centerX = this.width / 2;
            int centerY = this.height / 2;

            graphics.drawCenteredString(this.font, this.title, centerX, centerY - 75, 0xFFAA00);
            graphics.drawString(this.font, "Local Save Folder Name:", centerX - 150, centerY - 48, 0xA0A0A0);
            graphics.drawString(this.font, "Friend's MCloudSync Invite Token:", centerX - 150, centerY - 3, 0xA0A0A0);

            if (this.statusText != null && !this.statusText.getString().isEmpty()) {
                graphics.drawCenteredString(this.font, this.statusText, centerX, centerY + 80, 0xFFFFFF);
            }
        }
    }

    // =========================================================================
    // 2. WORLD SYNC MANAGER SCREEN (Share local world or manage existing sync)
    // =========================================================================
    public static class WorldSyncManagerScreen extends Screen {
        private final Screen parent;
        private final LevelSummary summary;
        private final Path worldDir;
        private WorldSyncMetadata metadata;
        private EditBox coordinatorUrlBox;
        private EditBox authTokenBox;
        private Component feedbackText = Component.empty();
        private Button primaryButton;

        public WorldSyncManagerScreen(Screen parent, LevelSummary summary) {
            super(Component.literal("MCloud Sync — World Settings"));
            this.parent = parent;
            this.summary = summary;
            if (summary != null) {
                this.worldDir = net.minecraft.client.Minecraft.getInstance().getLevelSource().getBaseDir().resolve(summary.getLevelId());
                try {
                    this.metadata = WorldSyncMetadata.load(this.worldDir);
                } catch (Exception ignored) {
                }
            } else {
                this.worldDir = null;
                this.metadata = null;
            }
        }

        @Override
        protected void init() {
            int centerX = this.width / 2;
            int centerY = this.height / 2;

            if (this.summary == null || this.worldDir == null) {
                // No world was selected
                this.addRenderableWidget(Button.builder(
                        Component.literal("Link Shared World (From Token)"),
                        btn -> this.minecraft.setScreen(new LinkSharedWorldScreen(this.parent))
                ).bounds(centerX - 120, centerY - 10, 240, 20).build());

                this.addRenderableWidget(Button.builder(
                        Component.literal("Back to World List"),
                        btn -> this.minecraft.setScreen(this.parent)
                ).bounds(centerX - 120, centerY + 20, 240, 20).build());
                return;
            }

            boolean isSynced = this.metadata != null && this.metadata.enabled;

            if (!isSynced) {
                // View A: ENABLE SYNC FOR EXISTING WORLD
                MCloudSyncConfig config = MCloudSyncConfig.get();
                this.coordinatorUrlBox = new EditBox(this.font, centerX - 150, centerY - 30, 300, 20, Component.literal("Coordinator URL"));
                this.coordinatorUrlBox.setMaxLength(512);
                this.coordinatorUrlBox.setValue(config.defaultCoordinatorUrl != null ? config.defaultCoordinatorUrl : "http://127.0.0.1:8787");
                this.addRenderableWidget(this.coordinatorUrlBox);

                this.authTokenBox = new EditBox(this.font, centerX - 150, centerY + 15, 300, 20, Component.literal("Auth Token (Optional)"));
                this.authTokenBox.setMaxLength(256);
                this.authTokenBox.setValue(config.defaultAuthToken != null ? config.defaultAuthToken : "");
                this.authTokenBox.setHint(Component.literal("Optional Bearer Token").withStyle(ChatFormatting.DARK_GRAY));
                this.addRenderableWidget(this.authTokenBox);

                this.primaryButton = Button.builder(
                        Component.literal("Enable Sync & Upload World").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD),
                        btn -> enableSyncAsync()
                ).bounds(centerX - 150, centerY + 50, 145, 20).build();
                this.addRenderableWidget(this.primaryButton);

                this.addRenderableWidget(Button.builder(
                        Component.literal("Cancel"),
                        btn -> this.minecraft.setScreen(this.parent)
                ).bounds(centerX + 5, centerY + 50, 145, 20).build());

            } else {
                // View B: WORLD ALREADY SYNCED — COPY INVITE TOKEN / PUSH DELTA / UNLINK
                String inviteToken = this.metadata.toInviteToken();

                this.primaryButton = Button.builder(
                        Component.literal("Copy Invite Token").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD),
                        btn -> {
                            this.minecraft.keyboardHandler.setClipboard(inviteToken);
                            this.feedbackText = Component.literal("Invite Token Copied to Clipboard!").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD);
                        }
                ).bounds(centerX - 130, centerY - 15, 260, 20).build();
                this.addRenderableWidget(this.primaryButton);

                this.addRenderableWidget(Button.builder(
                        Component.literal("Push Changes to Cloud Now"),
                        btn -> pushDeltaAsync()
                ).bounds(centerX - 130, centerY + 12, 260, 20).build());

                this.addRenderableWidget(Button.builder(
                        Component.literal("Unlink World (Local Only)").withStyle(ChatFormatting.RED),
                        btn -> unlinkWorld()
                ).bounds(centerX - 130, centerY + 39, 125, 20).build());

                this.addRenderableWidget(Button.builder(
                        Component.literal("Back"),
                        btn -> this.minecraft.setScreen(this.parent)
                ).bounds(centerX + 5, centerY + 39, 125, 20).build());
            }
        }

        private void enableSyncAsync() {
            String coordUrl = this.coordinatorUrlBox.getValue().trim();
            String authToken = this.authTokenBox.getValue().trim();
            if (coordUrl.isEmpty()) {
                this.feedbackText = Component.literal("Coordinator URL cannot be empty!").withStyle(ChatFormatting.RED);
                return;
            }

            this.primaryButton.active = false;
            this.feedbackText = Component.literal("Initializing sync & performing initial cloud upload...").withStyle(ChatFormatting.YELLOW);

            UUID playerUuid = this.minecraft.getUser().getProfileId();
            String playerName = this.minecraft.getUser().getName();

            Thread worker = new Thread(() -> {
                try {
                    WorldSyncMetadata newMeta = new WorldSyncMetadata(
                            UUID.randomUUID().toString(),
                            this.summary.getLevelName(),
                            coordUrl,
                            authToken
                    );
                    newMeta.save(this.worldDir);

                    SyncCoordinatorClient client = new SyncCoordinatorClient(newMeta.coordinatorUrl, newMeta.authToken);
                    client.acquireLock(newMeta.worldId, playerUuid.toString(), playerName, true);
                    WorldSyncOrchestrator.pushWorldDelta(this.worldDir, newMeta, client, playerUuid, (phase, done, total) ->
                            this.minecraft.execute(() -> this.feedbackText = Component.literal(phase + " (" + done + "/" + total + ")").withStyle(ChatFormatting.AQUA))
                    );

                    client.releaseLock(newMeta.worldId, playerUuid.toString());

                    String token = newMeta.toInviteToken();
                    this.minecraft.execute(() -> {
                        this.metadata = newMeta;
                        this.minecraft.keyboardHandler.setClipboard(token);
                        this.feedbackText = Component.literal("World Synced! Invite Token copied to clipboard!").withStyle(ChatFormatting.GREEN);
                        this.rebuildWidgets();
                    });
                } catch (Exception e) {
                    MCloudSyncMod.LOGGER.error("[MCloudSync] Failed to enable sync for world", e);
                    this.minecraft.execute(() -> {
                        this.primaryButton.active = true;
                        this.feedbackText = Component.literal("Error: " + e.getMessage()).withStyle(ChatFormatting.RED);
                    });
                }
            }, "MCloudSync-EnableSyncWorker");
            worker.setDaemon(true);
            worker.start();
        }

        private void pushDeltaAsync() {
            this.primaryButton.active = false;
            this.feedbackText = Component.literal("Scanning chunks and uploading deltas...").withStyle(ChatFormatting.YELLOW);

            UUID playerUuid = this.minecraft.getUser().getProfileId();
            String playerName = this.minecraft.getUser().getName();

            Thread worker = new Thread(() -> {
                try {
                    SyncCoordinatorClient client = new SyncCoordinatorClient(this.metadata.coordinatorUrl, this.metadata.authToken);
                    client.acquireLock(this.metadata.worldId, playerUuid.toString(), playerName, true);
                    WorldSyncOrchestrator.pushWorldDelta(this.worldDir, this.metadata, client, playerUuid, (phase, done, total) ->
                            this.minecraft.execute(() -> this.feedbackText = Component.literal(phase + " (" + done + "/" + total + ")").withStyle(ChatFormatting.AQUA))
                    );
                    client.releaseLock(this.metadata.worldId, playerUuid.toString());

                    this.minecraft.execute(() -> {
                        this.primaryButton.active = true;
                        this.feedbackText = Component.literal("Upload complete! Revision is now " + this.metadata.lastSyncedRevision).withStyle(ChatFormatting.GREEN);
                    });
                } catch (Exception e) {
                    MCloudSyncMod.LOGGER.error("[MCloudSync] Manual delta push failed", e);
                    this.minecraft.execute(() -> {
                        this.primaryButton.active = true;
                        this.feedbackText = Component.literal("Failed: " + e.getMessage()).withStyle(ChatFormatting.RED);
                    });
                }
            }, "MCloudSync-ManagerPush");
            worker.setDaemon(true);
            worker.start();
        }

        private void unlinkWorld() {
            try {
                Path metaPath = this.worldDir.resolve(WorldSyncMetadata.METADATA_FILENAME);
                Files.deleteIfExists(metaPath);
                this.metadata = null;
                this.feedbackText = Component.literal("World unlinked. It is now a purely local Singleplayer world.").withStyle(ChatFormatting.YELLOW);
                this.rebuildWidgets();
            } catch (IOException e) {
                this.feedbackText = Component.literal("Failed to unlink: " + e.getMessage()).withStyle(ChatFormatting.RED);
            }
        }

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            this.renderBackground(graphics, mouseX, mouseY, partialTick);
            super.render(graphics, mouseX, mouseY, partialTick);
            int centerX = this.width / 2;
            int centerY = this.height / 2;

            if (this.summary == null || this.worldDir == null) {
                graphics.drawCenteredString(this.font, "No World Selected", centerX, centerY - 45, 0xFFAA00);
                graphics.drawCenteredString(this.font, "Select a world from the list first to manage its cloud sync.", centerX, centerY - 25, 0xAAAAAA);
                return;
            }

            boolean isSynced = this.metadata != null && this.metadata.enabled;
            if (!isSynced) {
                graphics.drawCenteredString(this.font, Component.literal("Share World: " + this.summary.getLevelName()).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), centerX, centerY - 72, 0xFFAA00);
                graphics.drawCenteredString(this.font, "Enable P2P/Coordinator Delta Sync so your friends can play this world.", centerX, centerY - 55, 0x888888);
                graphics.drawString(this.font, "Coordinator URL:", centerX - 150, centerY - 43, 0xA0A0A0);
                graphics.drawString(this.font, "Auth Token (Optional):", centerX - 150, centerY + 2, 0xA0A0A0);
            } else {
                graphics.drawCenteredString(this.font, Component.literal("MCloud Sync: " + this.summary.getLevelName()).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), centerX, centerY - 70, 0xFFAA00);
                graphics.drawCenteredString(this.font, Component.literal("Status: ACTIVE (Revision " + this.metadata.lastSyncedRevision + ")").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD), centerX, centerY - 52, 0x55FF55);
                graphics.drawCenteredString(this.font, Component.literal("Coordinator: " + this.metadata.coordinatorUrl).withStyle(ChatFormatting.GRAY), centerX, centerY - 35, 0x888888);
            }

            if (this.feedbackText != null && !this.feedbackText.getString().isEmpty()) {
                graphics.drawCenteredString(this.font, this.feedbackText, centerX, centerY + 80, 0xFFFFFF);
            }
        }
    }

    // =========================================================================
    // 3. SYNCING WORLD SCREEN (Smooth async progress before loading world)
    // =========================================================================
    public static class SyncingWorldScreen extends Screen {
        private final Screen parent;
        private final Path worldDir;
        private final String levelId;
        private final UUID playerUuid;
        private final String playerName;
        private final boolean forceTakeoverStale;
        private final Runnable onProceed;

        private Component statusText = Component.literal("Checking world mutex lock...").withStyle(ChatFormatting.AQUA);
        private boolean hasError = false;
        private String errorMessage = null;
        private Thread syncWorkerThread = null;
        private volatile boolean cancelled = false;

        public SyncingWorldScreen(Screen parent, Path worldDir, String levelId, UUID playerUuid, String playerName, Runnable onProceed) {
            this(parent, worldDir, levelId, playerUuid, playerName, false, onProceed);
        }

        public SyncingWorldScreen(Screen parent, Path worldDir, String levelId, UUID playerUuid, String playerName, boolean forceTakeoverStale, Runnable onProceed) {
            super(Component.literal("MCloud Sync — Synchronizing World"));
            this.parent = parent;
            this.worldDir = worldDir;
            this.levelId = levelId;
            this.playerUuid = playerUuid;
            this.playerName = playerName;
            this.forceTakeoverStale = forceTakeoverStale;
            this.onProceed = onProceed;
        }

        public SyncingWorldScreen(Screen parent, Path worldDir, String levelId, Runnable onProceed, boolean forceTakeoverStale) {
            this(
                    parent,
                    worldDir,
                    levelId,
                    Minecraft.getInstance().getUser().getProfileId(),
                    Minecraft.getInstance().getUser().getName(),
                    forceTakeoverStale,
                    onProceed
            );
        }

        public SyncingWorldScreen(Screen parent, Path worldDir, String levelId, Runnable onProceed) {
            this(parent, worldDir, levelId, onProceed, false);
        }

        private void cancelAndReturn() {
            this.cancelled = true;
            if (this.syncWorkerThread != null) {
                this.syncWorkerThread.interrupt();
            }
            this.minecraft.setScreen(this.parent);
        }

        @Override
        public void onClose() {
            cancelAndReturn();
        }

        @Override
        protected void init() {
            int centerX = this.width / 2;
            int centerY = this.height / 2;

            if (this.hasError) {
                Button playOfflineButton = Button.builder(
                        Component.literal("Play Offline (Local Only)").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD),
                        btn -> playOfflineAndProceed()
                ).bounds(centerX - 130, centerY + 18, 260, 20).build();
                this.addRenderableWidget(playOfflineButton);

                Button cancelButton = Button.builder(
                        Component.literal("Cancel (Back to World List)"),
                        btn -> cancelAndReturn()
                ).bounds(centerX - 130, centerY + 44, 260, 20).build();
                this.addRenderableWidget(cancelButton);
                return;
            }

            Button cancelButton = Button.builder(
                    Component.literal("Cancel"),
                    btn -> cancelAndReturn()
            ).bounds(centerX - 60, centerY + 44, 120, 20).build();
            this.addRenderableWidget(cancelButton);

            // Start async synchronization
            if (this.syncWorkerThread == null) {
                this.syncWorkerThread = new Thread(() -> {
                    try {
                        WorldSyncOrchestrator.SyncSessionResult result = WorldSyncOrchestrator.prepareWorldBeforeLaunch(
                                this.worldDir,
                                this.playerUuid,
                                this.playerName,
                                this.forceTakeoverStale,
                                (phase, done, total) -> this.minecraft.execute(() -> {
                                    if (this.cancelled) return;
                                    if (total > 0) {
                                        this.statusText = Component.literal(phase + " (" + done + " / " + total + " files)").withStyle(ChatFormatting.AQUA);
                                    } else {
                                        this.statusText = Component.literal(phase).withStyle(ChatFormatting.AQUA);
                                    }
                                })
                        );

                        this.minecraft.execute(() -> {
                            if (this.cancelled || this.minecraft.screen != this) {
                                return; // User cancelled
                            }

                            if (!result.canPlaySingleplayer()) {
                                if (result.isStaleLock()) {
                                    this.minecraft.setScreen(new StaleLockWarningScreen(
                                            this.parent,
                                            this.worldDir,
                                            this.levelId,
                                            this.onProceed,
                                            result.lockedByPlayerName()
                                    ));
                                } else {
                                    this.minecraft.setScreen(new WorldLockedScreen(
                                            this.parent,
                                            this.worldDir,
                                            result.lockedByPlayerName(),
                                            result.lanTunnelUrl()
                                    ));
                                }
                            } else {
                                this.onProceed.run();
                            }
                        });
                    } catch (InterruptedException e) {
                        // User cancelled
                        MCloudSyncMod.LOGGER.info("[MCloudSync] World preparation cancelled by user");
                    } catch (Exception e) {
                        if (this.cancelled) return;
                        MCloudSyncMod.LOGGER.error("[MCloudSync] Async world preparation failed", e);
                        this.minecraft.execute(() -> {
                            this.hasError = true;
                            this.errorMessage = e.getMessage() != null ? e.getMessage() : e.toString();
                            this.statusText = Component.literal("Coordinator unreachable (offline/no internet).\nYou can safely play offline; MCloudSync will merge and upload your progress when connection returns.").withStyle(ChatFormatting.WHITE);
                            this.rebuildWidgets();
                        });
                    }
                }, "MCloudSync-PrepareWorldWorker");
                this.syncWorkerThread.setDaemon(true);
                this.syncWorkerThread.start();
            }
        }

        private void playOfflineAndProceed() {
            try {
                WorldSyncMetadata meta = WorldSyncMetadata.load(this.worldDir);
                long baseRevision = meta != null ? meta.lastSyncedRevision : 0L;
                // Prepare level.dat for this player so their own inventory is loaded
                LevelDatPlayerSwapper.prepareForLocalPlayer(this.worldDir, this.playerUuid);
                // Mark LocalCrashJournal active with baseRevision and playedOffline = true
                LocalCrashJournal.markSessionActive(
                        this.worldDir,
                        this.playerUuid.toString(),
                        this.playerName,
                        baseRevision,
                        true
                );
                // Start ActiveWorldSessionManager in offline polling mode (coordinatorOnline = false)
                ActiveWorldSessionManager.startSession(this.worldDir, this.playerUuid, false);
            } catch (Exception e) {
                MCloudSyncMod.LOGGER.error("[MCloudSync] Failed to prepare offline play state", e);
            }
            this.onProceed.run();
        }

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            this.renderBackground(graphics, mouseX, mouseY, partialTick);
            super.render(graphics, mouseX, mouseY, partialTick);
            int centerX = this.width / 2;
            int centerY = this.height / 2;

            graphics.drawCenteredString(this.font, this.title, centerX, centerY - 65, 0xFFAA00);

            if (this.statusText != null && !this.statusText.getString().isEmpty()) {
                List<FormattedCharSequence> lines = this.font.split(this.statusText, Math.min(320, this.width - 40));
                int textY = centerY - 30;
                for (FormattedCharSequence line : lines) {
                    graphics.drawCenteredString(this.font, line, centerX, textY, 0xFFFFFF);
                    textY += 12;
                }
            }
        }
    }

    // =========================================================================
    // 4. WORLD LOCKED SCREEN (Friend currently holding the world)
    // =========================================================================
    public static class WorldLockedScreen extends Screen {
        private final Screen parent;
        private final Path worldDir;
        private final String holderName;
        private String activeLanUrl;
        private Component feedbackText = Component.empty();
        private Button actionButton;

        public WorldLockedScreen(Screen parent, Path worldDir, String holderName, String activeLanUrl) {
            super(Component.literal("MCloud Sync — World In Use"));
            this.parent = parent;
            this.worldDir = worldDir;
            this.holderName = holderName != null ? holderName : "Your Friend";
            this.activeLanUrl = activeLanUrl;
        }

        @Override
        protected void init() {
            int centerX = this.width / 2;
            int centerY = this.height / 2;

            boolean hasLan = this.activeLanUrl != null && !this.activeLanUrl.isBlank();
            Component btnLabel = hasLan
                    ? Component.literal("Join " + this.holderName + "'s MCloud LAN Now").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)
                    : Component.literal("Ask " + this.holderName + " to Open LAN").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD);

            this.actionButton = Button.builder(btnLabel, btn -> {
                if (this.activeLanUrl != null && !this.activeLanUrl.isBlank()) {
                    connectToMCloudTunnel(this.activeLanUrl);
                } else {
                    sendJoinRequestAndPoll();
                }
            }).bounds(centerX - 130, centerY + 18, 260, 20).build();
            this.addRenderableWidget(this.actionButton);

            this.addRenderableWidget(Button.builder(
                    Component.literal("Back to World List"),
                    btn -> this.minecraft.setScreen(this.parent)
            ).bounds(centerX - 130, centerY + 44, 260, 20).build());
        }

        private void connectToMCloudTunnel(String tunnelAddress) {
            if (tunnelAddress == null || tunnelAddress.isBlank()) {
                return;
            }
            String raw = tunnelAddress.trim();
            String fullUrl = raw;
            if (!raw.contains("://")) {
                fullUrl = "https://" + raw;
            }

            String cleanAddress;
            try {
                cleanAddress = MCloudAddressRegistry.normalizeServerAddressInput(fullUrl);
            } catch (Throwable t) {
                cleanAddress = raw.replace("https://", "").replace("wss://", "").trim();
            }
            if (cleanAddress == null || cleanAddress.isBlank()) {
                cleanAddress = raw.replace("https://", "").replace("wss://", "").trim();
            }

            try {
                String hostOnly = cleanAddress;
                int port = 443;
                int colonIdx = cleanAddress.indexOf(':');
                if (colonIdx > 0) {
                    hostOnly = cleanAddress.substring(0, colonIdx);
                    try {
                        port = Integer.parseInt(cleanAddress.substring(colonIdx + 1));
                    } catch (NumberFormatException ignored) {
                    }
                }
                MCloudAddressRegistry.registerEndpoint(hostOnly, port, true, "/mcloud");
            } catch (Throwable ignored) {
            }

            ActiveWorldSessionManager.setLastJoinedLanWorldDir(this.worldDir);
            ServerData serverData = new ServerData("MCloud Shared World (" + this.holderName + ")", cleanAddress, ServerData.Type.OTHER);
            Screen multiplayerParent = new JoinMultiplayerScreen(new TitleScreen());
            ConnectScreen.startConnecting(multiplayerParent, this.minecraft, ServerAddress.parseString(cleanAddress), serverData, false, null);
        }

        private void sendJoinRequestAndPoll() {
            this.actionButton.active = false;
            this.feedbackText = Component.literal("Sending notification to " + this.holderName + "...").withStyle(ChatFormatting.YELLOW);

            String myUuid = this.minecraft.getUser().getProfileId().toString();
            String myName = this.minecraft.getUser().getName();

            Thread poller = new Thread(() -> {
                try {
                    WorldSyncMetadata meta = WorldSyncMetadata.load(this.worldDir);
                    SyncCoordinatorClient client = new SyncCoordinatorClient(meta.coordinatorUrl, meta.authToken);
                    client.requestJoinLan(meta.worldId, myUuid, myName);

                    this.minecraft.execute(() ->
                            this.feedbackText = Component.literal("Request sent! Waiting for " + this.holderName + " to click [OPEN LAN]...").withStyle(ChatFormatting.AQUA));

                    for (int i = 0; i < 60; i++) {
                        Thread.sleep(2000);
                        if (this.minecraft.screen != this) {
                            return;
                        }
                        SyncCoordinatorClient.WorldStatusResponse status = client.getWorldStatus(meta.worldId);
                        if (status != null && status.lanTunnelUrl != null && !status.lanTunnelUrl.isBlank()) {
                            String url = status.lanTunnelUrl;
                            this.minecraft.execute(() -> {
                                this.activeLanUrl = url;
                                connectToMCloudTunnel(url);
                            });
                            return;
                        }
                    }

                    this.minecraft.execute(() -> {
                        this.actionButton.active = true;
                        this.feedbackText = Component.literal("Timed out waiting for LAN. You can try again.").withStyle(ChatFormatting.RED);
                    });
                } catch (Exception e) {
                    this.minecraft.execute(() -> {
                        this.actionButton.active = true;
                        this.feedbackText = Component.literal("Failed: " + e.getMessage()).withStyle(ChatFormatting.RED);
                    });
                }
            }, "MCloudSync-JoinRequestPoller");
            poller.setDaemon(true);
            poller.start();
        }

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            this.renderBackground(graphics, mouseX, mouseY, partialTick);
            super.render(graphics, mouseX, mouseY, partialTick);
            int centerX = this.width / 2;
            int centerY = this.height / 2;

            graphics.drawCenteredString(this.font, this.title, centerX, centerY - 65, 0xFFAA00);
            graphics.drawCenteredString(this.font,
                    Component.literal(this.holderName + " is currently playing this world!").withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD),
                    centerX, centerY - 38, 0xFFFFFF);
            graphics.drawCenteredString(this.font,
                    Component.literal("To prevent world corruption, Singleplayer access is locked.").withStyle(ChatFormatting.GRAY),
                    centerX, centerY - 20, 0xAAAAAA);

            if (this.feedbackText != null && !this.feedbackText.getString().isEmpty()) {
                List<FormattedCharSequence> lines = this.font.split(this.feedbackText, Math.min(320, this.width - 40));
                int textY = centerY + 70;
                for (FormattedCharSequence line : lines) {
                    graphics.drawCenteredString(this.font, line, centerX, textY, 0xFFFFFF);
                    textY += 12;
                }
            }
        }
    }

    // =========================================================================
    // 5. STALE LOCK WARNING SCREEN (Friend disconnected unexpectedly / crashed)
    // =========================================================================
    public static class StaleLockWarningScreen extends Screen {
        private final Screen parent;
        private final Path worldDir;
        private final String levelId;
        private final Runnable onProceed;
        private final String holderName;

        public StaleLockWarningScreen(Screen parent, Path worldDir, String levelId, Runnable onProceed, String holderName) {
            super(Component.literal("MCloud Sync — Friend Disconnected Unexpectedly"));
            this.parent = parent;
            this.worldDir = worldDir;
            this.levelId = levelId;
            this.onProceed = onProceed;
            this.holderName = holderName != null && !holderName.isBlank() ? holderName : "Your Friend";
        }

        @Override
        protected void init() {
            int centerX = this.width / 2;
            int centerY = this.height / 2;

            this.addRenderableWidget(Button.builder(
                    Component.literal("Wait / Back to World List"),
                    btn -> this.minecraft.setScreen(this.parent)
            ).bounds(centerX - 140, centerY + 18, 280, 20).build());

            this.addRenderableWidget(Button.builder(
                    Component.literal("Take Over World Now (Auto-Merge Later)").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
                    btn -> this.minecraft.setScreen(new SyncingWorldScreen(this.parent, this.worldDir, this.levelId, this.onProceed, true))
            ).bounds(centerX - 140, centerY + 44, 280, 20).build());
        }

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            this.renderBackground(graphics, mouseX, mouseY, partialTick);
            super.render(graphics, mouseX, mouseY, partialTick);
            int centerX = this.width / 2;
            int centerY = this.height / 2;

            graphics.drawCenteredString(this.font, this.title, centerX, centerY - 65, 0xFFAA00);
            graphics.drawCenteredString(this.font,
                    Component.literal(this.holderName + " lost internet or crashed while playing!").withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD),
                    centerX, centerY - 40, 0xFFFFFF);
            graphics.drawCenteredString(this.font,
                    Component.literal("Their latest progress hasn't uploaded yet. Wait for them to reconnect,").withStyle(ChatFormatting.GRAY),
                    centerX, centerY - 22, 0xAAAAAA);
            graphics.drawCenteredString(this.font,
                    Component.literal("or take over now (changes will smart-merge later).").withStyle(ChatFormatting.GRAY),
                    centerX, centerY - 10, 0xAAAAAA);
        }
    }
}
