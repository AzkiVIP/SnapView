package com.azkivip.snapview.client.gui;

import com.azkivip.snapview.client.clipboard.ClipboardService;
import com.azkivip.snapview.client.screenshot.ScreenshotEntry;
import com.azkivip.snapview.client.screenshot.ScreenshotRepository;
import com.azkivip.snapview.client.thumbnail.ThumbnailTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

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

        // Previous / Next
        addRenderableWidget(Button.builder(Component.translatable("snapview.viewer.previous"),
                        b -> navigate(-1)).bounds(74, barY, 60, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("snapview.viewer.next"),
                        b -> navigate(1)).bounds(140, barY, 60, 20).build());

        // Delete, Copy, Rename on the right
        int rightBtnsRight = this.width - 8;
        addRenderableWidget(Button.builder(Component.translatable("snapview.viewer.rename"),
                        b -> renameCurrent()).bounds(rightBtnsRight - 76, barY, 76, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("snapview.viewer.copy"),
                        b -> copyCurrent()).bounds(rightBtnsRight - 76 - 4 - 70, barY, 70, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("snapview.viewer.delete"),
                        b -> deleteCurrent()).bounds(rightBtnsRight - 76 - 4 - 70 - 4 - 70, barY, 70, 20).build());
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
        final ScreenshotEntry entry = current();
        final java.nio.file.Path path = entry.path();

        // Upload the texture on the client thread (GPU operations must be on
        // the render thread). We read the file from disk synchronously here
        // but NativeImage.read is fast for PNG files.
        // Actually, to avoid blocking the render thread, we read the NativeImage
        // on a background thread, but we must upload it on the client thread.
        // NativeImage.read itself is safe to call off-thread.
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return com.mojang.blaze3d.platform.NativeImage.read(
                                java.nio.file.Files.newInputStream(path));
                    } catch (Exception e) {
                        return null;
                    }
                })
                .thenAcceptAsync(nativeImage -> onImageLoaded(nativeImage, path), Minecraft.getInstance()::execute);
    }

    private void onImageLoaded(com.mojang.blaze3d.platform.NativeImage nativeImage, java.nio.file.Path path) {
        isLoading = false;
        if (nativeImage == null) {
            loadFailed = true;
            return;
        }

        releaseTexture();

        try {
            int width = nativeImage.getWidth();
            int height = nativeImage.getHeight();
            currentTexture = ThumbnailTexture.uploadNativeImage(
                    "viewer_" + index + "_" + System.nanoTime(), nativeImage);
            imageWidth = width;
            imageHeight = height;
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
        // ESC or Back button -> return to the parent (GalleryScreen).
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
            // Fit the image within the available viewport while preserving aspect ratio.
            // Allow upscaling for small images so they don't appear tiny in the center
            // of a large screen — but cap at 2x to avoid blurry pixelation.
            int maxW = this.width - 40;
            int maxH = this.height - 80;
            double scale = Math.min(maxW / (double) imageWidth, maxH / (double) imageHeight);
            // Allow upscaling up to 2x for small images; no cap for large images
            // (scale is already < 1 in that case).
            scale = Math.min(scale, 2.0);
            int drawW = Math.max(1, (int) Math.round(imageWidth * scale));
            int drawH = Math.max(1, (int) Math.round(imageHeight * scale));
            // Center horizontally and vertically (accounting for the top filename
            // bar and the bottom button bar).
            int drawX = (this.width - drawW) / 2;
            int drawY = (this.height - drawH) / 2 - 10;

            // blit(Identifier, x, y, w, h, u0, v0, u1, v1) - UV in 0..1 space.
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

    private void deleteCurrent() {
        // Show a confirmation dialog before deleting
        ScreenshotEntry entry = current();
        this.minecraft.setScreen(new ConfirmScreen(
                confirmed -> {
                    if (confirmed) {
                        performDelete(entry);
                    } else {
                        // Return to viewer
                        this.minecraft.setScreen(this);
                    }
                },
                Component.translatable("snapview.viewer.delete"),
                Component.translatable("snapview.viewer.delete.confirm", entry.fileName())
        ));
    }

    private void performDelete(ScreenshotEntry entry) {
        try {
            // Delete the actual screenshot file
            java.nio.file.Files.deleteIfExists(entry.path());

            // Delete the thumbnail cache entries for this file (all sizes)
            var client = com.azkivip.snapview.client.SnapViewClient.getInstance();
            if (client != null && client.getDiskCache() != null) {
                client.getDiskCache().deleteForEntry(entry);
            }

            // Remove from repository
            repository.removeEntry(entry);
            entries.remove(entry);

            // Navigate away or close
            if (entries.isEmpty()) {
                // No more screenshots — return to parent (gallery)
                onClose();
            } else {
                // Adjust index and reload
                if (index >= entries.size()) {
                    index = entries.size() - 1;
                }
                releaseTexture();
                loadCurrentImage();
                statusMessage = Component.translatable("snapview.viewer.deleted");
            }
        } catch (Exception e) {
            statusMessage = Component.translatable("snapview.viewer.deletefailed", e.getMessage());
        }
        this.minecraft.setScreen(this);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
