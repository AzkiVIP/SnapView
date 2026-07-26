package com.azkivip.snapview.client;

import com.azkivip.snapview.client.chat.ScreenshotChatHandler;
import com.azkivip.snapview.client.clipboard.ClipboardService;
import com.azkivip.snapview.client.gui.GalleryScreen;
import com.azkivip.snapview.client.screenshot.ScreenshotEntry;
import com.azkivip.snapview.client.screenshot.ScreenshotRepository;
import com.azkivip.snapview.client.thumbnail.ThumbnailDiskCache;
import com.azkivip.snapview.client.thumbnail.ThumbnailLoader;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Optional;

public final class SnapViewClient implements ClientModInitializer {

    public static final String MOD_ID = "snapview";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static KeyMapping openGalleryKey;

    private ScreenshotRepository repository;
    private ThumbnailDiskCache diskCache;
    private ThumbnailLoader thumbnailLoader;

    @Override
    public void onInitializeClient() {
        Path gameDir = Minecraft.getInstance().gameDirectory.toPath();
        Path screenshotsDir = gameDir.resolve("screenshots");
        Path cacheDir = gameDir.resolve("snapview_cache").resolve("thumbnails");

        repository = new ScreenshotRepository(screenshotsDir);
        diskCache = new ThumbnailDiskCache(cacheDir);
        thumbnailLoader = new ThumbnailLoader(diskCache);

        new ScreenshotChatHandler(repository).register();

        // In 26.1 the KeyMapping category is a KeyMapping.Category record
        // keyed by an Identifier, not a free-form String.
        KeyMapping.Category snapviewCategory = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath(MOD_ID, MOD_ID));

        openGalleryKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.snapview.open_gallery",
                GLFW.GLFW_KEY_UNKNOWN,
                snapviewCategory
        ));

        registerCommands();
        registerTickHandler();

        LOGGER.info("SnapView initialized (screenshots dir: {})", screenshotsDir);
    }

    private void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommands.literal("snapview")
                        .then(ClientCommands.literal("gallery")
                                .executes(context -> {
                                    openGallery();
                                    return 1;
                                }))
                        .then(ClientCommands.literal("copy")
                                .then(ClientCommands.argument("file", StringArgumentType.greedyString())
                                        .executes(context -> {
                                            copyByFileName(StringArgumentType.getString(context, "file"));
                                            return 1;
                                        })))
        ));
    }

    private void registerTickHandler() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (openGalleryKey != null && openGalleryKey.consumeClick()) {
                openGallery();
            }
        });
    }

    private void openGallery() {
        Minecraft client = Minecraft.getInstance();
        Screen current = client.screen;
        client.setScreen(new GalleryScreen(current, repository, thumbnailLoader));
    }

    private void copyByFileName(String fileName) {
        // Strip surrounding quotes if present. The chat click-event command
        // wraps the filename in quotes, and greedyString captures them
        // literally. Without stripping, path resolve fails with
        // "Illegal char <"> at index 0".
        if (fileName.length() >= 2 && fileName.startsWith("\"") && fileName.endsWith("\"")) {
            fileName = fileName.substring(1, fileName.length() - 1);
        }
        fileName = fileName.replace("\\\"", "\"");

        final String resolvedFileName = fileName;

        Optional<ScreenshotEntry> entry = repository.entries().stream()
                .filter(e -> e.fileName().equals(resolvedFileName))
                .findFirst();

        ScreenshotEntry target = entry.orElseGet(() -> {
            Path direct = repository.screenshotsDirectory().resolve(resolvedFileName);
            return java.nio.file.Files.exists(direct) ? ScreenshotEntry.of(direct) : null;
        });

        if (target == null) {
            LOGGER.warn("Copy: screenshot not found: {}", resolvedFileName);
            return;
        }

        ClipboardService.copyImageAsync(target.path(), Minecraft.getInstance()::execute, success -> {
            Minecraft client = Minecraft.getInstance();
            if (client.player == null) {
                return;
            }
            Component feedback = Component.translatable(
                    success ? "snapview.clipboard.copied" : "snapview.clipboard.failed");
            // ChatComponent#addMessage is private in 26.1; use addClientSystemMessage.
            client.gui.getChat().addClientSystemMessage(feedback);
        });
    }
}
