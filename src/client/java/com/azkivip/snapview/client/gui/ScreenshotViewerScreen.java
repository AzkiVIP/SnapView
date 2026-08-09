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
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Shows a single screenshot centered in the viewport with left/right arrow
 * navigation, plus Back/Copy/Rename/Delete buttons in a bottom bar.
 * <p>
 * Layout (top to bottom):
 * <pre>
 *      Filename (centered, top)
 *      Position indicator (e.g. "3 / 12")
 *
 *   < [          IMAGE          ] >
 *      (centered, aspect-preserved)
 *
 *      Filename (centered, above buttons)
 *      [Back] [Copy] [Rename] [Delete]
 * </pre>
 * <p>
 * Only one full-size texture is loaded at a time. The previous image's
 * texture is released before the next one is decoded.
 */
public final class ScreenshotViewerScreen extends Screen {

    private static final int ARROW_BUTTON_WIDTH = 40;
    private static final int ARROW_BUTTON_HEIGHT = 40;
    private static final int BOTTOM_BAR_Y_OFFSET = 28;
    private static final int TOP_BAR_HEIGHT = 32;
    private static final int BOTTOM_BAR_HEIGHT = 28;

    private final Screen parent;
    private final ScreenshotRepository repository;
    private final List<ScreenshotEntry> entries;
    private int index;

    private ThumbnailTexture currentTexture;
    private BufferedImage currentImage; // cached for clipboard reuse
    private int imageWidth;
    private int imageHeight;
    private boolean loadFailed;
    private boolean isLoading;

    private Component statusMessage = Component.empty();
    // Copy progress tracking — when true, an animated indicator is shown
    private boolean isCopying;
    private long copyStartTime; // System.nanoTime() when copy started

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

        int barY = this.height - BOTTOM_BAR_Y_OFFSET;

        // Bottom bar: [Back] [Copy] [Rename] [Delete]
        // Centered as a group
        int btnWidth = 70;
        int btnSpacing = 4;
        int totalButtonsWidth = btnWidth * 4 + btnSpacing * 3;
        int startX = (this.width - totalButtonsWidth) / 2;

        addRenderableWidget(Button.builder(Component.translatable("snapview.viewer.back"),
                        b -> onClose())
                .bounds(startX, barY, btnWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("snapview.viewer.copy"),
                        b -> copyCurrent())
                .bounds(startX + btnWidth + btnSpacing, barY, btnWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("snapview.viewer.rename"),
                        b -> renameCurrent())
                .bounds(startX + (btnWidth + btnSpacing) * 2, barY, btnWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("snapview.viewer.delete"),
                        b -> deleteCurrent())
                .bounds(startX + (btnWidth + btnSpacing) * 3, barY, btnWidth, 20).build());

        // Left arrow (prev) and right arrow (next) — vertically centered in
        // the image viewport, at the left and right edges.
        int arrowY = (this.height - ARROW_BUTTON_HEIGHT) / 2;
        addRenderableWidget(Button.builder(Component.literal("\u25C0"),
                        b -> navigate(-1))
                .bounds(8, arrowY, ARROW_BUTTON_WIDTH, ARROW_BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(Component.literal("\u25B6"),
                        b -> navigate(1))
                .bounds(this.width - 8 - ARROW_BUTTON_WIDTH, arrowY,
                        ARROW_BUTTON_WIDTH, ARROW_BUTTON_HEIGHT).build());
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
            currentTexture = ThumbnailTexture.upload(
                    "viewer_" + index + "_" + System.nanoTime(), image);
            currentImage = image; // cache for clipboard reuse
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
        currentImage = null; // clear cached image when texture is released
    }

    @Override
    public void onClose() {
        releaseTexture();
        this.minecraft.setScreen(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        // Top bar: filename + position indicator
        String fileName = current().fileName();
        String trimmedName = trimForWidth(fileName, this.width - 16);
        graphics.centeredText(this.font, trimmedName, this.width / 2, 8, 0xFFFFFFFF);
        graphics.centeredText(this.font, (index + 1) + " / " + entries.size(),
                this.width / 2, 20, 0xFFAAAAAA);

        if (loadFailed) {
            graphics.centeredText(this.font, Component.translatable("snapview.viewer.loadfailed"),
                    this.width / 2, this.height / 2, 0xFFFF5555);
        } else if (currentTexture != null) {
            // Available viewport for the image: between the top bar and the
            // bottom bar, with side margins for the arrow buttons.
            int imageAreaTop = TOP_BAR_HEIGHT;
            int imageAreaBottom = this.height - BOTTOM_BAR_HEIGHT - 16; // leave room for filename + status above buttons
            int imageAreaLeft = 8 + ARROW_BUTTON_WIDTH + 8;
            int imageAreaRight = this.width - 8 - ARROW_BUTTON_WIDTH - 8;
            int maxW = imageAreaRight - imageAreaLeft;
            int maxH = imageAreaBottom - imageAreaTop;

            if (maxW < 1) maxW = 1;
            if (maxH < 1) maxH = 1;

            // Scale to fit while preserving aspect ratio.
            // Allow upscaling up to 2x for small images so they're visible.
            double scale = Math.min(maxW / (double) imageWidth, maxH / (double) imageHeight);
            scale = Math.min(scale, 2.0);
            int drawW = Math.max(1, (int) Math.round(imageWidth * scale));
            int drawH = Math.max(1, (int) Math.round(imageHeight * scale));

            // Center horizontally and vertically within the image area
            int drawX = (this.width - drawW) / 2;
            int drawY = imageAreaTop + (maxH - drawH) / 2;

            // blit(Identifier, x0, y0, x1, y1, u0, u1, v0, v1)
            graphics.blit(currentTexture.id(), drawX, drawY, drawX + drawW, drawY + drawH,
                    0.0f, 1.0f, 0.0f, 1.0f);
        } else if (isLoading) {
            graphics.centeredText(this.font, Component.translatable("snapview.viewer.loading"),
                    this.width / 2, this.height / 2, 0xFFAAAAAA);
        }

        // Status message / copy progress above the bottom button bar
        // (filename is only shown at the top — no duplicate below the image)
        if (isCopying) {
            // Animated copy progress indicator — pulsing dots that cycle every 400ms.
            // This makes it visually obvious that the copy operation is still running,
            // not frozen. The elapsed time is also shown so the user knows how long
            // they've been waiting.
            long elapsedMs = (System.nanoTime() - copyStartTime) / 1_000_000;
            int dotCount = (int) (System.currentTimeMillis() / 400) % 4; // 0,1,2,3 cycling
            String dots = ".".repeat(dotCount);
            String progressText = "Copying screenshot" + dots + " (" + elapsedMs + "ms)";
            graphics.centeredText(this.font, progressText, this.width / 2,
                    this.height - BOTTOM_BAR_HEIGHT - 24, 0xFFFFFFAA);
        } else if (!statusMessage.getString().isEmpty()) {
            // Static status message (success or error)
            int color = statusMessage.getString().startsWith("Could not") ? 0xFFFF5555 : 0xFFAAFFAA;
            graphics.centeredText(this.font, statusMessage, this.width / 2,
                    this.height - BOTTOM_BAR_HEIGHT - 24, color);
        }
    }

    /**
     * Truncates the filename with an ellipsis if it's wider than maxWidth pixels.
     */
    private String trimForWidth(String text, int maxWidth) {
        if (this.font.width(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "\u2026";
        int ellipsisWidth = this.font.width(ellipsis);
        // Binary search for the longest prefix that fits with the ellipsis
        int lo = 1, hi = text.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (this.font.width(text.substring(0, mid)) + ellipsisWidth <= maxWidth) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return text.substring(0, lo) + ellipsis;
    }

    private void copyCurrent() {
        // Mark copy as in progress — the animated indicator will be shown
        // in extractRenderState until the callback fires.
        isCopying = true;
        copyStartTime = System.nanoTime();
        statusMessage = Component.translatable("snapview.clipboard.copying");

        if (currentImage != null) {
            ClipboardService.copyImageAsync(currentImage, current().path(),
                    Minecraft.getInstance()::execute, errorMessage -> {
                isCopying = false;
                if (errorMessage == null || errorMessage.isEmpty()) {
                    statusMessage = Component.translatable("snapview.clipboard.copied");
                } else {
                    statusMessage = Component.translatable("snapview.clipboard.failed.reason", errorMessage);
                }
            });
        } else {
            ClipboardService.copyImageAsync(current().path(), Minecraft.getInstance()::execute, errorMessage -> {
                isCopying = false;
                if (errorMessage == null || errorMessage.isEmpty()) {
                    statusMessage = Component.translatable("snapview.clipboard.copied");
                } else {
                    statusMessage = Component.translatable("snapview.clipboard.failed.reason", errorMessage);
                }
            });
        }
    }

    private void renameCurrent() {
        this.minecraft.setScreen(new RenameScreen(this, repository, current(), renamed -> {
            entries.set(index, renamed);
            statusMessage = Component.translatable("snapview.rename.success", renamed.fileName());
        }));
    }

    private void deleteCurrent() {
        ScreenshotEntry entry = current();
        this.minecraft.setScreen(new ConfirmScreen(
                confirmed -> {
                    if (confirmed) {
                        performDelete(entry);
                    } else {
                        this.minecraft.setScreen(this);
                    }
                },
                Component.translatable("snapview.viewer.delete"),
                Component.translatable("snapview.viewer.delete.confirm", entry.fileName())
        ));
    }

    private void performDelete(ScreenshotEntry entry) {
        try {
            java.nio.file.Files.deleteIfExists(entry.path());

            var client = com.azkivip.snapview.client.SnapViewClient.getInstance();
            if (client != null && client.getDiskCache() != null) {
                client.getDiskCache().deleteForEntry(entry);
            }

            repository.removeEntry(entry);
            entries.remove(entry);

            if (entries.isEmpty()) {
                onClose();
            } else {
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
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        // LEFT arrow → previous screenshot
        if (key == GLFW.GLFW_KEY_LEFT) {
            if (index > 0) {
                navigate(-1);
            }
            return true;
        }
        // RIGHT arrow → next screenshot
        if (key == GLFW.GLFW_KEY_RIGHT) {
            if (index < entries.size() - 1) {
                navigate(1);
            }
            return true;
        }
        // ESC → return to gallery (same as Back button)
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
