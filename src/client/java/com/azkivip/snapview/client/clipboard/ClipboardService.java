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

/**
 * Copies a screenshot to the system clipboard as an image.
 * <p>
 * GLFW/Minecraft only expose a text clipboard, so this goes through
 * {@code java.awt.datatransfer} instead. AWT's toolkit init on first use
 * can stall for tens to hundreds of milliseconds, and clipboard image
 * support is inconsistent on some Linux/Wayland setups - both reasons this
 * always runs on a background thread and always reports success/failure
 * back to the caller rather than assuming it worked.
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
	 * Attempts to copy the image at {@code imagePath} to the system
	 * clipboard. {@code onResult} is invoked on {@code resultExecutor}
	 * (normally the client thread) with {@code true} on success.
	 */
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
				return false;
			}

			Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
			clipboard.setContents(new ImageTransferable(image), null);
			return true;
		} catch (IOException | HeadlessException | IllegalStateException e) {
			// IllegalStateException covers clipboard-busy races on some platforms;
			// HeadlessException covers CI/server-like environments with no AWT display.
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
