package com.azkivip.snapview.client.gui;

import com.azkivip.snapview.client.screenshot.ScreenshotEntry;
import com.azkivip.snapview.client.screenshot.ScreenshotRepository;
import com.azkivip.snapview.client.thumbnail.ThumbnailLoader;
import com.azkivip.snapview.client.thumbnail.ThumbnailTexture;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Screenshot gallery grid with virtual scrolling, search, and a responsive
 * 3-column layout on desktop.
 * <p>
 * <b>Header design</b>: The header uses the vanilla screen background (drawn
 * by {@code super.extractRenderState} → {@code extractBackground}). A subtle
 * separator line distinguishes the header area from the gallery content below.
 * No separate dark rectangle is drawn — the header blends naturally with the
 * vanilla screen background. Gallery content is rendered below the header with
 * scissoring so thumbnails never overlap the header controls.
 * <p>
 * The scrolling model is virtualized: only rows intersecting the viewport
 * (plus one row of prefetch) are rendered.
 */
public final class GalleryScreen extends Screen {

    private static final int PADDING = 8;
    private static final int LABEL_HEIGHT = 12;
    /** Header area height (title row + controls row + bottom padding). */
    private static final int HEADER_HEIGHT = 56;
    /** Gap between the header bottom and the gallery content. */
    private static final int HEADER_GAP = 4;
    private static final int MIN_THUMB_WIDTH = 120;
    private static final int MIN_THUMB_HEIGHT = 68;
    private static final int MAX_TEX_DIMENSION = 768;

    /** Subtle separator line below the header. */
    private static final int HEADER_SEPARATOR_COLOR = 0x40808080;

    private final Screen parent;
    private final ScreenshotRepository repository;
    private final ThumbnailLoader thumbnailLoader;

    private int columns = 3;
    private int thumbWidth = ThumbnailLoader.BASE_THUMB_WIDTH;
    private int thumbHeight = ThumbnailLoader.BASE_THUMB_HEIGHT;
    private int cellWidth;
    private int cellHeight;
    private int texWidth = ThumbnailLoader.BASE_THUMB_WIDTH;
    private int texHeight = ThumbnailLoader.BASE_THUMB_HEIGHT;

    private double scrollOffset = 0;
    private int hoveredIndex = -1;
    private String searchQuery = "";

    private EditBox searchField;

    public GalleryScreen(Screen parent, ScreenshotRepository repository, ThumbnailLoader thumbnailLoader) {
        super(Component.translatable("snapview.gallery.title"));
        this.parent = parent;
        this.repository = repository;
        this.thumbnailLoader = thumbnailLoader;
    }

    @Override
    protected void init() {
        computeLayout();

        // Search field — positioned with proper padding from the left edge.
        int searchY = 28;
        int searchWidth = Math.min(280, this.width - PADDING * 2 - 200);
        searchField = new EditBox(this.font, PADDING, searchY, searchWidth, 20,
                Component.translatable("snapview.gallery.search"));
        searchField.setMaxLength(100);
        searchField.setHint(Component.translatable("snapview.gallery.search.hint"));
        searchField.setValue(searchQuery);
        searchField.setResponder(text -> {
            searchQuery = text;
            scrollOffset = 0;
        });
        addRenderableWidget(searchField);
        setInitialFocus(searchField);

        // Clear Cache + Back buttons on the right side of the header
        int btnY = searchY;
        int backBtnW = 60;
        int clearCacheBtnW = 90;
        int btnSpacing = 4;
        int rightEdge = this.width - PADDING;

        addRenderableWidget(Button.builder(Component.translatable("snapview.gallery.back"),
                        button -> onClose())
                .bounds(rightEdge - backBtnW, btnY, backBtnW, 20).build());

        addRenderableWidget(Button.builder(Component.translatable("snapview.gallery.clearcache"),
                        button -> clearCache())
                .bounds(rightEdge - backBtnW - btnSpacing - clearCacheBtnW, btnY,
                        clearCacheBtnW, 20).build());

        if (!repository.isLoaded()) {
            repository.refreshAsync(this.minecraft::execute, entries -> {
            });
        }
    }

    private void clearCache() {
        this.minecraft.setScreen(new net.minecraft.client.gui.screens.ConfirmScreen(
                confirmed -> {
                    if (confirmed) {
                        var client = com.azkivip.snapview.client.SnapViewClient.getInstance();
                        if (client != null && client.getDiskCache() != null) {
                            client.getDiskCache().clearAll();
                        }
                        thumbnailLoader.releaseAll();
                        // Scroll back to top so the user sees the refreshed gallery
                        // from the beginning, not stuck at their previous scroll position.
                        scrollOffset = 0;
                    }
                    this.minecraft.setScreen(this);
                },
                Component.translatable("snapview.gallery.clearcache"),
                Component.translatable("snapview.gallery.clearcache.confirm")
        ));
    }

    private void computeLayout() {
        int availableWidth = this.width - PADDING * 2;
        int minColumns3Width = MIN_THUMB_WIDTH * 3 + PADDING * 4;
        int minColumns2Width = MIN_THUMB_WIDTH * 2 + PADDING * 3;

        if (this.width < minColumns2Width) {
            columns = 1;
        } else if (this.width < minColumns3Width) {
            columns = 2;
        } else {
            columns = 3;
        }

        int totalPadding = PADDING * (columns + 1);
        thumbWidth = (availableWidth - totalPadding) / columns;
        thumbWidth = Math.max(MIN_THUMB_WIDTH, thumbWidth);
        thumbHeight = thumbWidth * 9 / 16;
        thumbHeight = Math.max(MIN_THUMB_HEIGHT, thumbHeight);
        cellWidth = thumbWidth + PADDING;
        cellHeight = thumbHeight + PADDING + LABEL_HEIGHT;

        double guiScale = this.minecraft.getWindow().getGuiScale();
        texWidth = Math.min((int) Math.ceil(thumbWidth * guiScale), MAX_TEX_DIMENSION);
        texHeight = Math.min((int) Math.ceil(thumbHeight * guiScale), MAX_TEX_DIMENSION);
    }

    @Override
    public void resize(int newWidth, int newHeight) {
        String savedQuery = searchQuery;
        super.resize(newWidth, newHeight);
        searchQuery = savedQuery;
    }

    @Override
    public void onClose() {
        thumbnailLoader.releaseAll();
        this.minecraft.setScreen(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // super.extractRenderState calls extractBackground which draws the
        // vanilla semi-transparent gradient background across the entire screen.
        // This provides the base background — no separate dark rectangle needed.
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        List<ScreenshotEntry> allEntries = repository.entries();

        if (!repository.isLoaded()) {
            drawHeader(graphics, "", null);
            graphics.centeredText(this.font, Component.translatable("snapview.gallery.loading"),
                    this.width / 2, this.height / 2, 0xFFFFFFFF);
            return;
        }

        List<ScreenshotEntry> entries = repository.filteredEntries(searchQuery);

        // Draw header text (title + info) and separator line.
        // Split info into two compact, clearly-labeled lines:
        // Line 1: "Screenshots: 124"
        // Line 2: "Cache: 45.2 MB, 118 thumbnails"
        String countText = searchQuery.isBlank()
                ? "Screenshots: " + entries.size()
                : "Screenshots: " + entries.size() + " / " + allEntries.size();
        String cacheText = null;
        var client = com.azkivip.snapview.client.SnapViewClient.getInstance();
        if (client != null && client.getDiskCache() != null) {
            long cacheBytes = client.getDiskCache().cacheSizeBytes();
            int cacheCount = client.getDiskCache().cacheFileCount();
            cacheText = "Cache: " + formatSize(cacheBytes) + ", " + cacheCount + " thumbnails";
        }
        drawHeader(graphics, countText, cacheText);

        if (entries.isEmpty()) {
            Component emptyMsg = searchQuery.isBlank()
                    ? Component.translatable("snapview.gallery.empty")
                    : Component.translatable("snapview.gallery.noresults");
            graphics.centeredText(this.font, emptyMsg, this.width / 2, this.height / 2, 0xFFAAAAAA);
            return;
        }

        // Gallery content viewport starts below the header + gap.
        int viewportTop = HEADER_HEIGHT + HEADER_GAP;
        int viewportBottom = this.height;
        int totalRows = (entries.size() + columns - 1) / columns;
        int contentHeight = totalRows * cellHeight;
        clampScroll(contentHeight, viewportBottom - viewportTop);

        // Enable scissoring so gallery content never renders into the header.
        graphics.enableScissor(0, viewportTop, this.width, viewportBottom);

        int firstVisibleRow = Math.max(0, (int) (scrollOffset / cellHeight) - 1);
        int lastVisibleRow = Math.min(totalRows - 1,
                (int) ((scrollOffset + (viewportBottom - viewportTop)) / cellHeight) + 1);

        hoveredIndex = -1;

        for (int row = firstVisibleRow; row <= lastVisibleRow; row++) {
            int rowTop = viewportTop + (row * cellHeight) - (int) scrollOffset;

            for (int col = 0; col < columns; col++) {
                int index = row * columns + col;
                if (index >= entries.size()) {
                    break;
                }

                int cellX = PADDING + col * cellWidth;
                int cellY = rowTop;

                if (cellY + cellHeight < viewportTop || cellY > viewportBottom) {
                    continue;
                }

                ScreenshotEntry entry = entries.get(index);
                boolean hovered = mouseX >= cellX && mouseX < cellX + thumbWidth
                        && mouseY >= cellY && mouseY < cellY + thumbHeight
                        && mouseY >= viewportTop;
                if (hovered) {
                    hoveredIndex = index;
                }

                renderCell(graphics, entry, cellX, cellY, hovered);
            }
        }

        // Scrollbar inside the scissored region
        int viewportHeight = viewportBottom - viewportTop;
        if (contentHeight > viewportHeight) {
            int scrollbarX = this.width - 4;
            int scrollbarHeight = Math.max(20, viewportHeight * viewportHeight / contentHeight);
            int scrollbarY = viewportTop + (int) (scrollOffset * viewportHeight / contentHeight);
            graphics.fill(scrollbarX, viewportTop, scrollbarX + 3, viewportBottom, 0x40404040);
            graphics.fill(scrollbarX, scrollbarY, scrollbarX + 3, scrollbarY + scrollbarHeight, 0x80808080);
        }

        graphics.disableScissor();
    }

    /**
     * Draws the header with title, compact info text (two lines), and a
     * subtle separator line. No dark background panel — the vanilla screen
     * background provides the base.
     * <p>
     * Info text is split into two short, clearly-labeled lines:
     * <ul>
     *   <li>Line 1 (y=4): "Screenshots: 124"</li>
     *   <li>Line 2 (y=14): "Cache: 45.2 MB, 118 thumbnails"</li>
     * </ul>
     * Both use bright, high-contrast colors for readability on the vanilla
     * dark background.
     */
    private void drawHeader(GuiGraphicsExtractor graphics, String countText, String cacheText) {
        // Title at top center
        graphics.centeredText(this.font, this.title, this.width / 2, 6, 0xFFFFFFFF);

        // Line 1: screenshot count (top-right, y=4)
        // Bright white for maximum readability
        if (countText != null && !countText.isEmpty()) {
            int w = this.font.width(countText);
            graphics.text(this.font, countText, this.width - PADDING - w, 4, 0xFFFFFFFF, false);
        }

        // Line 2: cache info (below line 1, y=14)
        // Light gray — bright enough to read but slightly dimmer than line 1
        if (cacheText != null && !cacheText.isEmpty()) {
            int w = this.font.width(cacheText);
            graphics.text(this.font, cacheText, this.width - PADDING - w, 14, 0xFFCCCCCC, false);
        }

        // Subtle separator line at the bottom of the header area
        graphics.fill(0, HEADER_HEIGHT, this.width, HEADER_HEIGHT + 1, HEADER_SEPARATOR_COLOR);
    }

    private void renderCell(GuiGraphicsExtractor graphics, ScreenshotEntry entry, int x, int y, boolean hovered) {
        // Dark background placeholder for the thumbnail
        graphics.fill(x, y, x + thumbWidth, y + thumbHeight, 0xFF1E1E1E);

        ThumbnailTexture texture = thumbnailLoader.peek(entry, texWidth, texHeight);
        if (texture == null) {
            thumbnailLoader.request(entry, texWidth, texHeight, t -> {
            });
        } else {
            drawThumbnail(graphics, texture, x, y);
        }

        if (hovered) {
            graphics.outline(x - 1, y - 1, thumbWidth + 2, thumbHeight + 2, 0xFFFFFFFF);
        }

        // Filename label below the thumbnail.
        // Color MUST include 0xFF alpha prefix — without it, the text is
        // fully transparent and invisible. 0xCCCCCC = alpha 0 (transparent);
        // 0xFFCCCCCC = alpha 255 (opaque light gray).
        graphics.text(this.font, trimmedLabel(entry.fileName()),
                x, y + thumbHeight + 2, 0xFFCCCCCC, false);
    }

    private void drawThumbnail(GuiGraphicsExtractor graphics, ThumbnailTexture texture, int x, int y) {
        graphics.blit(texture.id(), x, y, x + thumbWidth, y + thumbHeight,
                0.0f, 1.0f, 0.0f, 1.0f);
    }

    private String trimmedLabel(String fileName) {
        String withoutExtension = fileName.endsWith(".png")
                ? fileName.substring(0, fileName.length() - 4)
                : fileName;
        int maxChars = Math.max(4, thumbWidth / 6);
        if (withoutExtension.length() > maxChars) {
            return withoutExtension.substring(0, maxChars - 1) + "\u2026";
        }
        return withoutExtension;
    }

    private void clampScroll(int contentHeight, int viewportHeight) {
        double maxScroll = Math.max(0, contentHeight - viewportHeight);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        // Only scroll when the cursor is in the gallery content area (below the header)
        if (mouseY < HEADER_HEIGHT + HEADER_GAP) {
            return false;
        }
        scrollOffset -= scrollY * (cellHeight / 2.0);
        if (scrollOffset < 0) {
            scrollOffset = 0;
        }
        return true;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0 && hoveredIndex >= 0) {
            List<ScreenshotEntry> entries = repository.filteredEntries(searchQuery);
            if (hoveredIndex < entries.size()) {
                this.minecraft.setScreen(new ScreenshotViewerScreen(this, repository, entries, hoveredIndex));
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
