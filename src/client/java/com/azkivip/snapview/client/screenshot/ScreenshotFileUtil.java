package com.azkivip.snapview.client.screenshot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Pattern;

/**
 * Filename validation and on-disk renaming.
 * <p>
 * Renaming always changes the real file on disk (never just a display
 * label) so the gallery, the OS file browser and the vanilla screenshots
 * folder never disagree about what a file is called.
 */
public final class ScreenshotFileUtil {

	// Characters illegal on Windows (the strictest of the three major OSes);
	// validating against this superset keeps renamed files portable everywhere.
	private static final Pattern ILLEGAL_CHARACTERS = Pattern.compile("[<>:\"/\\\\|?*\\x00-\\x1F]");
	private static final Pattern RESERVED_WINDOWS_NAMES = Pattern.compile(
			"(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?$"
	);

	private ScreenshotFileUtil() {
	}

	public sealed interface ValidationResult
			permits ValidationResult.Valid, ValidationResult.Empty, ValidationResult.Invalid, ValidationResult.Exists {
		record Valid(String normalizedName) implements ValidationResult {}
		record Empty() implements ValidationResult {}
		record Invalid() implements ValidationResult {}
		record Exists() implements ValidationResult {}
	}

	/**
	 * Validates a user-provided name (without extension) against illegal
	 * characters, reserved OS names, length limits, and collisions with an
	 * existing file in the same directory.
	 */
	public static ValidationResult validate(String rawName, Path directory, String currentFileName) {
		String trimmed = rawName == null ? "" : rawName.trim();

		if (trimmed.isEmpty()) {
			return new ValidationResult.Empty();
		}

		if (ILLEGAL_CHARACTERS.matcher(trimmed).find() || RESERVED_WINDOWS_NAMES.matcher(trimmed).matches()) {
			return new ValidationResult.Invalid();
		}

		// Trailing dots/spaces are silently stripped by Windows and can cause
		// confusing mismatches between what the user typed and what got saved.
		String normalized = trimmed.replaceAll("[.\\s]+$", "");
		if (normalized.isEmpty()) {
			return new ValidationResult.Invalid();
		}

		// Keep the total path reasonably short across filesystems.
		if (normalized.length() > 200) {
			normalized = normalized.substring(0, 200);
		}

		String candidateFileName = normalized + ".png";
		if (!candidateFileName.equalsIgnoreCase(currentFileName)) {
			Path candidatePath = directory.resolve(candidateFileName);
			if (Files.exists(candidatePath)) {
				return new ValidationResult.Exists();
			}
		}

		return new ValidationResult.Valid(normalized);
	}

	/**
	 * Renames the file on disk. Throws {@link IOException} on failure so the
	 * caller can surface a concrete error message instead of silently
	 * leaving the gallery out of sync with the filesystem.
	 */
	public static ScreenshotEntry rename(ScreenshotEntry entry, String newBaseName) throws IOException {
		Path target = entry.path().resolveSibling(newBaseName + ".png");
		Path moved = Files.move(entry.path(), target, StandardCopyOption.ATOMIC_MOVE);
		return ScreenshotEntry.of(moved);
	}
}
