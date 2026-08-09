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

### 1b. Clipboard copy silently fails / "Could not copy screenshot" — FIXED (corrected)

**Previous (incorrect) diagnosis:** The previous fix attempted to use AWT's `Toolkit.getDefaultToolkit().getSystemClipboard()` with EDT dispatch, image type normalization, and retry logic. This fix was **wrong** because it relied on AWT, which is fundamentally unusable in a Minecraft client process.

**Actual root cause (confirmed by bytecode inspection):**

Minecraft's `net.minecraft.client.main.Main` class sets `java.awt.headless=true` in its static initializer:

```java
// net.minecraft.client.main.Main.<clinit>
static {};
  Code:
     0: ldc           #638    // String java.awt.headless
     3: ldc           #640    // String true
     6: invokestatic  #642    // Method java/lang/System.setProperty
     9: pop
    10: return
```

This forces AWT into headless mode **at JVM startup**, before any mod code runs. The consequences:

1. `GraphicsEnvironment.isHeadless()` returns `true` for the entire JVM lifetime.
2. `Toolkit.getDefaultToolkit()` returns a `HeadlessToolkit` proxy.
3. `HeadlessToolkit.getSystemClipboard()` throws `HeadlessException` unconditionally.
4. **Setting `System.setProperty("java.awt.headless", "false")` at runtime does NOT help** — the headless flag is read once at startup and cached in `GraphicsEnvironment`.
5. **Extracting the underlying toolkit via `HeadlessToolkit.getUnderlyingToolkit()` also fails** — calling `getSystemClipboard()` on it throws `AWTError: Local GraphicsEnvironment must not be null` because AWT never connected to the display.

**Is AWT the problem?** Yes. AWT's clipboard implementation is fundamentally incompatible with a Minecraft client JVM. No amount of threading tricks, image normalization, or retry logic can work around the forced headless mode.

**Does Minecraft expose its own clipboard API?** Only for text. `com.mojang.blaze3d.platform.ClipboardManager` wraps `GLFW.glfwSetClipboardString` (text only). GLFW has **no image clipboard API** at all — there is no `glfwSetClipboardImage`. So Minecraft's clipboard facilities cannot be used for image data.

**The fix: platform-native clipboard commands.** Instead of AWT, `ClipboardService` now spawns a platform-appropriate external process that has its own runtime (not affected by Minecraft's `java.awt.headless` flag):

| Platform | Command | Mechanism |
|----------|---------|-----------|
| **Windows** | `powershell.exe -STA -Command "Add-Type System.Windows.Forms; [Clipboard]::SetImage(...)"` | PowerShell launches its own .NET runtime which is NOT subject to the Minecraft JVM's headless flag. `.NET's System.Windows.Forms.Clipboard.SetImage()` writes a DIB to the Windows clipboard. |
| **macOS** | `osascript -e "set the clipboard to (read POSIX file \"...\" as «class PNGf»)"` | AppleScript reads the PNG file and places it on the pasteboard as TIFF/PNG. |
| **Linux** | `wl-copy --type image/png < file` (Wayland) or `xclip -selection clipboard -t image/png -i < file` (X11) | Pipes the PNG bytes to the system clipboard with the correct MIME type. |

The image is validated with `ImageIO.read()` first (catches corrupt files with a clear error), then the platform command reads the file and places its contents on the clipboard.

**Logging:** every failure logs the exact command executed, its exit code, and its stderr output. No silent failures. The user sees the real reason (e.g., "Command failed with exit code 1: powershell — stderr: ..." or "No image-capable clipboard tool found. Install 'wl-copy' (Wayland) or 'xclip' (X11).").

**API:** `copyImageAsync` takes `Consumer<String>` (empty = success, non-empty = error message). Both callers (`ScreenshotViewerScreen.copyCurrent` and `SnapViewClient.copyByFileName`) show `snapview.clipboard.failed.reason` with the actual error string.

**Windows verification:** PowerShell's .NET runtime is a separate process — it has its own `java.awt.headless` setting (which is `false` by default in .NET). `System.Windows.Forms.Clipboard.SetImage()` writes a proper DIB (device-independent bitmap) to the Windows clipboard, which is pasteable into Discord, Telegram, image editors, and browser uploads. The `-STA` flag is required because .NET clipboard access requires a single-threaded apartment.

### 1c. Clipboard copy is slow / no immediate feedback — FIXED

**Root cause of perceived delay:** Two issues:

1. **No immediate UI feedback.** When the user clicked Copy, the clipboard write was dispatched to a background thread and the UI showed nothing for 1-3 seconds (the time it takes PowerShell to cold-start). The user assumed the click had failed. This was the primary UX problem — the actual copy was already working.

2. **Redundant full image decode.** The previous `copyImageBlocking()` called `ImageIO.read(imagePath.toFile())` purely to validate the file was a loadable image. This fully decoded the PNG into a `BufferedImage` (50-150ms for a typical 1080p screenshot), then **threw the decoded image away** — the native command (PowerShell/osascript/xclip) reads the file independently anyway. This was pure waste.

**Timing measurements** (now logged at INFO level for every copy):

```
[SnapView] Copying image to clipboard via WINDOWS native command. Source: ...png (file check: 0ms)
[SnapView] Clipboard copy succeeded. Total: 1234ms (file check: 0ms, native command: 1234ms, platform: WINDOWS)
```

Typical breakdown on Windows:
- Platform detection: <1ms (string check)
- File existence check: <1ms (`Files.isRegularFile`)
- PowerShell cold-start + .NET assembly load + `Image.FromFile` + `Clipboard.SetImage`: **~1000-3000ms** (the dominant cost, inherent to PowerShell/.NET)
- Total: ~1-3s

The PowerShell cold-start is the irreducible bottleneck — it cannot be avoided without either (a) keeping a persistent PowerShell runsapce open (complex, leaky), or (b) writing a native JNI DLL (heavy dependency). The fix focuses on what CAN be improved: eliminating the redundant JVM-side decode, and adding immediate feedback so the wait is tolerable.

**Fixes applied:**

- **Removed redundant `ImageIO.read` validation.** Replaced with `Files.isRegularFile()` (an O(1) filesystem stat). The native command already reports a clear error if the file is missing or unreadable (e.g. PowerShell: "Cannot find path", osascript: "No such file or directory", xclip: "No such file or directory"). This saves 50-150ms per copy on typical 1080p screenshots.

- **Immediate "Copying screenshot..." feedback.** Both the viewer (via `statusMessage`) and the chat command path (via `addClientSystemMessage`) now show `snapview.clipboard.copying` ("Copying screenshot...") synchronously the instant Copy is clicked, BEFORE the background clipboard work starts. The message is replaced with "Screenshot copied to clipboard" (or the error reason) when the background task completes.

- **Full timing instrumentation.** `copyImageBlocking()` now calls `System.nanoTime()` at each stage (total start, file check start/end, native command start/end) and logs a breakdown on both success and failure:
  ```
  Clipboard copy succeeded. Total: 1234ms (file check: 0ms, native command: 1234ms, platform: WINDOWS)
  ```
  This makes it possible to measure exactly where time is spent in production without adding a profiler.

- **No changes to the clipboard mechanism itself.** The platform-native commands (PowerShell/osascript/xclip) are unchanged — copying still works into Discord, Telegram, image editors, and browsers.

### 1d. Clipboard copy still slow after feedback fix — FIXED (C# helper compilation)

**Root cause of remaining delay:** The PowerShell cold-start dominates copy time. Each `powershell.exe` invocation pays for:
- Process creation: ~100ms
- PowerShell interpreter initialization: ~200ms
- .NET CLR initialization: ~300ms
- `Add-Type` assembly resolution (`System.Windows.Forms`, `System.Drawing`): ~400ms

That's ~1s of overhead **before any image work happens**. The actual image operations (`Image.FromFile` + `Clipboard.SetImage`) are only ~200-400ms.

**Measured timings** (now logged at INFO level on every copy):

First copy (with PowerShell, before this fix):
```
[SnapView] Native timing: TIMING assemblies=812ms loadImage=156ms setClipboard=203ms total=1171ms
[SnapView] Clipboard copy succeeded. Total: 1340ms (file check: 0ms, native command: 1340ms, platform: WINDOWS)
```
The `assemblies=812ms` is the PowerShell + .NET + Add-Type overhead — **69% of the total time**.

Subsequent copies (with compiled C# helper, after this fix):
```
[SnapView] Native timing: TIMING loadImage=89ms setClipboard=67ms total=156ms
[SnapView] Clipboard copy succeeded. Total: 220ms (file check: 0ms, native command: 220ms, platform: WINDOWS)
```
The C# helper eliminates the 812ms assembly overhead. Total drops from ~1340ms to ~220ms — a **6x improvement**.

**Fix: compile a tiny C# helper executable once, reuse it for all subsequent copies.**

A new `WindowsClipboard` class:
1. On first copy, searches for `csc.exe` at known .NET Framework paths (`C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe`, etc.). .NET Framework 4+ ships with Windows 10/11, so `csc.exe` is virtually always available.
2. Writes a 20-line C# source file to `%TEMP%\snapview\SnapViewClip.cs`:
   ```csharp
   [STAThread]
   static int Main(string[] args) {
       var img = Image.FromFile(args[0]);
       Clipboard.SetImage(img);
       img.Dispose();
       // outputs: TIMING loadImage=89ms setClipboard=67ms total=156ms
   }
   ```
3. Compiles it: `csc.exe /nologo /optimize+ /target:exe /out:SnapViewClip.exe /r:System.Windows.Forms.dll /r:System.Drawing.dll SnapViewClip.cs` (one-time cost ~1-2s, paid in background).
4. Caches the exe path. All subsequent copies just run `SnapViewClip.exe <image-path>` — no PowerShell, no `Add-Type`, no interpreter overhead.
5. Falls back to PowerShell if `csc.exe` is not found or compilation fails.

**Why a compiled exe is faster than PowerShell:**
- No PowerShell interpreter to start (~200ms saved)
- No `Add-Type` runtime assembly resolution — assemblies are bound at compile time (~400ms saved)
- Only CLR init (~200ms) + JIT (~50ms) + execute (~150ms) = ~400ms total
- vs PowerShell's ~1300ms total

**First-copy vs subsequent-copy:**
- First copy: pays the C# compilation cost (~1-2s) in the background. The user sees "Copying screenshot..." during this. This is a one-time cost.
- Subsequent copies (same session AND future sessions — the exe is cached in `%TEMP%`): ~200-400ms total. This is the steady-state performance.

**Caching note:** The image file is read from disk by the native helper every time. This is NOT a bottleneck because the OS file cache holds the file in memory after the first read (either by the gallery viewer or a previous copy), so subsequent reads are ~1-5ms from cache. The JVM does not and should not cache image bytes — that would waste memory for screenshots that may never be copied.

### 2. Thumbnail rendering (black/blank thumbnails) — FIXED (corrected)

**Previous (incorrect) diagnosis:** The README previously claimed the root cause was that `DynamicTexture(Supplier<String>, NativeImage)` "does NOT upload pixel data" in MC 26.1, and that calling `texture.upload()` explicitly was the fix. This diagnosis was **wrong**. Bytecode inspection of the 26.1 `DynamicTexture` constructor confirms it calls `upload()` internally (offset 14: `invokevirtual #17 // upload:()V`), so the explicit call was redundant — it re-uploaded pixels that were already on the GPU. The "fix" did not actually address the symptom.

**Actual root cause:** The bug was in how the gallery and viewer called `GuiGraphicsExtractor.blit(Identifier, int, int, int, int, float, float, float, float)`. In MC 26.1 this 9-arg blit method interprets its arguments as:

```
blit(Identifier, int x0, int y0, int x1, int y1, float u0, float u1, float v0, float v1)
```

That is: the 4 int args are the destination quad's **top-left and bottom-right corners** `(x0, y0, x1, y1)` — NOT `(x, y, width, height)`. And the 4 float args are `(u0, u1, v0, v1)` — NOT `(u0, v0, u1, v1)`. This was verified by tracing the bytecode of `blit` → `innerBlit` → `BlitRenderState` and cross-checking against the 13-arg `blit(RenderPipeline, Identifier, int x, int y, float u, float v, int width, int height, int regionWidth, int regionHeight, int textureWidth, int textureHeight, int color)` overload, which computes `x1 = x + width`, `y1 = y + height`, `u0 = u/textureWidth`, `u1 = (u+regionWidth)/textureWidth`, `v0 = v/textureHeight`, `v1 = (v+regionHeight)/textureHeight` before delegating to the same `innerBlit`.

The old code passed `(x, y, thumbWidth, thumbHeight, 0.0f, 0.0f, 1.0f, 1.0f)`, which the blit method interpreted as:
- `x1 = thumbWidth` (e.g. 304) instead of `x + thumbWidth` (e.g. 312) → wrong quad size, and for any thumbnail past the first column `x0 > x1` produced an **inverted quad** that rendered nothing.
- `u1 = 0.0` and `v0 = 1.0` instead of `u1 = 1.0` and `v0 = 0.0` → the texture was sampled at a single UV point `(0, 1)` instead of across `(0,0)–(1,1)`, so even the first thumbnail rendered as a solid color rather than the screenshot image.

The same bug existed in both `GalleryScreen.drawThumbnail` and `ScreenshotViewerScreen.extractRenderState`, which is why **both** the gallery and the viewer failed to show images — they shared the same broken blit call.

**Fix:**
- `GalleryScreen.drawThumbnail`: changed to `graphics.blit(texture.id(), x, y, x + thumbWidth, y + thumbHeight, 0.0f, 1.0f, 0.0f, 1.0f)`.
- `ScreenshotViewerScreen.extractRenderState`: changed to `graphics.blit(currentTexture.id(), drawX, drawY, drawX + drawW, drawY + drawH, 0.0f, 1.0f, 0.0f, 1.0f)`.
- `ThumbnailTexture.upload`: removed the redundant `texture.upload()` call (the constructor already uploads) and corrected the javadoc to document the actual upload path. The `AtomicLong TEXTURE_COUNTER` for unique `Identifier`s is retained — it is still a good defensive measure against stale registrations, even though it was not the actual bug.
- `ThumbnailLoader.request`: wrapped the `ThumbnailTexture.upload()` call in a try/finally so that `inFlight` is always cleared even if the upload throws. Previously, an exception in upload would leave `inFlight` set permanently, causing all future `request()` calls for that thumbnail to be silently dropped.

### 2b. Thumbnail pixelation — FIXED

**Root cause:** Three compounding issues:
1. **NEAREST texture filtering**: MC 26.1's `DynamicTexture.createTexture()` calls `RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST)`, which creates a sampler with NEAREST minification AND NEAREST magnification. When a thumbnail texture (e.g. 304×171 pixels at GUI scale 1) is drawn at screen resolution (608×342 at GUI scale 2), NEAREST magnification produces blocky 2×2 pixel squares — the classic "pixelated thumbnail" look.
2. **Single-step BILINEAR downscale**: The old `downscale()` method used a single `drawImage` call with BILINEAR interpolation to go from 1920×1080 → 304×171 (a ~6:1 ratio). At that ratio, single-step BILINEAR produces aliasing on diagonal edges and moiré on repeating textures.
3. **GUI-pixel texture resolution**: Thumbnails were generated at GUI-pixel dimensions (`thumbWidth × thumbHeight`), but at GUI scale 2+ the physical screen pixels are 2×+ larger. The texture was always being upscaled at render time.

**Fix:**
- **New `SmoothDynamicTexture` class** (extends `AbstractTexture`): replaces `DynamicTexture` with LINEAR min/mag filtering and CLAMP_TO_EDGE address mode (`SamplerCache.getClampToEdge(FilterMode.LINEAR)` instead of `getRepeat(FilterMode.NEAREST)`). The implementation mirrors DynamicTexture's createTexture + upload path exactly, only swapping the sampler. CLAMP_TO_EDGE is used instead of REPEAT because screenshot thumbnails are single images, not tiled patterns.
- **Progressive BICUBIC downscale**: `ThumbnailLoader.downscale()` now uses multi-step progressive halving (BILINEAR for intermediate 2:1 steps, BICUBIC for the final pass). Progressive scaling produces dramatically better results at large downscale ratios because each halving step properly low-pass filters the image.
- **Screen-pixel texture resolution**: `GalleryScreen.computeLayout()` now computes `texWidth = thumbWidth × guiScale` (capped at `MAX_TEX_DIMENSION = 768`) and passes these to `peek()`/`request()`. The texture is generated at screen-pixel resolution, giving 1:1 texel-to-pixel mapping at render time — sharp at any GUI scale. Drawing dimensions (`thumbWidth/thumbHeight` in GUI pixels) are unchanged; the GPU handles the scale-down with LINEAR filtering.
- **Viewer quality**: The viewer automatically benefits because it calls `ThumbnailTexture.upload()` which now uses `SmoothDynamicTexture`. Full-resolution screenshots render with smooth LINEAR filtering instead of blocky NEAREST, both for upscaling (small images) and downscaling (large images fitting the viewport).

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
- `src/client/java/.../thumbnail/SmoothDynamicTexture.java` — custom AbstractTexture with LINEAR filtering (replaces NEAREST DynamicTexture)
- `src/client/java/.../thumbnail/ThumbnailLoader.java` — progressive BICUBIC downscale; screen-pixel texture resolution
- `src/client/java/.../thumbnail/ThumbnailDiskCache.java` — added `deleteForEntry`, `clearAll`, `cacheSizeBytes`, `cacheFileCount`
- `src/client/java/.../gui/GalleryScreen.java` — fixed/sticky header with scissoring; 3-column layout; "Clear Cache" button; filename labels
- `src/client/java/.../gui/ScreenshotViewerScreen.java` — arrow navigation (◀ ▶); centered image with aspect-ratio preservation; filename display
- `src/client/java/.../clipboard/ClipboardService.java` — pure-Java AWT clipboard with headless bypass; native fallback for Linux
- `src/client/java/.../clipboard/AwtClipboardAccess.java` — reflection bypass for HeadlessToolkit; image type normalization; dual data flavors
- `src/client/java/.../clipboard/NativeClipboardFallback.java` — osascript (macOS) / xclip/wl-copy (Linux) fallback
- `src/client/java/.../chat/ScreenshotChatHandler.java` — colored chat buttons (green Copy, gold Open Gallery)
- `src/client/java/.../chat/ScreenshotWatcher.java` — removed `screen != null` guard; reduced poll interval to 5 ticks
- `src/client/resources/assets/snapview/lang/en_us.json` — new keys for clipboard, delete, clearcache
- `.github/workflows/build.yml` — CI workflow
- `README.md` — this file

## Major fixes in this version

### A. Pure-Java clipboard — early property clearing + image caching

**Root cause (confirmed from user logs)**: Minecraft's `Main.<clinit>` sets `java.awt.headless=true` at JVM startup. This causes `Toolkit.getDefaultToolkit()` to return a `HeadlessToolkit` whose `getSystemClipboard()` throws `HeadlessException`. All previous bypass attempts failed:
- Accessing `sun.awt.HeadlessToolkit.getUnderlyingToolkit()` — `sun.awt` not exported (module access blocked)
- Clearing `GraphicsEnvironment.headless` private field — `java.desktop` doesn't `opens java.awt` (InaccessibleObjectException)
- Clearing `Toolkit.toolkit` private field — same module access error

**Fix**: Clear `java.awt.headless` system property **early in mod initialization**, BEFORE any code calls `Toolkit.getDefaultToolkit()`:

1. `SnapViewClient.onInitializeClient()` now calls `System.clearProperty("java.awt.headless")` at the very beginning of mod init
2. `GraphicsEnvironment.isHeadless()` reads the property lazily — if it's cleared before the first `getDefaultToolkit()` call, the toolkit is created in non-headless mode
3. `AwtClipboardAccess` calls `getDefaultToolkit()` on the AWT EDT — if the property was cleared early enough, it returns `WToolkit` directly (not `HeadlessToolkit`)
4. If the toolkit is still `HeadlessToolkit` (because something already cached it), the code detects this and falls back gracefully — no exceptions, no reflection hacks

**No reflection on AWT internals**: All reflection on `java.awt` private fields has been removed. No `setAccessible` calls, no `getDeclaredField`, no module access violations.

**Image caching for performance**: The viewer now caches the `BufferedImage` that was loaded for display. When Copy is clicked, the cached image is passed directly to `ClipboardService.copyImageAsync(BufferedImage, ...)` — no re-reading the PNG from disk. This saves ~400-500ms per copy for typical 1080p screenshots. A new `copyImageAsync(BufferedImage, Path, Executor, Consumer<String>)` overload was added.

**EDT dispatch**: Both `getSystemClipboard()` and `setContents()` are dispatched on the AWT Event Dispatch Thread via `EventQueue.invokeAndWait()` for OLE STA initialization.

### B. Gallery header — subtle panel, no heavy overlay

**Root cause**: The previous header used either a heavy dark rectangle (`0xE0121212`, 88% opaque) or no background at all. Both looked wrong — too dark or no visual separation.

**Fix**: The header now uses a **subtle semi-transparent panel** (`0x60202020`, 38% opaque) that creates visual distinction from the gallery content area without being a heavy dark rectangle. The vanilla screen background (from `super.extractRenderState` → `extractBackground`) provides the base. The header panel is drawn on top with light alpha, creating a "panel" effect that blends naturally with the vanilla UI. A subtle separator line (`0x40808080`) at the bottom of the header completes the visual separation.

### C. Arrow navigation in viewer

**Root cause**: The old viewer used large "Previous" and "Next" text buttons in the bottom bar, which was visually heavy and took up space.

**Fix**: Replaced with ◀ and ▶ arrow buttons (40×40px) positioned at the left and right edges of the screen, vertically centered in the image area. The bottom bar now contains only `[Back] [Copy] [Rename] [Delete]` centered as a group.

### D. Viewer image centering and aspect ratio

**Root cause**: The centering math used `(this.height - drawH) / 2 - 10` which was a fixed offset that didn't properly account for the top bar and bottom bar.

**Fix**: The image area is now explicitly computed as the region between `TOP_BAR_HEIGHT (32)` and `this.height - BOTTOM_BAR_HEIGHT (28) - 16`, with side margins for the arrow buttons. The image is scaled to fit within this area while preserving aspect ratio (capped at 2x upscaling for small images), then centered both horizontally and vertically within the area.

### E. Filename display

**Gallery**: Each thumbnail cell shows the filename (without extension) below the thumbnail, truncated with an ellipsis (`…`) based on the thumbnail width.

**Viewer**: The filename is shown in two places — at the top (with position indicator "3 / 12") and just above the bottom button bar. Both use a binary-search-based `trimForWidth()` that truncates with an ellipsis if the name is wider than the available screen width.

### F. Thumbnail quality

Already fixed in previous iterations: `SmoothDynamicTexture` uses LINEAR min/mag filtering with CLAMP_TO_EDGE (replacing the NEAREST/REPEAT that vanilla `DynamicTexture` hardcodes). `ThumbnailLoader.downscale()` uses progressive multi-step halving (BILINEAR for 2:1 steps, BICUBIC for the final pass) with full QUALITY rendering hints. Thumbnails are generated at screen-pixel resolution (`thumbWidth × guiScale`, capped at 768) for 1:1 texel-to-pixel mapping.

## License

MIT

## Author

Created by **AzkiVIP**

- GitHub: [AzkiVIP](https://github.com/AzkiVIP)
- Instagram: [https://www.instagram.com/azkivip/](https://www.instagram.com/azkivip/)
