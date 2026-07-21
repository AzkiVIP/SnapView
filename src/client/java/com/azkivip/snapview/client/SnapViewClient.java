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
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
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

		openGalleryKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.snapview.open_gallery",
				GLFW.GLFW_KEY_UNKNOWN, // Unbound by default; players opt in via Controls.
				"key.categories.snapview"
		));

		registerCommands();
		registerTickHandler();

		LOGGER.info("SnapView initialized (screenshots dir: {})", screenshotsDir);
	}

	private void registerCommands() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
				ClientCommandManager.literal("snapview")
						.then(ClientCommandManager.literal("gallery")
								.executes(context -> {
									openGallery();
									return 1;
								}))
						.then(ClientCommandManager.literal("copy")
								.then(ClientCommandManager.argument("file", StringArgumentType.greedyString())
										.executes(context -> {
											copyByFileName(StringArgumentType.getString(context, "file"));
											return 1;
										})))
		));
	}

	private void registerTickHandler() {
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
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
		Optional<ScreenshotEntry> entry = repository.entries().stream()
				.filter(e -> e.fileName().equals(fileName))
				.findFirst();

		// The chat click-handler fires before a directory refresh necessarily
		// ran, so also fall back to resolving straight off disk.
		ScreenshotEntry target = entry.orElseGet(() -> {
			Path direct = repository.screenshotsDirectory().resolve(fileName);
			return java.nio.file.Files.exists(direct) ? ScreenshotEntry.of(direct) : null;
		});

		if (target == null) {
			return;
		}

		ClipboardService.copyImageAsync(target.path(), Minecraft.getInstance()::execute, success -> {
			Minecraft client = Minecraft.getInstance();
			if (client.player == null) {
				return;
			}
			Component feedback = Component.translatable(
					success ? "snapview.clipboard.copied" : "snapview.clipboard.failed");
			client.gui.getChat().addMessage(feedback);
		});
	}
}
