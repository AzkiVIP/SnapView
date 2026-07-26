package com.azkivip.snapview.client.gui;

import com.azkivip.snapview.client.clipboard.ClipboardService;
import com.azkivip.snapview.client.screenshot.ScreenshotEntry;
import com.azkivip.snapview.client.screenshot.ScreenshotRepository;
import com.azkivip.snapview.client.thumbnail.ThumbnailTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Shows a single screenshot at (up to) full resolution, with previous/next
 * navigation, rename, copy, and a visible Back button.
 * <p>
 * Only one full-size texture is loaded at a time. The previous image's
 * texture is released before the next one is decoded.
 */
public final class ScreenshotViewerScreen extends Screen {

    private final Screen parent;
    private final ScreenshotRepository repository;
    private final List<ScreenshotEntry> entries;
    private int index;

    private ThumbnailTexture currentTexture;
    private int imageWidth;
    private int imageHeight;
    private boolean loadFailed;
    private boolean isLoading;

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

        // Back button (always visible, leftmost)
        addRenderableWidget(Button.builder(Component.translatable("snapview.viewer.back"),
                        b -> onClose())
                .bounds(8, barY, 60, 20)
                .build());

        addRenderableWidget(Button.builder(Component.translatable("snapview.viewer.previous"),
                        b -> navigate(-1)).bounds(74, barY, 60, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("snapview.viewer.next"),
                        b -> navigate(1)).bounds(140, barY, 60, 20).build());

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
        statusMessage = Component.empty();
        loadCurrentImage();
    }

    private ScreenshotEntry current() {
        return entries.get(index);
    }

    private void loadCurrentImage() {
        loadFailed = false;
        isLoading = true;
        ScreenshotEntry entry = current();

        CompletableFuture
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
        isLoading = false;
        if (image == null) {
            loadFailed = true;
            return;
        }

        releaseTexture();

        try {
            // Reuse ThumbnailTexture.upload() — it handles the NativeImage
            // conversion, the DynamicTexture.upload() call (critical for
            // actually getting pixels onto the GPU in 26.1), and texture
            // registration. Using a unique debugName per load prevents
            // texture ID collisions.
            currentTexture = ThumbnailTexture.upload(
                    "viewer_" + index + "_" + System.nanoTime(), image);
            imageWidth = image.getWidth();
            imageHeight = image.getHeight();
        } catch (Exception e) {
            loadFailed = true;
        }
    }

    private void releaseTexture() {
        if (currentTexture != null) {
            currentTexture.close();
            currentTexture = null;
        }
    }

    @Override
    public void onClose() {
        releaseTexture();
        this.minecraft.setScreen(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        if (loadFailed) {
            graphics.centeredText(this.font, Component.translatable("snapview.viewer.loadfailed"),
                    this.width / 2, this.height / 2, 0xFF5555);
        } else if (currentTexture != null) {
            // --- Centering math (traced step by step) ---
            //
            // The viewport is the rectangle between the top filename bar and
            // the bottom button bar. We center the image within this viewport
            // while preserving aspect ratio.
            //
            // Top bar:    y = 0   .. 30  (filename + position indicator)
            // Bottom bar: y = (this.height - 28) .. this.height (buttons)
            //
            // Available viewport:
            //   viewportTop    = 32   (below the top bar)
            //   viewportBottom = this.height - 32  (above the bottom bar)
            //   viewportHeight = viewportBottom - viewportTop
            //
            // Scale: fit the image inside the viewport, preserving aspect
            // ratio. Allow upscaling up to 2x so small screenshots are
            // clearly visible (not tiny in the center of a large screen).
            // Large screenshots scale down to fit.
            //
            // Position: center horizontally and vertically within the viewport.

            int viewportTop = 32;
            int viewportBottom = this.height - 32;
            int viewportHeight = viewportBottom - viewportTop;
            int viewportWidth = this.width;

            double scaleW = (double) viewportWidth / imageWidth;
            double scaleH = (double) viewportHeight / imageHeight;
            double scale = Math.min(scaleW, scaleH);
            // Allow upscaling up to 2x for small images; no extra cap needed
            // for large images (scale is already < 1 in that case).
            scale = Math.min(scale, 2.0);

            int drawW = Math.max(1, (int) Math.round(imageWidth * scale));
            int drawH = Math.max(1, (int) Math.round(imageHeight * scale));

            // Center horizontally within the full screen width
            int drawX = (this.width - drawW) / 2;
            // Center vertically within the viewport (between top and bottom bars)
            int drawY = viewportTop + (viewportHeight - drawH) / 2;

            graphics.blit(currentTexture.id(), drawX, drawY, drawW, drawH,
                    0.0f, 0.0f, 1.0f, 1.0f);
        } else if (isLoading) {
            graphics.centeredText(this.font, Component.translatable("snapview.viewer.loading"),
                    this.width / 2, this.height / 2, 0xAAAAAA);
        }

        // Filename at top
        graphics.centeredText(this.font, current().fileName(), this.width / 2, 8, 0xFFFFFF);
        // Position indicator
        graphics.centeredText(this.font, (index + 1) + " / " + entries.size(),
                this.width / 2, 20, 0xAAAAAA);

        if (!statusMessage.getString().isEmpty()) {
            graphics.centeredText(this.font, statusMessage, this.width / 2, this.height - 40, 0xAAFFAA);
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

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
