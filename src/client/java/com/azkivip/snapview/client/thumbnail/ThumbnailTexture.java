package com.azkivip.snapview.client.thumbnail;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
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
     * Uploads a BufferedImage to the GPU as a DynamicTexture.
     * <p>
     * CRITICAL: the DynamicTexture(Supplier, NativeImage) constructor in 26.1
     * creates the GL texture object but does NOT upload the pixel data.
     * We must call {@link DynamicTexture#upload()} explicitly. Without this
     * call, the GPU texture memory is uninitialized (all zeros = transparent
     * black), which is exactly the "black thumbnails" symptom.
     */
    public static ThumbnailTexture upload(String debugName, BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();

        NativeImage nativeImage = new NativeImage(NativeImage.Format.RGBA, width, height, false);

        // Java's BufferedImage.getRGB returns ARGB packed ints.
        // NativeImage.setPixelABGR expects ABGR packed ints.
        // ARGB 0xAARRGGBB -> ABGR 0xAABBGGRR
        // When written to memory (little-endian), ABGR bytes become R G B A,
        // matching GL_RGBA8 expected by Format.RGBA.
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

        // DynamicTexture(Supplier<String>, NativeImage) creates the GL texture
        // object and allocates storage, but does NOT upload pixel data in 26.1.
        // We must call upload() to push the NativeImage pixels to the GPU.
        DynamicTexture texture = new DynamicTexture(() -> "t" + uid, nativeImage);
        texture.upload();

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
