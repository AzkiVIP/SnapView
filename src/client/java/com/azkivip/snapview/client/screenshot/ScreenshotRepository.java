package com.azkivip.snapview.client.screenshot;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Owns the list of known screenshots.
 * <p>
 * Directory scanning only reads filesystem metadata (name, size, mtime) -
 * never image bytes - so it stays fast even with several thousand files.
 * Scanning always happens off the render thread; callers get the result
 * via a callback dispatched back onto whatever executor they provide
 * (normally the Minecraft client thread).
 */
public final class ScreenshotRepository {

	private static final Executor IO_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "SnapView-DirectoryScan");
		thread.setDaemon(true);
		return thread;
	});

	private final Path screenshotsDirectory;
	private final List<ScreenshotEntry> entries = new CopyOnWriteArrayList<>();
	private volatile boolean loaded = false;

	public ScreenshotRepository(Path screenshotsDirectory) {
		this.screenshotsDirectory = screenshotsDirectory;
	}

	public boolean isLoaded() {
		return loaded;
	}

	/** Current snapshot of known screenshots, newest first. Safe to call from the render thread. */
	public List<ScreenshotEntry> entries() {
		return entries;
	}

	/**
	 * Scans the screenshots directory on a background thread and invokes
	 * {@code onComplete} (on {@code resultExecutor}) once done. Never blocks
	 * the caller.
	 */
	public void refreshAsync(Executor resultExecutor, Consumer<List<ScreenshotEntry>> onComplete) {
		CompletableFuture.supplyAsync(this::scanDirectory, IO_EXECUTOR)
				.whenComplete((result, throwable) -> {
					if (throwable != null) {
						resultExecutor.execute(() -> onComplete.accept(Collections.emptyList()));
						return;
					}
					entries.clear();
					entries.addAll(result);
					loaded = true;
					resultExecutor.execute(() -> onComplete.accept(entries));
				});
	}

	private List<ScreenshotEntry> scanDirectory() {
		List<ScreenshotEntry> result = new ArrayList<>();

		if (!Files.isDirectory(screenshotsDirectory)) {
			return result;
		}

		try (DirectoryStream<Path> stream = Files.newDirectoryStream(screenshotsDirectory, "*.png")) {
			for (Path path : stream) {
				result.add(ScreenshotEntry.of(path));
			}
		} catch (IOException e) {
			return Collections.emptyList();
		}

		// Newest first - this is what most players expect when opening the gallery.
		result.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
		return result;
	}

	/** Updates the in-memory index after a successful rename, without a full re-scan. */
	public void replaceEntry(ScreenshotEntry oldEntry, ScreenshotEntry newEntry) {
		int index = entries.indexOf(oldEntry);
		if (index >= 0) {
			entries.set(index, newEntry);
		}
	}

	/** Removes an entry from the in-memory index (e.g. after deletion) without a full re-scan. */
	public void removeEntry(ScreenshotEntry entry) {
		entries.remove(entry);
	}

	/**
	 * Best-effort lookup of the most recently written screenshot file.
	 * Used by the chat-message hook to figure out which file a vanilla
	 * "screenshot saved" message refers to, without depending on the
	 * message's internal structure at all.
	 */
	public ScreenshotEntry findNewestOnDisk() {
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(screenshotsDirectory, "*.png")) {
			ScreenshotEntry newest = null;
			for (Path path : stream) {
				ScreenshotEntry candidate = ScreenshotEntry.of(path);
				if (newest == null || candidate.lastModified() > newest.lastModified()) {
					newest = candidate;
				}
			}
			return newest;
		} catch (IOException e) {
			return null;
		}
	}

	public Path screenshotsDirectory() {
		return screenshotsDirectory;
	}
}
