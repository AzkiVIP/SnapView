package com.azkivip.snapview.client.chat;

import com.azkivip.snapview.client.screenshot.ScreenshotEntry;
import com.azkivip.snapview.client.screenshot.ScreenshotRepository;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Polls the screenshots directory on a regular interval and fires a
 * callback when a new screenshot file appears.
 * <p>
 * This is used instead of hooking the vanilla "screenshot.success" chat
 * message because:
 * <ul>
 *   <li>The vanilla message is added via ChatComponent.addClientSystemMessage(),
 *       which does NOT fire ClientReceiveMessageEvents (that event only
 *       covers server-originated messages).</li>
 *   <li>Polling works regardless of how the screenshot was triggered
 *       (F2, external tool, mod, etc.).</li>
 *   <li>It doesn't depend on the vanilla message format or translation key.</li>
 * </ul>
 * <p>
 * The mixin (ChatComponentMixin) suppresses the vanilla message so the user
 * only sees our custom [Copy] [Open Gallery] message.
 */
public final class ScreenshotWatcher {

    private static final int POLL_INTERVAL_TICKS = 5; // 0.25 seconds

    private final ScreenshotRepository repository;
    private final ScreenshotChatHandler chatHandler;
    private final Set<String> knownFiles = ConcurrentHashMap.newKeySet();
    private int tickCounter = 0;
    private boolean initialized = false;

    public ScreenshotWatcher(ScreenshotRepository repository, ScreenshotChatHandler chatHandler) {
        this.repository = repository;
        this.chatHandler = chatHandler;
    }

    /**
     * Called every client tick. Performs a directory scan every
     * POLL_INTERVAL_TICKS ticks and fires the callback for new files.
     */
    public void tick() {
        tickCounter++;
        if (tickCounter < POLL_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;

        // Note: we intentionally do NOT skip when a screen is open.
        // F2 works while screens are open (e.g., while chat is open), and
        // skipping would cause us to miss screenshots taken in those moments.
        scanForNewScreenshots();
    }

    private void scanForNewScreenshots() {
        Path screenshotsDir = repository.screenshotsDirectory();
        if (!Files.isDirectory(screenshotsDir)) {
            return;
        }

        // On first run, populate the known set without firing notifications.
        // This prevents spamming chat for all existing screenshots when the
        // mod first loads.
        if (!initialized) {
            initialized = true;
            knownFiles.addAll(scanDirectory());
            return;
        }

        Set<String> currentFiles = scanDirectory();

        // Find new files
        for (String fileName : currentFiles) {
            if (knownFiles.add(fileName)) {
                // New screenshot detected!
                onNewScreenshot(fileName);
            }
        }

        // Remove deleted files from the known set
        knownFiles.retainAll(currentFiles);
    }

    private Set<String> scanDirectory() {
        Set<String> result = new HashSet<>();
        Path screenshotsDir = repository.screenshotsDirectory();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(screenshotsDir, "*.png")) {
            for (Path path : stream) {
                result.add(path.getFileName().toString());
            }
        } catch (Exception e) {
            // Ignore - we'll retry next poll
        }

        return result;
    }

    private void onNewScreenshot(String fileName) {
        Path screenshotPath = repository.screenshotsDirectory().resolve(fileName);
        if (!Files.isRegularFile(screenshotPath)) {
            return;
        }

        ScreenshotEntry entry = ScreenshotEntry.of(screenshotPath);

        // Check if an entry with this path already exists in the repository.
        // This can happen after a rename: the ScreenshotWatcher detects the
        // "new" file name on disk, but repository.replaceEntry() already
        // updated the existing entry in-place. Adding it again would create
        // a duplicate gallery entry.
        for (ScreenshotEntry existing : repository.entries()) {
            if (existing.path().equals(screenshotPath)) {
                return; // Already in the repository — don't duplicate
            }
        }

        // Send the custom chat message with [Copy] and [Open Gallery] actions
        chatHandler.sendScreenshotSavedMessage(entry);

        // Add the entry to the repository's in-memory list so it appears
        // in the gallery without needing a manual refresh
        repository.entries().add(0, entry);
    }
}
