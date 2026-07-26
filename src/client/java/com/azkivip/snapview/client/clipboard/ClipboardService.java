package com.azkivip.snapview.client.clipboard;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Copies a screenshot to the system clipboard as an image.
 * <p>
 * GLFW/Minecraft only expose a text clipboard, so this goes through
 * {@code java.awt.datatransfer} instead. AWT's toolkit init on first use
 * can stall, and clipboard image support is inconsistent on some
 * Linux/Wayland setups - both reasons this always runs on a background
 * thread and always reports success/failure back to the caller.
 * <p>
 * All exceptions are logged at WARN level so the user can see the actual
 * error in the logs (not just a generic "failed" message in chat).
 */
public final class ClipboardService {

    private static final Logger LOGGER = LoggerFactory.getLogger("SnapView/Clipboard");

    private static final Executor CLIPBOARD_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SnapView-Clipboard");
        thread.setDaemon(true);
        return thread;
    });

    private ClipboardService() {
    }

    public static void copyImageAsync(Path imagePath, Executor resultExecutor, Consumer<Boolean> onResult) {
        CompletableFuture
                .supplyAsync(() -> copyImageBlocking(imagePath), CLIPBOARD_EXECUTOR)
                .whenComplete((success, throwable) ->
                        resultExecutor.execute(() -> onResult.accept(throwable == null && success)));
    }

    private static boolean copyImageBlocking(Path imagePath) {
        try {
            BufferedImage image = ImageIO.read(imagePath.toFile());
            if (image == null) {
                LOGGER.warn("Failed to read image for clipboard: ImageIO.read returned null for {}", imagePath);
                return false;
            }

            // Some Linux/Wayland environments don't have a working AWT clipboard.
            // Toolkit.getDefaultToolkit() can throw AWTError or
            // InternalError if there's no display. Catch everything and log it.
            Toolkit toolkit = Toolkit.getDefaultToolkit();
            if (toolkit == null) {
                LOGGER.warn("AWT Toolkit.getDefaultToolkit() returned null — no clipboard available");
                return false;
            }

            Clipboard clipboard = toolkit.getSystemClipboard();
            if (clipboard == null) {
                LOGGER.warn("getSystemClipboard() returned null — no clipboard available");
                return false;
            }

            clipboard.setContents(new ImageTransferable(image), null);
            LOGGER.info("Screenshot copied to clipboard: {}", imagePath.getFileName());
            return true;
        } catch (Throwable e) {
            // Catch Throwable (not just Exception) because AWT can throw
            // AWTError, InternalError, NoClassDefFoundError, etc. on headless
            // or display-less environments. Log the full exception so the
            // user can see the actual cause in the logs.
            LOGGER.warn("Failed to copy screenshot to clipboard: " + imagePath, e);
            return false;
        }
    }

    private record ImageTransferable(BufferedImage image) implements Transferable {

        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[]{DataFlavor.imageFlavor};
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return DataFlavor.imageFlavor.equals(flavor);
        }

        @Override
        public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (!isDataFlavorSupported(flavor)) {
                throw new UnsupportedFlavorException(flavor);
            }
            return image;
        }
    }
}
