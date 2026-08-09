package com.azkivip.snapview.client.clipboard;

import com.azkivip.snapview.client.SnapViewClient;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Copies a screenshot to the system clipboard as an image.
 * <p>
 * <b>Architecture</b>: Uses pre-installed OS commands (PowerShell on Windows,
 * osascript on macOS, xclip/wl-copy on Linux) to write images to the clipboard.
 * <p>
 * <b>Why not AWT</b>: Minecraft's {@code Main} class sets
 * {@code java.awt.headless=true} at JVM startup (in {@code Main.<clinit>}),
 * before any mod code runs. This forces AWT into headless mode —
 * {@code Toolkit.getDefaultToolkit()} returns a {@code HeadlessToolkit} whose
 * {@code getSystemClipboard()} throws {@code HeadlessException}. This cannot
 * be worked around because Java module security blocks reflection on the
 * relevant AWT internal classes, and the headless flag is cached before any
 * mod code executes.
 * <p>
 * <b>Performance</b>: The image is cached by the viewer (see
 * {@code ScreenshotViewerScreen.currentImage}) and passed directly to avoid
 * re-reading the PNG from disk. The native command (PowerShell) takes ~1-2s
 * on first run but this is the only reliable way to access the image clipboard
 * from within a Minecraft JVM.
 * <p>
 * <b>Threading</b>: All clipboard work runs on a background daemon thread
 * ("SnapView-Clipboard"), never on the render thread. The result callback is
 * dispatched back to the provided executor (normally the MC client thread).
 */
public final class ClipboardService {

    private static final Executor CLIPBOARD_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SnapView-Clipboard");
        thread.setDaemon(true);
        return thread;
    });

    private ClipboardService() {
    }

    /**
     * Copies the image at {@code imagePath} to the system clipboard.
     * {@code onResult} is invoked on {@code resultExecutor} with an empty
     * string on success, or a non-empty error message on failure.
     */
    public static void copyImageAsync(Path imagePath, Executor resultExecutor, Consumer<String> onResult) {
        CompletableFuture
                .supplyAsync(() -> copyImageBlocking(imagePath, null), CLIPBOARD_EXECUTOR)
                .whenComplete((errorMessage, throwable) ->
                        resultExecutor.execute(() -> {
                            if (throwable != null) {
                                SnapViewClient.LOGGER.error("Clipboard copy threw unexpected exception", throwable);
                                onResult.accept("Unexpected error: " + throwable.getMessage());
                            } else {
                                onResult.accept(errorMessage == null ? "" : errorMessage);
                            }
                        }));
    }

    /**
     * Copies a pre-loaded {@code BufferedImage} to the system clipboard.
     * This avoids re-reading the PNG file from disk when the image is already
     * in memory. The {@code imagePath} is used by the native command to read
     * the file (the native command runs in a separate process and can't
     * access the JVM's BufferedImage directly).
     */
    public static void copyImageAsync(BufferedImage image, Path imagePath,
                                       Executor resultExecutor, Consumer<String> onResult) {
        CompletableFuture
                .supplyAsync(() -> copyImageBlocking(imagePath, image), CLIPBOARD_EXECUTOR)
                .whenComplete((errorMessage, throwable) ->
                        resultExecutor.execute(() -> {
                            if (throwable != null) {
                                SnapViewClient.LOGGER.error("Clipboard copy threw unexpected exception", throwable);
                                onResult.accept("Unexpected error: " + throwable.getMessage());
                            } else {
                                onResult.accept(errorMessage == null ? "" : errorMessage);
                            }
                        }));
    }

    /**
     * @deprecated use {@link #copyImageAsync(Path, Executor, Consumer)} instead
     */
    @Deprecated
    public static void copyImageAsync(Path imagePath, Executor resultExecutor, Consumer<Boolean> onResult, boolean legacy) {
        copyImageAsync(imagePath, resultExecutor, errorMessage -> onResult.accept(errorMessage == null || errorMessage.isEmpty()));
    }

    /**
     * Copies the image to the clipboard using the platform's native command.
     * Returns an empty string on success, or a non-empty error message.
     * <p>
     * Timing breakdown is logged at INFO level for performance diagnosis:
     * <ul>
     *   <li>File check — O(1) filesystem stat</li>
     *   <li>Native command — includes process spawn + PowerShell cold-start +
     *       .NET assembly load + Image.FromFile + Clipboard.SetImage</li>
     * </ul>
     * The dominant cost is the native command (PowerShell cold-start ~1-2s on
     * Windows). The JVM-side image read/decode is avoided when the viewer
     * passes a cached BufferedImage (though the native command still reads
     * the file from disk independently — this is unavoidable since the native
     * command runs in a separate process).
     */
    private static String copyImageBlocking(Path imagePath, BufferedImage cachedImage) {
        long totalStart = System.nanoTime();

        // Step 1: File existence check (O(1) stat, ~0ms)
        long checkStart = System.nanoTime();
        if (!Files.isRegularFile(imagePath)) {
            return "Screenshot file does not exist: " + imagePath.toAbsolutePath();
        }
        long checkMs = (System.nanoTime() - checkStart) / 1_000_000;

        // Step 2: Native command (process spawn + PowerShell cold-start + clipboard write)
        long cmdStart = System.nanoTime();
        String result = NativeClipboardFallback.copyImage(imagePath, cachedImage);
        long cmdMs = (System.nanoTime() - cmdStart) / 1_000_000;
        long totalMs = (System.nanoTime() - totalStart) / 1_000_000;

        // Log whether the JVM-side image was cached (affects JVM-side read time,
        // but NOT the native command time — the native command always reads
        // the file from disk independently)
        String imageSource = cachedImage != null ? "cached (JVM)" : "not cached (JVM reads from disk)";

        if (result == null || result.isEmpty()) {
            SnapViewClient.LOGGER.info("Clipboard copy succeeded. Total: {}ms (file check: {}ms, native command: {}ms, image: {})",
                    totalMs, checkMs, cmdMs, imageSource);
        } else {
            SnapViewClient.LOGGER.warn("Clipboard copy failed. Total: {}ms (file check: {}ms, native command: {}ms, image: {}): {}",
                    totalMs, checkMs, cmdMs, imageSource, result);
        }

        return result == null ? "" : result;
    }
}
