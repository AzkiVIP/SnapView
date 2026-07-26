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
     * from a previous gallery session, causing the new texture to silently
     * bind to the old (empty) GPU resource.
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
     * This is the single entry point for all GPU texture uploads in SnapView.
     * Both the gallery (thumbnails) and the viewer (full-size images) use it.
     * <p>
     * Pipeline (traced end-to-end):
     * <ol>
     *   <li>{@code BufferedImage.getRGB(x, y)} returns ARGB packed ints
     *       (0xAARRGGBB in Java's int layout).</li>
     *   <li>We convert ARGB → ABGR packed ints (0xAABBGGRR). This is what
     *       {@code NativeImage.setPixelABGR} expects — the method name was
     *       renamed from {@code setPixelRGBA} in 26.1 because the packed
     *       value was always ABGR (little-endian RGBA byte order).</li>
     *   <li>{@code new NativeImage(Format.RGBA, w, h, false)} allocates a
     *       CPU-side pixel buffer with 4 bytes per pixel (R, G, B, A).</li>
     *   <li>After writing all pixels via {@code setPixelABGR}, the buffer
     *       contains bytes in R, G, B, A order (because the ABGR int is
     *       stored little-endian → bytes R G B A).</li>
     *   <li>{@code new DynamicTexture(Supplier, NativeImage)} creates the GL
     *       texture object and allocates GPU storage, but in 26.1 it does
     *       NOT upload the pixel data. We MUST call {@code texture.upload()}
     *       to push the NativeImage pixels to the GPU. Without this call the
     *       GPU texture memory stays at zero (all-black) — the exact
     *       "black thumbnails" symptom.</li>
     *   <li>{@code TextureManager.register(id, texture)} binds the
     *       DynamicTexture to our Identifier so {@code graphics.blit(id, ...)}
     *       can render it.</li>
     * </ol>
     */
    public static ThumbnailTexture upload(String debugName, BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();

        NativeImage nativeImage = new NativeImage(NativeImage.Format.RGBA, width, height, false);

        // Convert ARGB (Java BufferedImage) → ABGR (NativeImage).
        // ARGB int: 0xAARRGGBB
        // ABGR int: 0xAABBGGRR
        // When the ABGR int is written to memory (little-endian), the bytes
        // become R, G, B, A — which matches GL_RGBA8 expected by Format.RGBA.
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

        // Globally unique ID so TextureManager.register() never collides
        // with a stale registration from a previous session.
        long uid = TEXTURE_COUNTER.incrementAndGet();
        Identifier id = Identifier.fromNamespaceAndPath("snapview",
                "thumbnail/t" + uid + "_" + sanitize(debugName));

        // CRITICAL: DynamicTexture(Supplier, NativeImage) in 26.1 creates the
        // GL texture object + allocates storage, but does NOT upload pixels.
        // We must call upload() explicitly.
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
