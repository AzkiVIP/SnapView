# SnapView

Client-side in-game screenshot gallery, viewer and management mod for Fabric.

## Target versions

- Minecraft: 26.1 / 26.1.1 / 26.1.2 (single codebase, `~26.1` dependency range in `fabric.mod.json`)
- Fabric Loader: `0.19.3`
- Fabric API: `0.155.2+26.1.2`
- Fabric Loom: `1.17-SNAPSHOT`
- Java: 25
- Mapping system: none — 26.1 ships unobfuscated, code uses Mojang's real names directly.

Versions were taken from `fabricmc.net/2026/03/14/261.html`, `docs.fabricmc.net/develop`, and the official `FabricMC/fabric-example-mod` template (cloned directly to confirm exact numbers).

## Build status / what to verify before shipping

This mod was written and reviewed in a sandbox without network access to Mojang's or Fabric's artifact servers, so **it has not been compiled against the real 26.1.2 jar**. Everything here follows long-standing, stable Mojang mapping conventions (confirmed against the real `fabric-example-mod` source where possible), but three areas touch APIs that are worth a build-and-fix pass first:

1. **`ThumbnailTexture.java` / `ScreenshotViewerScreen.java`** — `NativeImage`, `DynamicTexture`, `setPixelRGBA`, `TextureManager#register`/`#release`. Stable for years, but not verified against 26.1.2 specifically.
2. **`GalleryScreen.drawThumbnail` / `ScreenshotViewerScreen.render`** — the `GuiGraphics#blit` overload used here. This method's signature has changed a few times across recent Minecraft versions; check the actual overload available in 26.1.2.
3. **`ScreenshotChatHandler.java`** — `Component`/`ClickEvent` package (`net.minecraft.network.chat`) and the `ClickEvent.RunCommand` record shape (confirmed via search that `ClickEvent` is now a sealed interface of records as of ~1.21.6, but not against 26.1.2 specifically).

No mixins are used anywhere in this mod, specifically to avoid depending on unverified internal class names — see `ScreenshotChatHandler`'s Javadoc for the reasoning.

## Running / building

```
./gradlew build
```

Requires Java 25 and network access to `maven.fabricmc.net` + Mojang's Minecraft artifact servers (not available in the environment this was written in).
