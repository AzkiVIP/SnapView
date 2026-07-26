package com.azkivip.snapview.client.clipboard;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Copies a screenshot to the system clipboard as an image.
 * <p>
 * GLFW/Minecraft only expose a text clipboard, so this goes through
 * {@code java.awt.datatransfer} instead.
 * <p>
 * IMPORTANT: The exception is NEVER silently swallowed. Every failure path
 * logs the full stacktrace so the root cause is visible in the game log.
 */
public final class ClipboardService {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("SnapView/Clipboard");

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
        Exception caughtException = null;

        try {
            // 1. Verify the file exists
            if (!java.nio.file.Files.exists(imagePath)) {
                LOGGER.error("Clipboard: file does not exist: {}", imagePath);
                return false;
            }

            // 2. Read the image
            BufferedImage image = ImageIO.read(imagePath.toFile());
            if (image == null) {
                LOGGER.error("Clipboard: ImageIO.read returned null for {}", imagePath);
                return false;
            }

            LOGGER.info("Clipboard: loaded image {}x{} from {}", image.getWidth(), image.getHeight(), imagePath.getFileName());

            // 3. Check for headless mode — this is the most common cause of
            //    "Could not copy" failures. Minecraft runs on GLFW/OpenGL and
            //    the JVM may have initialized AWT in headless mode.
            if (GraphicsEnvironment.isHeadless()) {
                LOGGER.error("Clipboard: GraphicsEnvironment.isHeadless() returned true — AWT clipboard is unavailable in this environment. " +
                        "On Linux, ensure a DISPLAY is available or run with -Djava.awt.headless=false");
                return false;
            }

            // 4. Get the system clipboard
            Toolkit toolkit = Toolkit.getDefaultToolkit();
            Clipboard clipboard = toolkit.getSystemClipboard();
            if (clipboard == null) {
                LOGGER.error("Clipboard: Toolkit.getDefaultToolkit().getSystemClipboard() returned null");
                return false;
            }

            // 5. Set the clipboard contents with both imageFlavor AND image/png MIME flavor.
            //    Many external apps (Discord, browsers, Telegram) only accept the
            //    image/png MIME flavor, not DataFlavor.imageFlavor alone.
            clipboard.setContents(new ImageTransferable(image), null);

            LOGGER.info("Clipboard: image successfully copied to system clipboard");
            return true;

        } catch (HeadlessException e) {
            caughtException = e;
            LOGGER.error("Clipboard: HeadlessException — the JVM is running in headless mode. " +
                    "AWT clipboard operations are impossible. " +
                    "Fix: run Minecraft with -Djava.awt.headless=false, or ensure a display is available.", e);
        } catch (IllegalStateException e) {
            caughtException = e;
            LOGGER.error("Clipboard: IllegalStateException — the clipboard is currently unavailable " +
                    "(possibly held by another application, or a platform limitation on Wayland).", e);
        } catch (SecurityException e) {
            caughtException = e;
            LOGGER.error("Clipboard: SecurityException — the JVM security manager blocked clipboard access.", e);
        } catch (Exception e) {
            caughtException = e;
            LOGGER.error("Clipboard: unexpected exception while copying image to clipboard", e);
        }

        return false;
    }

    /**
     * Transferable that provides the image in BOTH DataFlavor.imageFlavor
     * (for Java apps and image editors) AND image/png MIME flavor (for
     * external apps like Discord, browsers, Telegram that expect a PNG
     * stream).
     */
    private record ImageTransferable(BufferedImage image) implements Transferable {

        private static final DataFlavor PNG_FLAVOR = new DataFlavor("image/png", "PNG Image");

        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[]{DataFlavor.imageFlavor, PNG_FLAVOR};
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return DataFlavor.imageFlavor.equals(flavor) || PNG_FLAVOR.equals(flavor);
        }

        @Override
        public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException, IOException {
            if (DataFlavor.imageFlavor.equals(flavor)) {
                return image;
            }
            if (PNG_FLAVOR.equals(flavor)) {
                // Re-encode the image as PNG bytes so external apps can read it
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(image, "png", baos);
                return new java.io.ByteArrayInputStream(baos.toByteArray());
            }
            throw new UnsupportedFlavorException(flavor);
        }
    }
}
