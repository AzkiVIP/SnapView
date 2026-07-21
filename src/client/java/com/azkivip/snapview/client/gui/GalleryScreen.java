package com.azkivip.snapview.client.gui;

import com.azkivip.snapview.client.screenshot.ScreenshotEntry;
import com.azkivip.snapview.client.screenshot.ScreenshotRepository;
import com.azkivip.snapview.client.thumbnail.ThumbnailLoader;
import com.azkivip.snapview.client.thumbnail.ThumbnailTexture;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Screenshot gallery grid.
 * <p>
 * This is the screen the "no freeze on 500+ screenshots" requirement is
 * actually about, so the scrolling model here is deliberately virtualized:
 * {@link #render} only ever iterates over the rows that intersect the
 * current viewport (plus one row of prefetch margin above/below). It never
 * iterates the full entry list except to compute total content height for
 * the scrollbar, which is an O(1) calculation from the entry count.
 * <p>
 * NOTE FOR REVIEW: the exact {@code GuiGraphics} blit overload used in
 * {@link #drawThumbnail} has changed across recent Minecraft versions and
 * was not compiled against the real 26.1.2 jar in this environment -
 * double check that one method if the build fails here.
 */
public final class GalleryScreen extends Screen {

	private static final int PADDING = 8;
	private static final int CELL_WIDTH = ThumbnailLoader.THUMB_WIDTH + PADDING;
	private static final int CELL_HEIGHT = ThumbnailLoader.THUMB_HEIGHT + PADDING + 12; // + label row
	private static final int TOP_BAR_HEIGHT = 32;

	private final Screen parent;
	private final ScreenshotRepository repository;
	private final ThumbnailLoader thumbnailLoader;

	private int columns = 1;
	private double scrollOffset = 0;
	private int hoveredIndex = -1;

	public GalleryScreen(Screen parent, ScreenshotRepository repository, ThumbnailLoader thumbnailLoader) {
		super(Component.translatable("snapview.gallery.title"));
		this.parent = parent;
		this.repository = repository;
		this.thumbnailLoader = thumbnailLoader;
	}

	@Override
	protected void init() {
		columns = Math.max(1, (this.width - PADDING) / CELL_WIDTH);

		addRenderableWidget(Button.builder(Component.translatable("snapview.gallery.back"),
						button -> onClose())
				.bounds(PADDING, PADDING, 80, 20)
				.build());

		if (!repository.isLoaded()) {
			repository.refreshAsync(this.minecraft::execute, entries -> {
				// Repository is already updated in place; nothing else to do here,
				// the next render() call will simply see a populated list.
			});
		}
	}

	@Override
	public void onClose() {
		thumbnailLoader.releaseAll();
		this.minecraft.setScreen(parent);
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.render(graphics, mouseX, mouseY, partialTick);

		List<ScreenshotEntry> entries = repository.entries();

		if (!repository.isLoaded()) {
			graphics.drawCenteredString(this.font, Component.translatable("snapview.gallery.loading"),
					this.width / 2, this.height / 2, 0xFFFFFF);
			return;
		}

		if (entries.isEmpty()) {
			graphics.drawCenteredString(this.font, Component.translatable("snapview.gallery.empty"),
					this.width / 2, this.height / 2, 0xAAAAAA);
			return;
		}

		int viewportTop = TOP_BAR_HEIGHT;
		int viewportBottom = this.height;
		int totalRows = (entries.size() + columns - 1) / columns;
		int contentHeight = totalRows * CELL_HEIGHT;
		clampScroll(contentHeight, viewportBottom - viewportTop);

		// Only the rows overlapping the viewport (plus one row of margin either
		// side) are visited - this is what keeps frame cost flat regardless of
		// how many screenshots exist in total.
		int firstVisibleRow = Math.max(0, (int) (scrollOffset / CELL_HEIGHT) - 1);
		int lastVisibleRow = Math.min(totalRows - 1,
				(int) ((scrollOffset + (viewportBottom - viewportTop)) / CELL_HEIGHT) + 1);

		hoveredIndex = -1;

		for (int row = firstVisibleRow; row <= lastVisibleRow; row++) {
			int rowTop = viewportTop + (row * CELL_HEIGHT) - (int) scrollOffset;

			for (int col = 0; col < columns; col++) {
				int index = row * columns + col;
				if (index >= entries.size()) {
					break;
				}

				int cellX = PADDING + col * CELL_WIDTH;
				int cellY = rowTop;

				if (cellY + CELL_HEIGHT < viewportTop || cellY > viewportBottom) {
					continue; // Fully outside viewport even after row-level filtering; skip.
				}

				ScreenshotEntry entry = entries.get(index);
				boolean hovered = mouseX >= cellX && mouseX < cellX + ThumbnailLoader.THUMB_WIDTH
						&& mouseY >= cellY && mouseY < cellY + ThumbnailLoader.THUMB_HEIGHT
						&& mouseY >= viewportTop;
				if (hovered) {
					hoveredIndex = index;
				}

				renderCell(graphics, entry, cellX, cellY, hovered);
			}
		}
	}

	private void renderCell(GuiGraphics graphics, ScreenshotEntry entry, int x, int y, boolean hovered) {
		graphics.fill(x, y, x + ThumbnailLoader.THUMB_WIDTH, y + ThumbnailLoader.THUMB_HEIGHT, 0xFF1E1E1E);

		ThumbnailTexture texture = thumbnailLoader.peek(entry);
		if (texture == null) {
			thumbnailLoader.request(entry, t -> {
				/* no-op: next frame's peek() will simply find it. */
			});
		} else {
			drawThumbnail(graphics, texture, x, y);
		}

		if (hovered) {
			graphics.renderOutline(x - 1, y - 1, ThumbnailLoader.THUMB_WIDTH + 2, ThumbnailLoader.THUMB_HEIGHT + 2, 0xFFFFFFFF);
		}

		graphics.drawString(this.font, trimmedLabel(entry.fileName()), x, y + ThumbnailLoader.THUMB_HEIGHT + 2, 0xAAAAAA, false);
	}

	private void drawThumbnail(GuiGraphics graphics, ThumbnailTexture texture, int x, int y) {
		graphics.blit(texture.id(), x, y, 0, 0,
				ThumbnailLoader.THUMB_WIDTH, ThumbnailLoader.THUMB_HEIGHT,
				ThumbnailLoader.THUMB_WIDTH, ThumbnailLoader.THUMB_HEIGHT);
	}

	private String trimmedLabel(String fileName) {
		String withoutExtension = fileName.endsWith(".png") ? fileName.substring(0, fileName.length() - 4) : fileName;
		int maxChars = CELL_WIDTH / 6;
		return withoutExtension.length() > maxChars
				? withoutExtension.substring(0, maxChars - 1) + "\u2026"
				: withoutExtension;
	}

	private void clampScroll(int contentHeight, int viewportHeight) {
		double maxScroll = Math.max(0, contentHeight - viewportHeight);
		scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		scrollOffset -= scrollY * (CELL_HEIGHT / 2.0);
		if (scrollOffset < 0) {
			scrollOffset = 0;
		}
		return true;
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button == 0 && hoveredIndex >= 0) {
			List<ScreenshotEntry> entries = repository.entries();
			if (hoveredIndex < entries.size()) {
				this.minecraft.setScreen(new ScreenshotViewerScreen(this, repository, entries, hoveredIndex));
				return true;
			}
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
