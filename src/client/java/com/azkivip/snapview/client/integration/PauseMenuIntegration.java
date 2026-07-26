package com.azkivip.snapview.client.integration;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Adds a "Screenshot Gallery" button to the pause menu (Game Menu / Pause Screen).
 * <p>
 * Uses Fabric's ScreenEvents.AFTER_INIT to inject the button after vanilla
 * has laid out its own buttons. The button is placed below the existing
 * menu items and matches vanilla's button width/style.
 */
public final class PauseMenuIntegration {

    private PauseMenuIntegration() {
    }

    /**
     * Registers the pause-menu integration. Call once during client init.
     */
    public static void register() {
        ScreenEvents.AFTER_INIT.register(PauseMenuIntegration::onScreenInit);
    }

    private static void onScreenInit(Minecraft client, Screen screen, int scaledWidth, int scaledHeight) {
        // The pause menu class name in 26.1 Mojang mappings is "PauseScreen".
        // Some older mappings called it "GameMenuScreen".
        String className = screen.getClass().getSimpleName();
        if (!isPauseMenu(className)) {
            return;
        }

        // Add our button below the existing menu buttons.
        // Vanilla pause menu buttons are 200 wide, centered, stacked vertically.
        var buttons = Screens.getWidgets(screen);
        if (buttons.isEmpty()) {
            return;
        }

        // Find the lowest existing button to place ours below it
        int lowestY = 0;
        for (var btn : buttons) {
            int btnBottom = btn.getY() + btn.getHeight();
            if (btnBottom > lowestY) {
                lowestY = btnBottom;
            }
        }

        int buttonY = lowestY + 8;
        int buttonX = (scaledWidth - 200) / 2;

        // Don't add if the button would go off-screen
        if (buttonY + 20 > scaledHeight - 10) {
            buttonY = scaledHeight - 30;
        }

        Button galleryButton = Button.builder(
                        Component.translatable("snapview.pausemenu.gallery"),
                        button -> openGallery())
                .bounds(buttonX, buttonY, 200, 20)
                .build();

        // Avoid adding duplicates if init() is called multiple times
        String galleryLabel = Component.translatable("snapview.pausemenu.gallery").getString();
        boolean alreadyExists = false;
        for (var btn : buttons) {
            if (btn.getMessage().getString().equals(galleryLabel)) {
                alreadyExists = true;
                break;
            }
        }

        if (!alreadyExists) {
            buttons.add(galleryButton);
        }
    }

    private static boolean isPauseMenu(String className) {
        return "PauseScreen".equals(className) || "GameMenuScreen".equals(className);
    }

    private static void openGallery() {
        // Defer to the SnapViewClient's gallery opener
        com.azkivip.snapview.client.SnapViewClient.openGalleryFromPauseMenu();
    }
}
