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
 * textures at once.
 */
public final class ThumbnailLoader implements AutoCloseable {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("SnapView/ThumbnailLoader");

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

    public ThumbnailTexture peek(ScreenshotEntry entry) {
        return liveTextures.get(entry.cacheKey());
    }

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
                    LOGGER.warn("Thumbnail generation returned null for {}", entry.fileName());
                    inFlight.remove(key);
                    pendingTasks.remove(key);
                    return;
                }
                Minecraft.getInstance().execute(() -> {
                    if (!inFlight.contains(key)) {
                        return; // Cancelled while decoding.
                    }
                    try {
                        ThumbnailTexture texture = ThumbnailTexture.upload(key, thumbnail);
                        liveTextures.put(key, texture);
                        inFlight.remove(key);
                        pendingTasks.remove(key);
                        onReady.accept(texture);
                    } catch (Exception e) {
                        LOGGER.error("Failed to upload thumbnail to GPU for {}", entry.fileName(), e);
                        inFlight.remove(key);
                        pendingTasks.remove(key);
                    }
                });
            } catch (Exception e) {
                LOGGER.error("Failed to load thumbnail for {}", entry.fileName(), e);
                inFlight.remove(key);
                pendingTasks.remove(key);
            }
        });
        pendingTasks.put(key, future);
    }

    public void cancel(ScreenshotEntry entry) {
        String key = entry.cacheKey();
        Future<?> future = pendingTasks.remove(key);
        if (future != null) {
            future.cancel(true);
        }
        inFlight.remove(key);
    }

    private BufferedImage loadOrGenerate(ScreenshotEntry entry) throws IOException {
        // 1. Try disk cache first
        if (diskCache.has(entry)) {
            try {
                BufferedImage cached = diskCache.read(entry);
                if (cached != null) {
                    return cached;
                }
                LOGGER.warn("Cache file for {} existed but ImageIO.read returned null (corrupt?) — regenerating", entry.fileName());
            } catch (Exception e) {
                LOGGER.warn("Failed to read cache file for {} — regenerating: {}", entry.fileName(), e.getMessage());
            }
        }

        // 2. Decode the full-resolution source PNG
        BufferedImage full = ImageIO.read(entry.path().toFile());
        if (full == null) {
            LOGGER.error("ImageIO.read returned null for source file {}", entry.path());
            return null;
        }

        // 3. Downscale to thumbnail dimensions
        BufferedImage scaled = downscale(full, THUMB_WIDTH, THUMB_HEIGHT);
        full.flush();

        // 4. Write to cache for next time
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
