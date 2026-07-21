package com.azkivip.snapview.client.screenshot;

import java.nio.file.Path;

/**
 * Lightweight metadata for a screenshot on disk.
 * <p>
 * Deliberately holds no image data - this is what lets the repository keep
 * thousands of entries in memory at almost no cost. Image bytes are only
 * touched by {@link com.azkivip.snapview.client.thumbnail.ThumbnailLoader}
 * and only for entries currently near the viewport.
 */
public record ScreenshotEntry(Path path, String fileName, long lastModified, long sizeBytes) {

	public static ScreenshotEntry of(Path path) {
		try {
			var attrs = java.nio.file.Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class);
			return new ScreenshotEntry(
					path,
					path.getFileName().toString(),
					attrs.lastModifiedTime().toMillis(),
					attrs.size()
			);
		} catch (java.io.IOException e) {
			// File may have been deleted/renamed concurrently while scanning; fall back to
			// zeroed metadata rather than failing the whole directory scan.
			return new ScreenshotEntry(path, path.getFileName().toString(), 0L, 0L);
		}
	}

	/** Cache key that changes whenever the underlying file content is likely to have changed. */
	public String cacheKey() {
		return fileName + ":" + lastModified + ":" + sizeBytes;
	}
}
