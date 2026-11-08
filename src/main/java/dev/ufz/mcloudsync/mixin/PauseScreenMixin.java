package dev.ufz.mcloudsync.mixin;

import dev.ufz.mcloud.tunnel.CloudflaredManager;
import dev.ufz.mcloudsync.sync.ActiveWorldSessionManager;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Injects a 1-click MCloud LAN button into the Pause Menu (top-right corner)
 * for Singleplayer worlds synchronized with MCloudSync.
 */
@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin extends Screen {

    @Unique
    private Button mcloud$lanButton;

    protected PauseScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init()V", at = @At("RETURN"))
    private void mcloudsync$addPauseLanButton(CallbackInfo ci) {
        if (this.minecraft == null || !this.minecraft.hasSingleplayerServer() || ActiveWorldSessionManager.getCurrentWorldDir() == null) {
            return;
        }

        int btnWidth = 128;
        int btnHeight = 20;
        int btnX = this.width - 135;
        int btnY = 6;

        boolean running = CloudflaredManager.isRunning();
        Component label = running
                ? Component.literal("MCloud LAN: ONLINE").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)
                : Component.literal("Open MCloud LAN").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);

        this.mcloud$lanButton = Button.builder(label, btn -> {
            if (CloudflaredManager.isRunning()) {
                String url = CloudflaredManager.getActiveUrl();
                if (url != null && this.minecraft != null) {
                    this.minecraft.keyboardHandler.setClipboard(url);
                    try {
                        this.minecraft.getToasts().addToast(new SystemToast(
                                SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                                Component.literal("MCloud LAN URL Copied!").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD),
                                Component.literal(url).withStyle(ChatFormatting.WHITE)
                        ));
                    } catch (Throwable ignored) {
                    }
                }
            } else {
                btn.setMessage(Component.literal("Opening MCloud LAN...").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));
                btn.active = false;
                if (this.minecraft != null) {
                    ActiveWorldSessionManager.openWorldToMCloudLan(this.minecraft);
                }
            }
        }).bounds(btnX, btnY, btnWidth, btnHeight).build();

        this.addRenderableWidget(this.mcloud$lanButton);
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void mcloudsync$onRender(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (this.mcloud$lanButton != null) {
            boolean running = CloudflaredManager.isRunning();
            if (running && !this.mcloud$lanButton.active) {
                this.mcloud$lanButton.setMessage(Component.literal("MCloud LAN: ONLINE").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
                this.mcloud$lanButton.active = true;
            }
        }
    }
}
