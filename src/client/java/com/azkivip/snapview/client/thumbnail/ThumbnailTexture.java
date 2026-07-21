package com.azkivip.snapview.client.thumbnail;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.awt.image.BufferedImage;

/**
 * Wraps a single GPU-resident thumbnail texture.
 * <p>
 * NOTE FOR REVIEW: this is the one place in the mod that touches
 * Minecraft's native-image/texture-upload API directly. Everything here
 * (NativeImage, DynamicTexture, TextureManager#register) has been stable
 * in Mojang's own mappings across many versions, but has not been
 * compiled against the real 26.1.2 jar in this environment (no network
 * access to Mojang's artifact servers here) - verify this file first if
 * the build fails on anything thumbnail-related.
 * <p>
 * Must only be constructed/closed on the client render thread, since it
 * performs real GPU uploads/deletes.
 */
public final class ThumbnailTexture implements AutoCloseable {

        private final Identifier textureId;
        private boolean closed = false;

        private ThumbnailTexture(Identifier textureId) {
                this.textureId = textureId;
        }

        /** Must be called on the client thread. Uploads {@code image} to the GPU and registers it. */
        public static ThumbnailTexture upload(String debugName, BufferedImage image) {
                int width = image.getWidth();
                int height = image.getHeight();

                // 26.1 renamed NativeImage#setPixelRGBA to setPixelABGR (the value was always
                // ABGR-packed; the old name was a misnomer). Our argbToAbgr helper already
                // produces ABGR, so we just call the renamed setter.
                NativeImage nativeImage = new NativeImage(NativeImage.Format.RGBA, width, height, false);
                for (int y = 0; y < height; y++) {
                        for (int x = 0; x < width; x++) {
                                int argb = image.getRGB(x, y);
                                nativeImage.setPixelABGR(x, y, argbToAbgr(argb));
                        }
                }

                Identifier id = Identifier.fromNamespaceAndPath("snapview", "thumbnail/" + sanitize(debugName));
                // DynamicTexture(NativeImage) is gone in 26.1 - the constructor now requires a
                // Supplier<String> used as a debug label for the GPU resource. We pass the same
                // sanitized debug name so Profiler / crash dumps still point back to this texture.
                DynamicTexture texture = new DynamicTexture(() -> sanitize(debugName), nativeImage);
                Minecraft.getInstance().getTextureManager().register(id, texture);

                return new ThumbnailTexture(id);
        }

        public Identifier id() {
                return textureId;
        }

        /** Java's BufferedImage is ARGB; NativeImage expects ABGR (little-endian RGBA) packed ints. */
    private static int argbToAbgr(int argb) {
                int a = (argb >>> 24) & 0xFF;
                int r = (argb >>> 16) & 0xFF;
                int g = (argb >>> 8) & 0xFF;
                int b = argb & 0xFF;
                return (a << 24) | (b << 16) | (g << 8) | r;
        }

        private static String sanitize(String name) {
                return name.toLowerCase().replaceAll("[^a-z0-9/_.-]", "_");
        }

        @Override
        public void close() {
                if (closed) {
                        return;
                }
                closed = true;
                Minecraft.getInstance().getTextureManager().release(textureId);
        }
}
