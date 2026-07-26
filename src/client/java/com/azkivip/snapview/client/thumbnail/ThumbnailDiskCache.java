package com.azkivip.snapview.client.thumbnail;

import com.azkivip.snapview.client.screenshot.ScreenshotEntry;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Persists downscaled thumbnails to disk so re-opening the gallery on a
 * later session doesn't require re-decoding every full-resolution
 * screenshot again.
 * <p>
 * Cache files are named after a hash of {@link ScreenshotEntry#cacheKey()}
 * (filename + last-modified + size). If the source file changes on disk -
 * including through SnapView's own rename feature - the key changes and a
 * fresh thumbnail is generated automatically; the old cache file is simply
 * never looked up again. There is deliberately no separate JSON index to
 * keep this simple and impossible to get out of sync with what's on disk.
 */
public final class ThumbnailDiskCache {

	private final Path cacheDirectory;

	public ThumbnailDiskCache(Path cacheDirectory) {
		this.cacheDirectory = cacheDirectory;
		try {
			Files.createDirectories(cacheDirectory);
		} catch (IOException ignored) {
			// If we can't create the cache dir, every load simply falls back
			// to regenerating from the source screenshot each time.
		}
	}

	private Path cacheFileFor(ScreenshotEntry entry) {
		return cacheDirectory.resolve(hash(entry.cacheKey()) + ".png");
	}

	public boolean has(ScreenshotEntry entry) {
		return Files.isRegularFile(cacheFileFor(entry));
	}

	public BufferedImage read(ScreenshotEntry entry) throws IOException {
		return ImageIO.read(cacheFileFor(entry).toFile());
	}

	public void write(ScreenshotEntry entry, BufferedImage thumbnail) {
		try {
			ImageIO.write(thumbnail, "png", cacheFileFor(entry).toFile());
		} catch (IOException ignored) {
			// Non-fatal: the thumbnail is still usable this session, it just
			// won't be cached for next time.
		}
	}

	private static String hash(String key) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-1");
			byte[] bytes = digest.digest(key.getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder(bytes.length * 2);
			for (byte b : bytes) {
				sb.append(String.format("%02x", b));
			}
			return sb.toString();
		} catch (NoSuchAlgorithmException e) {
			// SHA-1 is always available on the JVM; this is unreachable in practice.
			return Integer.toHexString(key.hashCode());
		}
	}
}
