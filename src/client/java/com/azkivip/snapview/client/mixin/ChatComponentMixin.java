package com.azkivip.snapview.client.mixin;

import com.azkivip.snapview.client.chat.ScreenshotChatHandler;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts the vanilla "screenshot.success" chat message and suppresses it.
 * <p>
 * The vanilla screenshot code calls ChatComponent.addClientSystemMessage()
 * with a Component.translatable("screenshot.success", filename). This does
 * NOT fire ClientReceiveMessageEvents (that event only covers server-originated
 * messages), so the only way to intercept it is via a mixin.
 * <p>
 * When we detect the vanilla screenshot message, we cancel it. The
 * ScreenshotWatcher (which polls the filesystem) will then send our custom
 * [Copy] [Open Gallery] message instead.
 */
@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {

    @Inject(method = "addClientSystemMessage", at = @At("HEAD"), cancellable = true)
    private void snapview$onClientSystemMessage(Component message, CallbackInfo ci) {
        if (ScreenshotChatHandler.isVanillaScreenshotMessage(message)) {
            // Suppress the vanilla message. The ScreenshotWatcher will send
            // our custom [Copy] [Open Gallery] message when it detects the
            // new file on disk (typically within 0.5 seconds).
            ci.cancel();
        }
    }
}
