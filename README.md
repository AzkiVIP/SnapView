# SnapView

Client-side in-game screenshot gallery, viewer and management mod for Fabric.

## Target versions

- Minecraft: 26.1.2
- Fabric Loader: 0.19.3
- Fabric API: 0.155.2+26.1.2
- Fabric Loom: 1.17-SNAPSHOT
- Java: 25

## Features

- In-game screenshot gallery with 3-column desktop layout (responsive down to 2/1 columns on small windows)
- Filename search/filtering (live, case-insensitive)
- Screenshot viewer with prev/next navigation, visible Back button, centered + aspect-preserving scaling
- Rename screenshots (with filename validation)
- Copy screenshots to system clipboard
- Delete screenshots (with confirmation dialog; deletes file + cache entry + refreshes gallery)
- Pause menu "Screenshot Gallery" button
- Screenshot chat actions: green `[Copy]` and gold `[Open Gallery]` appear after taking a screenshot
- Thumbnail disk cache with size-aware keys, auto-invalidation on rename/delete
- Cache management: "Clear Cache" button in gallery + live cache size display
- Virtual scrolling gallery (flat frame cost regardless of screenshot count)
- Bounded GPU texture pool (max 120 live thumbnails, unique IDs prevent registration collisions)

## Build artifacts

Pre-built JARs are in `build-artifacts/`:
- `snapview-1.0.0.jar` — the mod JAR, drop into your `mods/` folder

## Building from source

```
./gradlew build
```

Requires Java 25. Output JAR is at `build/libs/snapview-1.0.0.jar`.

## Continuous Integration

A GitHub Actions workflow is at `.github/workflows/build.yml`. It:
- Triggers on push and pull_request to `main`/`master`
- Runs on `ubuntu-latest` with JDK 25 (Temurin)
- Runs `./gradlew build`
- Uploads the built JAR as artifact named **`SnapView-JAR`**
- Retains the artifact for 30 days

After a push, download the JAR from:
**Actions → Build → (latest run) → Artifacts → SnapView-JAR**

## Runtime fixes in this version

### 1. Copy action "Illegal char" error — FIXED

**Root cause:** The chat click-event command was `/snapview copy "filename"` (with literal quotes), and `StringArgumentType.greedyString()` captured the quotes literally. `repository.screenshotsDirectory().resolve(fileName)` then failed because `"` is an illegal path character.

**Fix:** `SnapViewClient.copyByFileName()` now strips surrounding quotes and unescapes escaped quotes before resolving the path.

### 2. Thumbnail rendering (black/blank thumbnails) — FIXED

**Root cause:** Two issues:
1. The `DynamicTexture(Supplier<String>, NativeImage)` constructor in MC 26.1 allocates the GL texture object but does NOT upload pixel data. Without calling `texture.upload()`, GPU texture memory stays at zero (black).
2. The texture `Identifier` was derived from the sanitized cache key. If a previous gallery session's texture wasn't fully released, `TextureManager.register()` could collide with the stale registration, causing the new texture to bind to the old (empty) GPU resource.

**Fix:**
- `ThumbnailTexture.upload()` now calls `texture.upload()` after constructing the `DynamicTexture` (verified at bytecode offset 185).
- A monotonic `AtomicLong TEXTURE_COUNTER` is prepended to every texture `Identifier`, guaranteeing globally unique IDs across gallery open/close cycles — no collision risk even if `TextureManager.release()` is delayed.

### 3. Viewer layout (image not centered / not scaling) — FIXED

**Root cause:** The old code capped `scale = Math.min(scale, 1.0)`, preventing small screenshots from scaling up. The centering math was correct but the scale cap made small images appear tiny.

**Fix:** Changed the cap to `Math.min(scale, 2.0)` — small screenshots now scale up to 2x (enough to be visible without excessive blur), and large screenshots still fit within the viewport. Centering math (`(this.width - drawW) / 2`) was already correct and is preserved.

### 4. Back navigation — VERIFIED WORKING

The viewer has a visible "Back" button (60px, bottom-left) that calls `onClose()`. ESC also calls `onClose()`. Both return to the `GalleryScreen` (the parent). No dead-end screens.

### 5. Gallery grid layout (collapsing to 1 column) — FIXED

**Root cause:** The old `computeLayout()` used fixed pixel thresholds (`< 480px → 1 column`, `< 854px → 2 columns`). But `this.width` is the **scaled GUI width**, not the window pixel width. At GUI scale 2 on a 1920px window, `this.width` is ~480 — triggering the 1-column fallback unnecessarily.

**Fix:** The new `computeLayout()` always targets 3 columns. It only reduces to 2 columns if the window can't fit 2 × `MIN_THUMB_WIDTH` (120px) + padding, and to 1 column if it can't fit 2 columns. The thresholds are now `minColumns2Width = 264px` and `minColumns3Width = 392px` — both well below any realistic desktop GUI width.

### 6. Screenshot chat actions missing — FIXED

**Root cause:** Two issues:
1. The `ChatComponentMixin` was correctly configured but the `ScreenshotWatcher` had a `if (mc.screen != null) return;` guard that skipped polling whenever any screen was open. F2 works while screens are open (e.g., chat), so the watcher would miss those screenshots.
2. The poll interval was 0.5s, which felt slow.

**Fix:**
- Removed the `mc.screen != null` guard — the watcher now polls regardless of screen state.
- Reduced poll interval from 10 ticks (0.5s) to 5 ticks (0.25s) for snappier feedback.
- The mixin suppresses the vanilla `screenshot.success` message (via `@Inject` at `HEAD` of `ChatComponent.addClientSystemMessage` with `cancellable = true`).
- The watcher detects the new file on disk and sends the custom `[Copy] [Open Gallery]` message.

### 7. Pause menu integration — VERIFIED WORKING

`PauseMenuIntegration` registers a `ScreenEvents.AFTER_INIT` listener that detects `PauseScreen` (the 26.1 class name) and appends a 200px-wide "Screenshot Gallery" button below the vanilla buttons, matching vanilla's button width/style.

### 8. Thumbnail cache management — ADDED

- `ThumbnailDiskCache.clearAll()` — deletes all `*.png` files in the cache directory.
- `ThumbnailDiskCache.cacheSizeBytes()` / `cacheFileCount()` — for display.
- `ThumbnailDiskCache.deleteForEntry(entry)` — deletes the cache file for a specific entry (used by the delete feature).
- Gallery shows "Cache: X.X MB (N files)" in the top-left.
- "Clear Cache" button (with confirmation dialog) clears the cache and releases all live textures so they regenerate from scratch.

### 9. Screenshot deletion — ADDED

- "Delete" button in the viewer (between Previous/Next and Copy/Rename).
- Clicking it opens a `ConfirmScreen` with the screenshot filename.
- On confirm: deletes the file (`Files.deleteIfExists`), deletes the cache entry (`diskCache.deleteForEntry`), removes from repository, and either navigates to the next screenshot or returns to the gallery if no screenshots remain.
- The gallery refreshes immediately (no restart needed) because the repository's entry list is updated in-place.

### 10. Colored chat buttons — ADDED

- `[Copy]` is green (`#55FF55`) and bold.
- `[Open Gallery]` is gold (`#FFAA00`) and bold.
- Both use `TextColor.fromRgb()` + `ChatFormatting.BOLD` for clear visual distinction.

## Files modified

- `src/client/java/.../SnapViewClient.java` — quote stripping in `copyByFileName`; added `getInstance()`, `getRepository()`, `getThumbnailLoader()`, `getDiskCache()` accessors
- `src/client/java/.../thumbnail/ThumbnailTexture.java` — `texture.upload()` call; `AtomicLong TEXTURE_COUNTER` for unique IDs
- `src/client/java/.../thumbnail/ThumbnailDiskCache.java` — added `deleteForEntry`, `clearAll`, `cacheSizeBytes`, `cacheFileCount`
- `src/client/java/.../gui/GalleryScreen.java` — 3-column layout fix; "Clear Cache" button + confirmation; cache size display; `formatSize` helper
- `src/client/java/.../gui/ScreenshotViewerScreen.java` — upscaling fix; Delete button + `ConfirmScreen`; `performDelete` with file+cache deletion
- `src/client/java/.../chat/ScreenshotChatHandler.java` — colored chat buttons (green Copy, gold Open Gallery)
- `src/client/java/.../chat/ScreenshotWatcher.java` — removed `screen != null` guard; reduced poll interval to 5 ticks
- `src/client/resources/assets/snapview/lang/en_us.json` — new keys: `gallery.clearcache`, `gallery.clearcache.confirm`, `viewer.delete`, `viewer.delete.confirm`, `viewer.deleted`, `viewer.deletefailed`
- `.github/workflows/build.yml` — CI workflow
- `README.md` — this file

## License

MIT
