package dev.ufz.mcloudsync.mixin;

import dev.ufz.mcloudsync.client.MCloudSyncScreens;
import dev.ufz.mcloudsync.sync.WorldSyncMetadata;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Intercepts Singleplayer world opening in `WorldOpenFlows`.
 * Only activates if the target world directory contains `.mcloud-sync.json` (strict opt-in).
 * Asynchronously synchronizes the world via `SyncingWorldScreen` with progress feedback,
 * preventing UI thread freezes, and redirecting to `WorldLockedScreen` if a friend is in-game.
 */
@Mixin(WorldOpenFlows.class)
public class WorldOpenFlowsMixin {

    @Shadow
    @Final
    private Minecraft minecraft;

    @Unique
    private static final Set<String> mcloudsync$preparedLevels = Collections.synchronizedSet(new HashSet<>());

    @Inject(
            method = "openWorld(Ljava/lang/String;Ljava/lang/Runnable;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void mcloudsync$onOpenWorld(String levelId, Runnable onCancel, CallbackInfo ci) {
        Path worldDir = this.minecraft.getLevelSource().getBaseDir().resolve(levelId);
        if (!WorldSyncMetadata.isSyncedWorld(worldDir)) {
            return;
        }

        // If this level was already prepared asynchronously by our SyncingWorldScreen, proceed into vanilla loading!
        if (mcloudsync$preparedLevels.remove(levelId)) {
            return;
        }

        UUID playerUuid = this.minecraft.getUser().getProfileId();
        String playerName = this.minecraft.getUser().getName();
        Screen currentScreen = this.minecraft.screen;

        // Cancel the synchronous call to prevent render thread freeze and open the smooth progress screen
        ci.cancel();

        this.minecraft.setScreen(new MCloudSyncScreens.SyncingWorldScreen(
                currentScreen,
                worldDir,
                levelId,
                playerUuid,
                playerName,
                () -> {
                    mcloudsync$preparedLevels.add(levelId);
                    this.minecraft.createWorldOpenFlows().openWorld(levelId, onCancel);
                }
        ));
    }
}
