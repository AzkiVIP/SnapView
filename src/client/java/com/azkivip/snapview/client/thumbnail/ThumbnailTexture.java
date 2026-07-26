package com.azkivip.snapview.client.thumbnail;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicLong;

import javax.imageio.ImageIO;

/**
 * Wraps a single GPU-resident thumbnail texture.
 * <p>
 * Must only be constructed/closed on the client render thread, since it
 * performs real GPU uploads/deletes.
 */
public final class ThumbnailTexture implements AutoCloseable {

    private static final AtomicLong TEXTURE_COUNTER = new AtomicLong(0);

    private final Identifier textureId;
    private boolean closed = false;

    private ThumbnailTexture(Identifier textureId) {
        this.textureId = textureId;
    }

    /**
     * Uploads a BufferedImage to the GPU as a DynamicTexture.
     * <p>
     * Instead of manually copying pixels with setPixelABGR (which is error-prone
     * due to byte-order conversions), we re-encode the BufferedImage as PNG
     * bytes and use NativeImage.read(InputStream) — the same STB Image path
     * vanilla MC uses for all its textures. This guarantees correct pixel
     * format handling.
     */
    public static ThumbnailTexture upload(String debugName, BufferedImage image) {
        try {
            // Re-encode the BufferedImage as PNG bytes, then let NativeImage
            // decode them via STB Image. This is the proven path vanilla MC
            // uses for all PNG textures — no manual pixel-format conversion.
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "png", baos);
            byte[] pngBytes = baos.toByteArray();

            NativeImage nativeImage;
            try (InputStream is = new ByteArrayInputStream(pngBytes)) {
                nativeImage = NativeImage.read(is);
            }

            if (nativeImage == null) {
                throw new IllegalStateException("NativeImage.read returned null");
            }

            return registerTexture(debugName, nativeImage);
        } catch (Exception e) {
            throw new RuntimeException("Failed to upload thumbnail texture: " + debugName, e);
        }
    }

    /**
     * Uploads a PNG file directly from disk using NativeImage.read().
     * This is the most efficient path — no BufferedImage intermediate.
     * Returns the UploadedTexture which contains the texture ID and dimensions.
     */
    public static UploadedTexture uploadFromPath(String debugName, java.nio.file.Path imagePath) {
        try {
            NativeImage nativeImage;
            try (InputStream is = java.nio.file.Files.newInputStream(imagePath)) {
                nativeImage = NativeImage.read(is);
            }
            if (nativeImage == null) {
                throw new IllegalStateException("NativeImage.read returned null for " + imagePath);
            }
            int width = nativeImage.getWidth();
            int height = nativeImage.getHeight();
            ThumbnailTexture texture = registerTexture(debugName, nativeImage);
            return new UploadedTexture(texture, width, height);
        } catch (Exception e) {
            throw new RuntimeException("Failed to upload texture from path: " + imagePath, e);
        }
    }

    /** Holds a ThumbnailTexture along with its pixel dimensions. */
    public record UploadedTexture(ThumbnailTexture texture, int width, int height) {}

    /**
     * Uploads an already-decoded NativeImage to the GPU. Used by the viewer
     * which loads the NativeImage on a background thread then uploads it on
     * the client thread.
     */
    public static ThumbnailTexture uploadNativeImage(String debugName, NativeImage nativeImage) {
        return registerTexture(debugName, nativeImage);
    }

    private static ThumbnailTexture registerTexture(String debugName, NativeImage nativeImage) {
        long uid = TEXTURE_COUNTER.incrementAndGet();
        Identifier id = Identifier.fromNamespaceAndPath("snapview",
                "thumbnail/t" + uid + "_" + sanitize(debugName));

        // DynamicTexture(Supplier<String>, NativeImage) constructor in 26.1
        // creates the GL texture, allocates storage, AND calls upload()
        // internally (verified at bytecode offset 14-17 of the constructor).
        // So we do NOT need to call upload() again.
        DynamicTexture texture = new DynamicTexture(() -> "t" + uid, nativeImage);

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
