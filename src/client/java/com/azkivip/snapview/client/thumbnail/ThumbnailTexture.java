package com.azkivip.snapview.client.thumbnail;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Wraps a single GPU-resident thumbnail texture.
 * <p>
 * Must only be constructed/closed on the client render thread, since it
 * performs real GPU uploads/deletes.
 */
public final class ThumbnailTexture implements AutoCloseable {

    /**
     * Monotonic counter guaranteeing every texture gets a globally unique
     * Identifier, even across gallery open/close cycles. Without this, the
     * TextureManager.register() call could collide with a stale registration
     * from a previous gallery session that wasn't fully released, causing
     * the new texture to silently bind to the old (empty) GPU resource.
     */
    private static final AtomicLong TEXTURE_COUNTER = new AtomicLong(0);

    private final Identifier textureId;
    private boolean closed = false;

    private ThumbnailTexture(Identifier textureId) {
        this.textureId = textureId;
    }

    /**
     * Uploads a BufferedImage to the GPU as a {@link SmoothDynamicTexture}.
     * <p>
     * Uses LINEAR min/mag filtering and CLAMP_TO_EDGE address mode (via
     * SmoothDynamicTexture) instead of the NEAREST/REPEAT that vanilla
     * DynamicTexture hardcodes. This eliminates the "pixelated thumbnail"
     * look when the texture is drawn at a size larger than its native pixel
     * dimensions (which happens at any GUI scale > 1).
     * <p>
     * The pixel conversion (ARGB → ABGR → little-endian RGBA bytes) matches
     * GL_RGBA8 / GL_UNSIGNED_BYTE as expected by
     * {@code CommandEncoder.writeToTexture}.
     */
    public static ThumbnailTexture upload(String debugName, BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();

        NativeImage nativeImage = new NativeImage(NativeImage.Format.RGBA, width, height, false);

        // Java's BufferedImage.getRGB returns ARGB packed ints.
        // NativeImage.setPixelABGR expects ABGR packed ints (and only works on
        // Format.RGBA images — it throws on other formats).
        // ARGB 0xAARRGGBB -> ABGR 0xAABBGGRR
        // When written to memory (little-endian via MemoryUtil.memPutInt),
        // the ABGR int 0xAABBGGRR becomes bytes R G B A in memory, which
        // matches the GL_RGBA8 / GL_UNSIGNED_BYTE layout expected by
        // CommandEncoder.writeToTexture (which calls glTexSubImage2D with
        // GL_RGBA, GL_UNSIGNED_BYTE). Verified at byte level.
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb = image.getRGB(x, y);
                int a = (argb >>> 24) & 0xFF;
                int r = (argb >>> 16) & 0xFF;
                int g = (argb >>> 8) & 0xFF;
                int b = argb & 0xFF;
                int abgr = (a << 24) | (b << 16) | (g << 8) | r;
                nativeImage.setPixelABGR(x, y, abgr);
            }
        }

        // Use a monotonic counter in the Identifier path so every texture
        // gets a unique ID — no collision risk across gallery sessions even
        // if TextureManager.release() is delayed or skipped.
        long uid = TEXTURE_COUNTER.incrementAndGet();
        Identifier id = Identifier.fromNamespaceAndPath("snapview",
                "thumbnail/t" + uid + "_" + sanitize(debugName));

        // SmoothDynamicTexture (not vanilla DynamicTexture) to get LINEAR
        // filtering instead of NEAREST. The constructor creates the GL texture,
        // uploads pixels, and binds a LINEAR/CLAMP_TO_EDGE sampler — all
        // in one call.
        SmoothDynamicTexture texture = new SmoothDynamicTexture(() -> "t" + uid, nativeImage);

        Minecraft.getInstance().getTextureManager().register(id, texture);

        return new ThumbnailTexture(id);
    }

    public Identifier id() {
        return textureId;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        Minecraft.getInstance().getTextureManager().release(textureId);
    }

    private static String sanitize(String name) {
        return name.toLowerCase().replaceAll("[^a-z0-9/_.-]", "_");
    }
}
