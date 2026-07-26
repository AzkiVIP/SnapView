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

	/**
	 * Returns the cache file for an explicit (already size-encoded) key.
	 * Used by ThumbnailLoader when the target thumbnail size varies.
	 */
	public Path cacheFileForSizedKey(String sizedKey) {
		return cacheDirectory.resolve(hash(sizedKey) + ".png");
	}

	public void writeToPath(String sizedKey, BufferedImage thumbnail) {
		try {
			ImageIO.write(thumbnail, "png", cacheFileForSizedKey(sizedKey).toFile());
		} catch (IOException ignored) {
			// Non-fatal: the thumbnail is still usable this session.
		}
	}

	/**
	 * Deletes the cached thumbnail for a given screenshot entry (base size key).
	 */
	public void deleteForEntry(ScreenshotEntry entry) {
		try {
			java.nio.file.Files.deleteIfExists(cacheFileFor(entry));
		} catch (IOException ignored) {
			// Best-effort; stale cache files are harmless.
		}
	}

	/**
	 * Clears the entire thumbnail cache directory.
	 */
	public void clearAll() {
		try (var stream = java.nio.file.Files.newDirectoryStream(cacheDirectory, "*.png")) {
			for (java.nio.file.Path path : stream) {
				try {
					java.nio.file.Files.deleteIfExists(path);
				} catch (IOException ignored) {
					// Individual file deletion failures are non-fatal.
				}
			}
		} catch (IOException ignored) {
			// If we can't list the directory, there's nothing to clear.
		}
	}

	/**
	 * Returns the total size in bytes of all cache files.
	 */
	public long cacheSizeBytes() {
		long total = 0;
		try (var stream = java.nio.file.Files.newDirectoryStream(cacheDirectory, "*.png")) {
			for (java.nio.file.Path path : stream) {
				try {
					total += java.nio.file.Files.size(path);
				} catch (IOException ignored) {
					// Skip unreadable files.
				}
			}
		} catch (IOException ignored) {
			// Directory not accessible.
		}
		return total;
	}

	public int cacheFileCount() {
		int count = 0;
		try (var stream = java.nio.file.Files.newDirectoryStream(cacheDirectory, "*.png")) {
			for (java.nio.file.Path ignored : stream) {
				count++;
			}
		} catch (IOException ignored) {
			// Directory not accessible.
		}
		return count;
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
