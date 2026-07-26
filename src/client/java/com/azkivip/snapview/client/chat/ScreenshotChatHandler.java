package com.azkivip.snapview.client.chat;

import com.azkivip.snapview.client.screenshot.ScreenshotEntry;
import com.azkivip.snapview.client.screenshot.ScreenshotRepository;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;

/**
 * Sends a custom chat message with [Copy] and [Open Gallery] actions after
 * a screenshot is taken.
 * <p>
 * This is invoked by the ScreenshotWatcher when it detects a new screenshot
 * file on disk. The vanilla "screenshot.success" message is suppressed by a
 * mixin (see ChatComponentMixin) so only our custom message appears.
 * <p>
 * Why not ClientReceiveMessageEvents? That event only fires for
 * server-originated game/chat messages. The vanilla screenshot code path
 * calls ChatComponent.addClientSystemMessage() directly, which does NOT
 * fire ClientReceiveMessageEvents. The only way to intercept it is a mixin
 * on addClientSystemMessage.
 */
public final class ScreenshotChatHandler {

    private static final String SCREENSHOT_TRANSLATION_KEY = "screenshot.success";

    private final ScreenshotRepository repository;

    public ScreenshotChatHandler(ScreenshotRepository repository) {
        this.repository = repository;
    }

    /**
     * Sends the custom screenshot-saved message with click actions.
     * Called by ScreenshotWatcher when a new screenshot is detected.
     */
    public void sendScreenshotSavedMessage(ScreenshotEntry entry) {
        String escapedName = entry.fileName().replace("\"", "\\\"");

        // Green [Copy] button - uses ChatFormatting.GREEN for clear visual distinction
        MutableComponent copyAction = Component.translatable("snapview.chat.copy")
                .withStyle(style -> style
                        .withClickEvent(new ClickEvent.RunCommand("/snapview copy \"" + escapedName + "\""))
                        .withColor(net.minecraft.network.chat.TextColor.fromRgb(0x55FF55)))
                .withStyle(ChatFormatting.BOLD);

        // Gold [Open Gallery] button - distinct from Copy
        MutableComponent galleryAction = Component.translatable("snapview.chat.gallery")
                .withStyle(style -> style
                        .withClickEvent(new ClickEvent.RunCommand("/snapview gallery"))
                        .withColor(net.minecraft.network.chat.TextColor.fromRgb(0xFFAA00)))
                .withStyle(ChatFormatting.BOLD);

        MutableComponent fullMessage = Component.translatable("snapview.chat.saved")
                .append(Component.literal("  "))
                .append(copyAction)
                .append(Component.literal("  "))
                .append(galleryAction);

        Minecraft.getInstance().gui.getChat().addClientSystemMessage(fullMessage);
    }

    /**
     * Checks whether a Component is the vanilla "screenshot.success" message.
     * Used by the mixin to decide whether to suppress the vanilla message.
     */
    public static boolean isVanillaScreenshotMessage(Component message) {
        if (message.getContents() instanceof TranslatableContents translatable) {
            return SCREENSHOT_TRANSLATION_KEY.equals(translatable.getKey());
        }
        return false;
    }
}
