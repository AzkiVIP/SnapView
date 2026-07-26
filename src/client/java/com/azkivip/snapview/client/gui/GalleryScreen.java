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
 * The scrolling model is virtualized: {@link #extractRenderState} only ever
 * iterates over the rows that intersect the current viewport (plus one row
 * of prefetch margin). It never iterates the full entry list except to
 * compute total content height.
 */
public final class GalleryScreen extends Screen {

    private static final int PADDING = 8;
    private static final int LABEL_HEIGHT = 12;
    private static final int TOP_BAR_HEIGHT = 56;
    private static final int MIN_THUMB_WIDTH = 120;
    private static final int MIN_THUMB_HEIGHT = 68;

    private final Screen parent;
    private final ScreenshotRepository repository;
    private final ThumbnailLoader thumbnailLoader;

    private int columns = 3;
    private int thumbWidth = ThumbnailLoader.BASE_THUMB_WIDTH;
    private int thumbHeight = ThumbnailLoader.BASE_THUMB_HEIGHT;
    private int cellWidth;
    private int cellHeight;

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

        // Search field
        int searchWidth = Math.min(300, this.width - PADDING * 2 - 200);
        searchField = new EditBox(this.font, PADDING, PADDING + 24, searchWidth, 20,
                Component.translatable("snapview.gallery.search"));
        searchField.setMaxLength(100);
        searchField.setHint(Component.translatable("snapview.gallery.search.hint"));
        searchField.setValue(searchQuery);
        searchField.setResponder(text -> {
            searchQuery = text;
            // Reset scroll when the filter changes so results are visible
            scrollOffset = 0;
        });
        addRenderableWidget(searchField);
        setInitialFocus(searchField);

        // Clear Cache button (next to Back)
        int clearCacheX = this.width - 80 - PADDING - 90 - PADDING;
        addRenderableWidget(Button.builder(Component.translatable("snapview.gallery.clearcache"),
                        button -> clearCache())
                .bounds(clearCacheX, PADDING + 24, 90, 20)
                .build());

        // Back button
        addRenderableWidget(Button.builder(Component.translatable("snapview.gallery.back"),
                        button -> onClose())
                .bounds(this.width - 80 - PADDING, PADDING + 24, 80, 20)
                .build());

        if (!repository.isLoaded()) {
            repository.refreshAsync(this.minecraft::execute, entries -> {
                // Repository updated; next render will show entries.
            });
        }
    }

    private void clearCache() {
        // Confirm before clearing
        this.minecraft.setScreen(new net.minecraft.client.gui.screens.ConfirmScreen(
                confirmed -> {
                    if (confirmed) {
                        var client = com.azkivip.snapview.client.SnapViewClient.getInstance();
                        if (client != null && client.getDiskCache() != null) {
                            client.getDiskCache().clearAll();
                        }
                        // Release all live textures so they regenerate from scratch
                        thumbnailLoader.releaseAll();
                    }
                    this.minecraft.setScreen(this);
                },
                Component.translatable("snapview.gallery.clearcache"),
                Component.translatable("snapview.gallery.clearcache.confirm")
        ));
    }

    private void computeLayout() {
        int availableWidth = this.width - PADDING * 2;

        // Always target 3 columns on desktop. Only reduce column count if the
        // window is genuinely too small to fit 3 columns at MIN_THUMB_WIDTH.
        // Note: this.width is the SCALED GUI width, not the window pixel width.
        // At default GUI scale on a 1920px window, this.width is ~960.
        int minColumns3Width = MIN_THUMB_WIDTH * 3 + PADDING * 4; // 3*120 + 32 = 392
        int minColumns2Width = MIN_THUMB_WIDTH * 2 + PADDING * 3; // 2*120 + 24 = 264

        if (this.width < minColumns2Width) {
            columns = 1;
        } else if (this.width < minColumns3Width) {
            columns = 2;
        } else {
            columns = 3;
        }

        // Compute thumbnail size to fill the available width with the chosen column count.
        int totalPadding = PADDING * (columns + 1);
        thumbWidth = (availableWidth - totalPadding) / columns;
        thumbWidth = Math.max(MIN_THUMB_WIDTH, thumbWidth);

        // Maintain 16:9 aspect ratio
        thumbHeight = thumbWidth * 9 / 16;
        thumbHeight = Math.max(MIN_THUMB_HEIGHT, thumbHeight);

        cellWidth = thumbWidth + PADDING;
        cellHeight = thumbHeight + PADDING + LABEL_HEIGHT;
    }

    @Override
    public void resize(int newWidth, int newHeight) {
        // Save the search query before the screen is rebuilt
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
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        List<ScreenshotEntry> allEntries = repository.entries();

        if (!repository.isLoaded()) {
            graphics.centeredText(this.font, Component.translatable("snapview.gallery.loading"),
                    this.width / 2, this.height / 2, 0xFFFFFF);
            return;
        }

        List<ScreenshotEntry> entries = repository.filteredEntries(searchQuery);

        if (entries.isEmpty()) {
            Component emptyMsg = searchQuery.isBlank()
                    ? Component.translatable("snapview.gallery.empty")
                    : Component.translatable("snapview.gallery.noresults");
            graphics.centeredText(this.font, emptyMsg, this.width / 2, this.height / 2, 0xAAAAAA);
            return;
        }

        // Screenshot count display (top-right)
        String countText = searchQuery.isBlank()
                ? entries.size() + " screenshots"
                : entries.size() + " / " + allEntries.size() + " screenshots";
        graphics.text(this.font, countText, this.width - PADDING, PADDING + 4, 0xAAAAAA, false);

        // Cache size display (top-left, below search field)
        var client = com.azkivip.snapview.client.SnapViewClient.getInstance();
        if (client != null && client.getDiskCache() != null) {
            long cacheBytes = client.getDiskCache().cacheSizeBytes();
            int cacheCount = client.getDiskCache().cacheFileCount();
            String cacheText = "Cache: " + formatSize(cacheBytes) + " (" + cacheCount + " files)";
            graphics.text(this.font, cacheText, PADDING, PADDING + 4, 0x888888, false);
        }

        int viewportTop = TOP_BAR_HEIGHT;
        int viewportBottom = this.height;
        int totalRows = (entries.size() + columns - 1) / columns;
        int contentHeight = totalRows * cellHeight;
        clampScroll(contentHeight, viewportBottom - viewportTop);

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

        // Scrollbar
        int viewportHeight = viewportBottom - viewportTop;
        if (contentHeight > viewportHeight) {
            int scrollbarX = this.width - 4;
            int scrollbarHeight = Math.max(20, viewportHeight * viewportHeight / contentHeight);
            int scrollbarY = viewportTop + (int) (scrollOffset * viewportHeight / contentHeight);
            graphics.fill(scrollbarX, viewportTop, scrollbarX + 3, viewportBottom, 0x40404040);
            graphics.fill(scrollbarX, scrollbarY, scrollbarX + 3, scrollbarY + scrollbarHeight, 0x80808080);
        }
    }

    private void renderCell(GuiGraphicsExtractor graphics, ScreenshotEntry entry, int x, int y, boolean hovered) {
        // Dark background placeholder
        graphics.fill(x, y, x + thumbWidth, y + thumbHeight, 0xFF1E1E1E);

        ThumbnailTexture texture = thumbnailLoader.peek(entry, thumbWidth, thumbHeight);
        if (texture == null) {
            thumbnailLoader.request(entry, thumbWidth, thumbHeight, t -> {
                // Next frame's peek() will find it.
            });
        } else {
            drawThumbnail(graphics, texture, x, y);
        }

        if (hovered) {
            graphics.outline(x - 1, y - 1, thumbWidth + 2, thumbHeight + 2, 0xFFFFFFFF);
        }

        graphics.text(this.font, trimmedLabel(entry.fileName()),
                x, y + thumbHeight + 2, 0xCCCCCC, false);
    }

    private void drawThumbnail(GuiGraphicsExtractor graphics, ThumbnailTexture texture, int x, int y) {
        // blit(Identifier, x, y, w, h, u0, v0, u1, v1) - UV in 0..1 space.
        graphics.blit(texture.id(), x, y, thumbWidth, thumbHeight,
                0.0f, 0.0f, 1.0f, 1.0f);
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
