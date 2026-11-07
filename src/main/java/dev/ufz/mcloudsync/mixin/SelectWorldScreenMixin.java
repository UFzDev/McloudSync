package dev.ufz.mcloudsync.mixin;

import dev.ufz.mcloudsync.client.MCloudSyncScreens;
import dev.ufz.mcloudsync.sync.WorldSyncMetadata;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelSummary;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Path;

/**
 * Enhances the Singleplayer World Selection screen:
 * 1. Top-right button: "Link MCloud World" to clone/download a friend's shared world via invite token.
 * 2. Top-left button: "MCloud Sync / Share World" that updates dynamically with the selected world,
 *    allowing 1-click sharing of an existing local world or viewing its invite token & sync status.
 */
@Mixin(SelectWorldScreen.class)
public abstract class SelectWorldScreenMixin extends Screen {

    @Unique
    private Button mcloud$actionButton;

    @Unique
    private LevelSummary mcloud$selectedSummary;

    protected SelectWorldScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init()V", at = @At("RETURN"))
    private void mcloudsync$addButtons(CallbackInfo ci) {
        // 1. Link MCloud World button in top-right
        int linkBtnWidth = 115;
        int linkBtnHeight = 20;
        int linkX = this.width - linkBtnWidth - 8;
        int linkY = 6;

        Button linkBtn = Button.builder(
                Component.literal("Link MCloud World").withStyle(ChatFormatting.GOLD),
                btn -> {
                    if (this.minecraft != null) {
                        this.minecraft.setScreen(new MCloudSyncScreens.LinkSharedWorldScreen(this));
                    }
                }
        ).bounds(linkX, linkY, linkBtnWidth, linkBtnHeight).build();
        this.addRenderableWidget(linkBtn);

        // 2. MCloud Sync / Share World action button in top-left
        int actionBtnWidth = 120;
        int actionBtnHeight = 20;
        int actionX = 8;
        int actionY = 6;

        this.mcloud$actionButton = Button.builder(
                Component.literal("MCloud Sync").withStyle(ChatFormatting.GRAY),
                btn -> {
                    if (this.minecraft != null) {
                        this.minecraft.setScreen(new MCloudSyncScreens.WorldSyncManagerScreen(this, this.mcloud$selectedSummary));
                    }
                }
        ).bounds(actionX, actionY, actionBtnWidth, actionBtnHeight).build();
        this.addRenderableWidget(this.mcloud$actionButton);

        mcloudsync$refreshActionButton();
    }

    @Inject(method = "updateButtonStatus", at = @At("RETURN"))
    private void mcloudsync$onUpdateButtonStatus(LevelSummary summary, CallbackInfo ci) {
        this.mcloud$selectedSummary = summary;
        mcloudsync$refreshActionButton();
    }

    @Unique
    private void mcloudsync$refreshActionButton() {
        if (this.mcloud$actionButton == null) {
            return;
        }

        if (this.mcloud$selectedSummary == null) {
            this.mcloud$actionButton.setMessage(Component.literal("MCloud Sync").withStyle(ChatFormatting.GRAY));
            return;
        }

        try {
            if (this.minecraft != null && this.minecraft.getLevelSource() != null) {
                Path worldDir = this.minecraft.getLevelSource().getBaseDir().resolve(this.mcloud$selectedSummary.getLevelId());
                if (WorldSyncMetadata.isSyncedWorld(worldDir)) {
                    this.mcloud$actionButton.setMessage(Component.literal("MCloud: Synced").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
                    return;
                }
            }
        } catch (Throwable ignored) {
        }

        this.mcloud$actionButton.setMessage(Component.literal("Share via MCloud").withStyle(ChatFormatting.GOLD));
    }
}
