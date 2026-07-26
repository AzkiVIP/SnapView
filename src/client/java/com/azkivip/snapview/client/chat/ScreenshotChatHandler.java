package com.azkivip.snapview.client.chat;

import com.azkivip.snapview.client.screenshot.ScreenshotEntry;
import com.azkivip.snapview.client.screenshot.ScreenshotRepository;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;

/**
 * Replaces Minecraft's default "Saved screenshot as ..." chat message with
 * a minimal two-action message (Copy / Open Screenshot Menu).
 * <p>
 * Deliberately does NOT mixin into the internal screenshot-saving class.
 * Instead:
 * <ol>
 *   <li>{@code ClientReceiveMessageEvents.ALLOW_GAME} (a stable, public
 *   Fabric API event) lets us see and cancel the vanilla message before
 *   it's displayed.</li>
 *   <li>The vanilla message is identified by its translation key,
 *   {@code "screenshot.success"} - a lang-file JSON key, which is
 *   completely independent of the Java mapping/obfuscation situation in
 *   26.1.</li>
 *   <li>The actual file is resolved by asking {@link ScreenshotRepository}
 *   for the newest file on disk, rather than by parsing the vanilla
 *   message's arguments - screenshot save + chat message happen
 *   synchronously in vanilla, so this is reliable.</li>
 * </ol>
 * <p>
 * NOTE FOR REVIEW: {@code Component}/{@code ClickEvent} package location
 * and the {@code ClickEvent.RunCommand} record shape reflect the
 * long-standing Mojang mapping naming; not compiled against the real
 * 26.1.2 jar in this environment. Double check this file if the build
 * fails here.
 */
public final class ScreenshotChatHandler {

        private static final String SCREENSHOT_TRANSLATION_KEY = "screenshot.success";

        private final ScreenshotRepository repository;

        public ScreenshotChatHandler(ScreenshotRepository repository) {
                this.repository = repository;
        }

        public void register() {
                ClientReceiveMessageEvents.ALLOW_GAME.register(this::onGameMessage);
        }

        private boolean onGameMessage(Component message, boolean overlay) {
                if (overlay || !isVanillaScreenshotMessage(message)) {
                        return true; // Not our message - let it through unchanged.
                }

                ScreenshotEntry newest = repository.findNewestOnDisk();
                if (newest == null) {
                        return true; // Couldn't resolve a file; fall back to vanilla behaviour rather than show a broken message.
                }

                sendScreenshotSavedMessage(newest);
                return false; // Suppress the vanilla message.
        }

        /**
         * Checks whether a Component is the vanilla "screenshot.success" message.
         * Public static so it can be called from a mixin (ChatComponentMixin)
         * that intercepts ChatComponent.addClientSystemMessage to suppress the
         * vanilla screenshot message.
         */
        public static boolean isVanillaScreenshotMessage(Component message) {
                if (message.getContents() instanceof TranslatableContents translatable) {
                        return SCREENSHOT_TRANSLATION_KEY.equals(translatable.getKey());
                }
                // Defensive fallback in case the translation-key check ever misses:
                // still never crashes, just declines to intercept.
                return false;
        }

        /**
         * Sends the custom screenshot-saved message with [Copy] and [Open Gallery]
         * click actions. Public so it can be called from ScreenshotWatcher when a
         * new screenshot file is detected on disk.
         */
        public void sendScreenshotSavedMessage(ScreenshotEntry entry) {
                String escapedName = entry.fileName().replace("\"", "\\\"");

                MutableComponent copyAction = Component.translatable("snapview.chat.copy")
                                .withStyle(style -> style.withClickEvent(
                                                new ClickEvent.RunCommand("/snapview copy \"" + escapedName + "\"")));

                MutableComponent galleryAction = Component.translatable("snapview.chat.gallery")
                                .withStyle(style -> style.withClickEvent(
                                                new ClickEvent.RunCommand("/snapview gallery")));

                MutableComponent fullMessage = Component.translatable("snapview.chat.saved")
                                .append(Component.literal("  "))
                                .append(copyAction)
                                .append(Component.literal("  "))
                                .append(galleryAction);

                Minecraft.getInstance().gui.getChat().addClientSystemMessage(fullMessage);
        }
}
