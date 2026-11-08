package dev.ufz.mcloudsync.mixin;

import dev.ufz.mcloudsync.client.MCloudSyncScreens;
import dev.ufz.mcloudsync.sync.ActiveWorldSessionManager;
import dev.ufz.mcloudsync.sync.WorldSyncMetadata;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Path;

/**
 * Injects a seamless Singleplayer Host Handoff button into DisconnectedScreen
 * when a guest player is disconnected from an MCloud LAN session.
 */
@Mixin(DisconnectedScreen.class)
public abstract class DisconnectedScreenMixin extends Screen {

    protected DisconnectedScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init()V", at = @At("RETURN"))
    private void mcloudsync$addContinueInSingleplayerButton(CallbackInfo ci) {
        Path worldDir = ActiveWorldSessionManager.getLastJoinedLanWorldDir();
        if (worldDir == null || !WorldSyncMetadata.isSyncedWorld(worldDir)) {
            return;
        }

        int btnWidth = 250;
        int btnHeight = 20;
        int btnX = this.width / 2 - btnWidth / 2;
        int btnY = this.height - 32;

        Component label = Component.literal("Continue in Singleplayer (Sync & Host)")
                .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD);

        Button continueButton = Button.builder(label, btn -> {
            ActiveWorldSessionManager.setLastJoinedLanWorldDir(null);
            if (this.minecraft == null) {
                return;
            }

            String levelId = worldDir.getFileName().toString();
            Screen parentScreen = new SelectWorldScreen(new TitleScreen());

            this.minecraft.setScreen(new MCloudSyncScreens.SyncingWorldScreen(
                    parentScreen,
                    worldDir,
                    levelId,
                    () -> {
                        WorldOpenFlowsMixin.markLevelPrepared(levelId);
                        this.minecraft.createWorldOpenFlows().openWorld(
                                levelId,
                                () -> this.minecraft.setScreen(new SelectWorldScreen(new TitleScreen()))
                        );
                    },
                    true // forceTakeoverStale = true enables automatic wait & retries while previous host finishes saving
            ));
        }).bounds(btnX, btnY, btnWidth, btnHeight).build();

        this.addRenderableWidget(continueButton);
    }
}
