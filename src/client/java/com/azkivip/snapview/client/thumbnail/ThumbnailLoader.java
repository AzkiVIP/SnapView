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
                    try {
                        ThumbnailTexture texture = ThumbnailTexture.upload(key, thumbnail);
                        liveTextures.put(key, texture);
                        onReady.accept(texture);
                    } catch (Exception e) {
                        // If the GPU upload fails for any reason, log and allow
                        // a future request() to retry — otherwise inFlight would
                        // stay set forever and the thumbnail would never appear.
                        com.azkivip.snapview.client.SnapViewClient.LOGGER.warn(
                                "Failed to upload thumbnail texture for {}", key, e);
                    } finally {
                        inFlight.remove(key);
                        pendingTasks.remove(key);
                    }
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

    /**
     * High-quality downscale using progressive (multi-step) halving with
     * BICUBIC interpolation for the final pass.
     * <p>
     * Progressive scaling produces dramatically better results than a single
     * drawImage call when the downscale ratio is large (e.g. 1920&times;1080 →
     * 304&times;171 is a ~6:1 ratio). Each halving step properly low-pass
     * filters the image, preserving detail and reducing aliasing that would
     * otherwise appear as stair-stepping on diagonal edges and moiré on
     * repeating textures.
     * <p>
     * The algorithm:
     * <ol>
     *   <li>While the current image is more than 2&times; the target size in
     *       either dimension, halve it (using BILINEAR — fast and good enough
     *       at 2:1 ratios).</li>
     *   <li>Do the final scale to exact target dimensions using BICUBIC
     *       interpolation for maximum edge smoothness.</li>
     * </ol>
     * All passes use QUALITY rendering and anti-aliasing hints.
     */
    private static BufferedImage downscale(BufferedImage source, int targetWidth, int targetHeight) {
        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();

        double scale = Math.min(
                targetWidth / (double) sourceWidth,
                targetHeight / (double) sourceHeight
        );
        int width = Math.max(1, (int) Math.round(sourceWidth * scale));
        int height = Math.max(1, (int) Math.round(sourceHeight * scale));

        // If the source is already at or below the target size, just do a
        // single BICUBIC pass (handles both exact-match and slight upscale).
        if (sourceWidth <= width * 2 && sourceHeight <= height * 2) {
            return singleStepScale(source, width, height);
        }

        // Progressive halving: repeatedly halve until within 2x of target.
        // BILINEAR is used for intermediate steps (fast, and 2:1 is the ideal
        // ratio for BILINEAR's box-filter-like behavior).
        BufferedImage current = source;
        int currentW = sourceWidth;
        int currentH = sourceHeight;

        while (currentW > width * 2 || currentH > height * 2) {
            int nextW = Math.max(width, currentW / 2);
            int nextH = Math.max(height, currentH / 2);

            BufferedImage next = new BufferedImage(nextW, nextH, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = next.createGraphics();
            applyQualityHints(g, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(current, 0, 0, nextW, nextH, null);
            g.dispose();

            if (current != source) {
                current.flush();
            }
            current = next;
            currentW = nextW;
            currentH = nextH;
        }

        // Final pass: BICUBIC interpolation for the smoothest possible edges.
        BufferedImage result = singleStepScale(current, width, height);

        if (current != source && current != result) {
            current.flush();
        }

        return result;
    }

    private static BufferedImage singleStepScale(BufferedImage source, int width, int height) {
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        applyQualityHints(g, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(source, 0, 0, width, height, null);
        g.dispose();
        return scaled;
    }

    private static void applyQualityHints(Graphics2D g, Object interpolation) {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
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
