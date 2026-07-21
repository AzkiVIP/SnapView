package com.azkivip.snapview.client.gui;

import com.azkivip.snapview.client.clipboard.ClipboardService;
import com.azkivip.snapview.client.screenshot.ScreenshotEntry;
import com.azkivip.snapview.client.screenshot.ScreenshotRepository;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Shows a single screenshot at (up to) full resolution, with previous/next
 * navigation and the rename/copy actions.
 * <p>
 * Unlike the gallery grid, this screen only ever has at most one full-size
 * texture loaded at a time - the previous image's texture is released
 * before the next one is decoded, so browsing through hundreds of
 * screenshots here still can't accumulate memory.
 */
public final class ScreenshotViewerScreen extends Screen {

	private final Screen parent;
	private final ScreenshotRepository repository;
	private final List<ScreenshotEntry> entries;
	private int index;

	private Identifier fullImageTextureId;
	private int imageWidth;
	private int imageHeight;
	private boolean loadFailed;

	private Component statusMessage = Component.empty();

	public ScreenshotViewerScreen(Screen parent, ScreenshotRepository repository,
	                               List<ScreenshotEntry> entries, int index) {
		super(Component.literal(""));
		this.parent = parent;
		this.repository = repository;
		this.entries = entries;
		this.index = index;
	}

	@Override
	protected void init() {
		loadCurrentImage();

		int barY = this.height - 28;
		addRenderableWidget(Button.builder(Component.translatable("snapview.viewer.previous"),
						b -> navigate(-1)).bounds(8, barY, 70, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("snapview.viewer.next"),
						b -> navigate(1)).bounds(84, barY, 70, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("snapview.viewer.copy"),
						b -> copyCurrent()).bounds(this.width - 160, barY, 70, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("snapview.viewer.rename"),
						b -> renameCurrent()).bounds(this.width - 84, barY, 76, 20).build());
	}

	private void navigate(int delta) {
		int newIndex = index + delta;
		if (newIndex < 0 || newIndex >= entries.size()) {
			return;
		}
		releaseTexture();
		index = newIndex;
		loadCurrentImage();
	}

	private ScreenshotEntry current() {
		return entries.get(index);
	}

	private void loadCurrentImage() {
		loadFailed = false;
		ScreenshotEntry entry = current();

		// Full-res decode happens off-thread even here, so opening the viewer
		// never stalls a frame - the screen simply shows a blank frame for
		// one or two frames while the texture uploads.
		java.util.concurrent.CompletableFuture
				.supplyAsync(() -> readImage(entry))
				.thenAcceptAsync(this::onImageLoaded, Minecraft.getInstance()::execute);
	}

	private BufferedImage readImage(ScreenshotEntry entry) {
		try {
			return ImageIO.read(entry.path().toFile());
		} catch (Exception e) {
			return null;
		}
	}

	private void onImageLoaded(BufferedImage image) {
		if (image == null) {
			loadFailed = true;
			return;
		}
		if (entries.get(index) != current()) {
			return; // User navigated away before this finished loading.
		}

		com.mojang.blaze3d.platform.NativeImage nativeImage =
				new com.mojang.blaze3d.platform.NativeImage(
						com.mojang.blaze3d.platform.NativeImage.Format.RGBA,
						image.getWidth(), image.getHeight(), false);
		for (int y = 0; y < image.getHeight(); y++) {
			for (int x = 0; x < image.getWidth(); x++) {
				int argb = image.getRGB(x, y);
				int a = (argb >>> 24) & 0xFF, r = (argb >>> 16) & 0xFF, g = (argb >>> 8) & 0xFF, b = argb & 0xFF;
				nativeImage.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
			}
		}

		fullImageTextureId = Identifier.fromNamespaceAndPath("snapview", "viewer/full");
		Minecraft.getInstance().getTextureManager().register(fullImageTextureId, new DynamicTexture(nativeImage));
		imageWidth = image.getWidth();
		imageHeight = image.getHeight();
	}

	private void releaseTexture() {
		if (fullImageTextureId != null) {
			Minecraft.getInstance().getTextureManager().release(fullImageTextureId);
			fullImageTextureId = null;
		}
	}

	@Override
	public void onClose() {
		releaseTexture();
		this.minecraft.setScreen(parent);
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.render(graphics, mouseX, mouseY, partialTick);

		if (loadFailed) {
			graphics.drawCenteredString(this.font, "Could not load image", this.width / 2, this.height / 2, 0xFF5555);
		} else if (fullImageTextureId != null) {
			// Fit the image within the available viewport while preserving aspect ratio.
			int maxW = this.width - 40;
			int maxH = this.height - 80;
			double scale = Math.min(maxW / (double) imageWidth, maxH / (double) imageHeight);
			scale = Math.min(scale, 1.0);
			int drawW = (int) (imageWidth * scale);
			int drawH = (int) (imageHeight * scale);
			int drawX = (this.width - drawW) / 2;
			int drawY = (this.height - drawH) / 2 - 10;

			graphics.blit(fullImageTextureId, drawX, drawY, 0, 0, drawW, drawH, drawW, drawH);
		}

		graphics.drawCenteredString(this.font, current().fileName(), this.width / 2, 8, 0xFFFFFF);
		if (!statusMessage.getString().isEmpty()) {
			graphics.drawCenteredString(this.font, statusMessage, this.width / 2, this.height - 40, 0xAAFFAA);
		}
	}

	private void copyCurrent() {
		ClipboardService.copyImageAsync(current().path(), Minecraft.getInstance()::execute, success ->
				statusMessage = Component.translatable(success ? "snapview.clipboard.copied" : "snapview.clipboard.failed"));
	}

	private void renameCurrent() {
		this.minecraft.setScreen(new RenameScreen(this, repository, current(), renamed -> {
			entries.set(index, renamed);
			statusMessage = Component.translatable("snapview.rename.success", renamed.fileName());
		}));
	}
}
