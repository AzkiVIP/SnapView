package com.azkivip.snapview.client.thumbnail;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.TextureFormat;
import net.minecraft.client.renderer.texture.AbstractTexture;

import java.util.function.Supplier;

/**
 * A drop-in replacement for {@link net.minecraft.client.renderer.texture.DynamicTexture}
 * that uses <b>LINEAR</b> min/mag filtering and <b>CLAMP_TO_EDGE</b> address mode
 * instead of the hardcoded NEAREST / REPEAT that DynamicTexture uses.
 * <p>
 * <b>Why this exists:</b> MC 26.1's {@code DynamicTexture.createTexture()} calls
 * {@code RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST)}, which
 * creates a sampler with NEAREST minification AND NEAREST magnification. When a
 * thumbnail texture (e.g. 304&times;171 pixels) is drawn at screen resolution
 * (e.g. 608&times;342 at GUI scale 2), NEAREST magnification produces blocky 2&times;2
 * pixel squares — the classic "pixelated thumbnail" look. LINEAR magnification
 * interpolates between texels, producing a smooth image instead.
 * <p>
 * The implementation mirrors DynamicTexture's createTexture + upload path exactly
 * (verified via bytecode comparison), only swapping the sampler for a
 * CLAMP_TO_EDGE / LINEAR / LINEAR one. CLAMP_TO_EDGE is used instead of REPEAT
 * because screenshot thumbnails are single images, not tiled patterns — clamping
 * prevents any edge-wrap artifacts at UV boundaries.
 * <p>
 * Must only be constructed/closed on the render thread (same constraint as
 * DynamicTexture — it touches the GPU device).
 */
public final class SmoothDynamicTexture extends AbstractTexture {

    private NativeImage pixels;

    /**
     * Creates the GL texture, allocates RGBA8 storage, uploads the pixel data,
     * and binds a LINEAR/CLAMP_TO_EDGE sampler — all in one call, mirroring
     * DynamicTexture's constructor behavior.
     *
     * @param debugName supplier for GL debug labels (visible in RenderDoc etc.)
     * @param pixels    the NativeImage to upload; ownership transfers to this
     *                  texture (it will be closed when the texture is closed)
     */
    public SmoothDynamicTexture(Supplier<String> debugName, NativeImage pixels) {
        this.pixels = pixels;
        createTexture(debugName);
        upload();
    }

    private void createTexture(Supplier<String> debugName) {
        // usage = 5 = USAGE_COPY_DST (1) | USAGE_TEXTURE_BINDING (4)
        // — needed for writeToTexture and for binding as a shader sampler.
        // mipLevels = 1 — no mipmaps (thumbnails are small enough that
        // LINEAR filtering alone is sufficient; mipmaps would add complexity
        // and memory overhead without visible quality improvement at these sizes).
        this.texture = RenderSystem.getDevice().createTexture(
                debugName,
                5,
                TextureFormat.RGBA8,
                pixels.getWidth(),
                pixels.getHeight(),
                1,  // depth
                1   // mipLevels
        );

        // THE KEY DIFFERENCE from DynamicTexture:
        // DynamicTexture uses: getRepeat(FilterMode.NEAREST)  → NEAREST/NEAREST + REPEAT/REPEAT
        // SmoothDynamicTexture uses: getClampToEdge(FilterMode.LINEAR) → LINEAR/LINEAR + CLAMP/CLAMP
        //
        // LINEAR minification: smooths the texture when it's drawn smaller than its native size
        // LINEAR magnification: smooths the texture when it's drawn larger than its native size
        // CLAMP_TO_EDGE: prevents edge wrapping artifacts at UV (0,0)-(1,1) boundaries
        this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);

        this.textureView = RenderSystem.getDevice().createTextureView(this.texture);
    }

    /**
     * Pushes the NativeImage pixel data to the GPU via
     * {@code CommandEncoder.writeToTexture} → {@code glTexSubImage2D}.
     * Called automatically by the constructor.
     */
    public void upload() {
        if (this.texture != null) {
            RenderSystem.getDevice().createCommandEncoder()
                    .writeToTexture(this.texture, this.pixels);
        }
    }

    public NativeImage getPixels() {
        return pixels;
    }

    public void setPixels(NativeImage newPixels) {
        if (this.pixels != null) {
            this.pixels.close();
        }
        this.pixels = newPixels;
    }

    @Override
    public void close() {
        if (this.pixels != null) {
            this.pixels.close();
            this.pixels = null;
        }
        super.close();
    }
}
