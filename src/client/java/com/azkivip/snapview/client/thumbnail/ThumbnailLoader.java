package com.azkivip.snapview.client.thumbnail;

import com.azkivip.snapview.client.screenshot.ScreenshotEntry;
import net.minecraft.client.Minecraft;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    /** Base target box for thumbnails; actual size adapts to screen width in the gallery. */
    public static final int BASE_THUMB_WIDTH = 240;
    public static final int BASE_THUMB_HEIGHT = 135;

    /** Hard cap on simultaneously live GPU textures. */
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
        return peek(entry, BASE_THUMB_WIDTH, BASE_THUMB_HEIGHT);
    }

    public ThumbnailTexture peek(ScreenshotEntry entry, int targetWidth, int targetHeight) {
        return liveTextures.get(sizedKey(entry, targetWidth, targetHeight));
    }

    public void request(ScreenshotEntry entry, Consumer<ThumbnailTexture> onReady) {
        request(entry, BASE_THUMB_WIDTH, BASE_THUMB_HEIGHT, onReady);
    }

    /**
     * Requests a thumbnail at a specific target size. The size is part of the
     * cache key so switching screen resolutions doesn't serve a stale
     * wrongly-scaled thumbnail.
     */
    public void request(ScreenshotEntry entry, int targetWidth, int targetHeight,
                        Consumer<ThumbnailTexture> onReady) {
        String key = sizedKey(entry, targetWidth, targetHeight);

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
                BufferedImage thumbnail = loadOrGenerate(entry, targetWidth, targetHeight, key);
                if (thumbnail == null) {
                    inFlight.remove(key);
                    pendingTasks.remove(key);
                    return;
                }
                Minecraft.getInstance().execute(() -> {
                    if (!inFlight.contains(key)) {
                        return; // Cancelled while decoding.
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

    public void cancel(ScreenshotEntry entry) {
        cancel(entry, BASE_THUMB_WIDTH, BASE_THUMB_HEIGHT);
    }

    public void cancel(ScreenshotEntry entry, int targetWidth, int targetHeight) {
        String key = sizedKey(entry, targetWidth, targetHeight);
        Future<?> future = pendingTasks.remove(key);
        if (future != null) {
            future.cancel(true);
        }
        inFlight.remove(key);
    }

    private static String sizedKey(ScreenshotEntry entry, int w, int h) {
        return entry.cacheKey() + ":" + w + "x" + h;
    }

    private BufferedImage loadOrGenerate(ScreenshotEntry entry, int targetWidth, int targetHeight,
                                          String sizedKey) throws IOException {
        // Try disk cache first using the size-encoded key
        Path cacheFile = diskCache.cacheFileForSizedKey(sizedKey);
        if (Files.isRegularFile(cacheFile)) {
            BufferedImage cached = ImageIO.read(cacheFile.toFile());
            if (cached != null && cached.getWidth() == targetWidth && cached.getHeight() == targetHeight) {
                return cached;
            }
        }

        BufferedImage full = ImageIO.read(entry.path().toFile());
        if (full == null) {
            return null;
        }

        BufferedImage scaled = downscale(full, targetWidth, targetHeight);
        full.flush();

        diskCache.writeToPath(sizedKey, scaled);
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
