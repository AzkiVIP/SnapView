package com.azkivip.snapview.client.thumbnail;

import com.azkivip.snapview.client.screenshot.ScreenshotEntry;
import net.minecraft.client.Minecraft;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * Loads thumbnails for the gallery grid without ever blocking the render
 * thread and without ever holding more than a bounded number of GPU
 * textures at once - this is what keeps memory/VRAM flat regardless of
 * how many screenshots exist on disk.
 * <p>
 * Callers (the gallery screen) are expected to call {@link #request} only
 * for entries currently visible (+ a small prefetch margin), and
 * {@link #cancel} for entries that scroll back out of that range before
 * their thumbnail finished loading.
 */
public final class ThumbnailLoader implements AutoCloseable {

	/** Fixed target box; screenshots are scaled to fit inside this while preserving aspect ratio. */
	public static final int THUMB_WIDTH = 160;
	public static final int THUMB_HEIGHT = 90;

	/** Hard cap on simultaneously live GPU textures, independent of collection size. */
	private static final int MAX_LIVE_TEXTURES = 120;

	private final ExecutorService decodePool = Executors.newFixedThreadPool(2, runnable -> {
		Thread thread = new Thread(runnable, "SnapView-ThumbnailDecode");
		thread.setDaemon(true);
		thread.setPriority(Thread.NORM_PRIORITY - 1);
		return thread;
	});

	private final ThumbnailDiskCache diskCache;

	// Access-ordered LRU: touching an entry (get) moves it to the back;
	// once it grows past MAX_LIVE_TEXTURES the oldest texture is closed.
	private final Map<String, ThumbnailTexture> liveTextures = new LinkedHashMap<>(256, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, ThumbnailTexture> eldest) {
			if (size() > MAX_LIVE_TEXTURES) {
				eldest.getValue().close();
				return true;
			}
			return false;
		}
	};

	private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
	private final Map<String, Future<?>> pendingTasks = new ConcurrentHashMap<>();

	public ThumbnailLoader(ThumbnailDiskCache diskCache) {
		this.diskCache = diskCache;
	}

	/** Returns the already-uploaded texture, if any, without triggering a load. Call from the client thread. */
	public ThumbnailTexture peek(ScreenshotEntry entry) {
		return liveTextures.get(entry.cacheKey());
	}

	/**
	 * Requests a thumbnail. If it's already loaded, {@code onReady} fires
	 * immediately (on the calling/client thread). Otherwise loading happens
	 * in the background and {@code onReady} fires later via the client
	 * thread's task queue. Safe to call every frame for visible slots - it's
	 * a no-op once loaded or already in flight.
	 */
	public void request(ScreenshotEntry entry, Consumer<ThumbnailTexture> onReady) {
		String key = entry.cacheKey();

		ThumbnailTexture existing = liveTextures.get(key);
		if (existing != null) {
			onReady.accept(existing);
			return;
		}

		if (!inFlight.add(key)) {
			return; // Already loading.
		}

		Future<?> future = decodePool.submit(() -> {
			try {
				BufferedImage thumbnail = loadOrGenerate(entry);
				if (thumbnail == null) {
					return;
				}
				Minecraft.getInstance().execute(() -> {
					// The request may have been cancelled while decoding finished;
					// only upload to the GPU if we're still interested.
					if (!inFlight.contains(key)) {
						return;
					}
					ThumbnailTexture texture = ThumbnailTexture.upload(key, thumbnail);
					liveTextures.put(key, texture);
					inFlight.remove(key);
					pendingTasks.remove(key);
					onReady.accept(texture);
				});
			} catch (Exception e) {
				inFlight.remove(key);
				pendingTasks.remove(key);
			}
		});
		pendingTasks.put(key, future);
	}

	/** Cancels a pending (not-yet-uploaded) load, e.g. because the slot scrolled off-screen. */
	public void cancel(ScreenshotEntry entry) {
		String key = entry.cacheKey();
		Future<?> future = pendingTasks.remove(key);
		if (future != null) {
			future.cancel(true);
		}
		inFlight.remove(key);
	}

	private BufferedImage loadOrGenerate(ScreenshotEntry entry) throws IOException {
		if (diskCache.has(entry)) {
			BufferedImage cached = diskCache.read(entry);
			if (cached != null) {
				return cached;
			}
		}

		BufferedImage full = ImageIO.read(entry.path().toFile());
		if (full == null) {
			return null; // Corrupt/unreadable file - skip rather than crash the loader.
		}

		BufferedImage scaled = downscale(full, THUMB_WIDTH, THUMB_HEIGHT);
		full.flush(); // Release the full-resolution buffer immediately; we no longer need it.

		diskCache.write(entry, scaled);
		return scaled;
	}

	private static BufferedImage downscale(BufferedImage source, int maxWidth, int maxHeight) {
		double scale = Math.min(
				maxWidth / (double) source.getWidth(),
				maxHeight / (double) source.getHeight()
		);
		int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
		int height = Math.max(1, (int) Math.round(source.getHeight() * scale));

		BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = scaled.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g.drawImage(source, 0, 0, width, height, null);
		g.dispose();
		return scaled;
	}

	/** Drops every live GPU texture. Call when the gallery screen closes to free VRAM. */
	public void releaseAll() {
		for (ThumbnailTexture texture : liveTextures.values()) {
			texture.close();
		}
		liveTextures.clear();
		for (Future<?> future : pendingTasks.values()) {
			future.cancel(true);
		}
		pendingTasks.clear();
		inFlight.clear();
	}

	@Override
	public void close() {
		releaseAll();
		decodePool.shutdownNow();
	}
}
