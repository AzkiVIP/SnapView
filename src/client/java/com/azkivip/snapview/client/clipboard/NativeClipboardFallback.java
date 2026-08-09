package com.azkivip.snapview.client.clipboard;

import com.azkivip.snapview.client.SnapViewClient;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Platform-native clipboard access via pre-installed OS commands.
 * <p>
 * <b>Why native commands instead of AWT</b>: Minecraft's {@code Main} class
 * sets {@code java.awt.headless=true} at JVM startup (in {@code Main.<clinit>}).
 * This forces AWT into headless mode — {@code Toolkit.getDefaultToolkit()}
 * returns a {@code HeadlessToolkit} whose {@code getSystemClipboard()} throws
 * {@code HeadlessException} unconditionally. This cannot be worked around
 * because:
 * <ul>
 *   <li>Java module security blocks reflection on {@code sun.awt.HeadlessToolkit}
 *       (not exported) and {@code java.awt.GraphicsEnvironment.headless} (not opened).</li>
 *   <li>The headless flag is cached at JVM startup, before any mod code runs.</li>
 *   <li>Fabric mixins cannot intercept {@code Main.main()} because it's the JVM
 *       entry point (runs before Fabric's mixin system is initialized).</li>
 * </ul>
 * <p>
 * <b>The solution</b>: Use pre-installed OS commands (PowerShell on Windows,
 * osascript on macOS, xclip/wl-copy on Linux) to write the image to the
 * clipboard. These run as separate processes with their own runtime, NOT
 * affected by the Minecraft JVM's {@code java.awt.headless} flag.
 * <p>
 * <b>No bundled executables</b>: This does NOT extract or compile any external
 * exe. It uses only commands that are pre-installed on the OS:
 * <ul>
 *   <li>Windows: {@code powershell.exe} (pre-installed on all Windows 10/11)</li>
 *   <li>macOS: {@code osascript} (pre-installed)</li>
 *   <li>Linux: {@code xclip} or {@code wl-copy} (commonly pre-installed)</li>
 * </ul>
 */
final class NativeClipboardFallback {

    private NativeClipboardFallback() {
    }

    /**
     * Copies the image to the clipboard using a pre-installed OS command.
     * Returns null/empty on success, or an error message.
     */
    static String copyImage(Path imagePath, BufferedImage image) {
        String osName = System.getProperty("os.name", "").toLowerCase();
        if (osName.contains("win")) {
            return copyWindows(imagePath);
        } else if (osName.contains("mac") || osName.contains("darwin")) {
            return copyMacOS(imagePath);
        } else if (osName.contains("linux") || osName.contains("unix")) {
            return copyLinux(imagePath);
        }
        return "Clipboard not supported on OS: " + osName;
    }

    /**
     * Windows: uses PowerShell with System.Windows.Forms.Clipboard.SetImage().
     * PowerShell launches its own .NET runtime which is NOT affected by the
     * Minecraft JVM's java.awt.headless flag.
     * <p>
     * Performance: PowerShell cold-start takes ~1-2s. To reduce this, the
     * image is passed by file path (not stdin) so PowerShell only needs to
     * load the file, not decode it from piped bytes.
     */
    private static String copyWindows(Path imagePath) {
        String absPath = imagePath.toAbsolutePath().toString().replace("'", "''");

        // PowerShell script: load the image from file and set it on the clipboard.
        // -NoProfile: skip loading the user's PowerShell profile (faster)
        // -ExecutionPolicy Bypass: allow the inline script to run
        // -STA: single-threaded apartment — required for clipboard access in .NET
        String psScript =
                "Add-Type -AssemblyName System.Windows.Forms; " +
                "Add-Type -AssemblyName System.Drawing; " +
                "$img = [System.Drawing.Image]::FromFile('" + absPath + "'); " +
                "[System.Windows.Forms.Clipboard]::SetImage($img); " +
                "$img.Dispose()";

        String[] cmd = {
                "powershell.exe",
                "-NoProfile",
                "-ExecutionPolicy", "Bypass",
                "-STA",
                "-Command", psScript
        };

        return runCommand(cmd, "powershell -STA -Command SetImage");
    }

    private static String copyMacOS(Path imagePath) {
        String absPath = imagePath.toAbsolutePath().toString().replace("\"", "\\\"");
        String appleScript =
                "set the clipboard to (read POSIX file \"" + absPath + "\" as \u00abclass PNGf\u00bb)";
        String[] cmd = {"osascript", "-e", appleScript};
        return runCommand(cmd, "osascript -e 'set clipboard to PNGf'");
    }

    private static String copyLinux(Path imagePath) {
        for (String tool : new String[]{"wl-copy", "xclip"}) {
            if (!isCommandAvailable(tool)) {
                continue;
            }
            byte[] pngBytes;
            try {
                pngBytes = Files.readAllBytes(imagePath);
            } catch (IOException e) {
                return "Failed to read PNG for " + tool + ": " + e.getMessage();
            }
            if ("wl-copy".equals(tool)) {
                String[] cmd = {"wl-copy", "--type", "image/png"};
                return runCommand(cmd, "wl-copy --type image/png", pngBytes);
            } else {
                String[] cmd = {"xclip", "-selection", "clipboard", "-t", "image/png", "-i"};
                return runCommand(cmd, "xclip -selection clipboard -t image/png -i", pngBytes);
            }
        }
        return "No image-capable clipboard tool found. Install 'wl-copy' (Wayland) or 'xclip' (X11).";
    }

    private static boolean isCommandAvailable(String command) {
        try {
            Process p = new ProcessBuilder("which", command).redirectErrorStream(true).start();
            boolean exited = p.waitFor(5, TimeUnit.SECONDS);
            return exited && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String runCommand(String[] cmd, String display, byte[] stdin) {
        long spawnStart = System.nanoTime();
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false);
            Process p = pb.start();
            long spawnMs = (System.nanoTime() - spawnStart) / 1_000_000;

            long execStart = System.nanoTime();
            if (stdin != null) {
                p.getOutputStream().write(stdin);
                p.getOutputStream().close();
            }
            byte[] stdout = p.getInputStream().readAllBytes();
            byte[] stderr = p.getErrorStream().readAllBytes();
            boolean exited = p.waitFor(15, TimeUnit.SECONDS);
            long execMs = (System.nanoTime() - execStart) / 1_000_000;
            long totalMs = (System.nanoTime() - spawnStart) / 1_000_000;

            if (!exited) {
                p.destroyForcibly();
                return "Command timed out: " + display;
            }
            if (p.exitValue() == 0) {
                SnapViewClient.LOGGER.info("Clipboard command succeeded: {} (spawn: {}ms, execution: {}ms, total: {}ms)",
                        display, spawnMs, execMs, totalMs);
                return "";
            }
            String err = new String(stderr).trim();
            String out = new String(stdout).trim();
            String detail = !err.isEmpty() ? err : out;
            SnapViewClient.LOGGER.warn("Clipboard command failed: {} (spawn: {}ms, execution: {}ms) — {}",
                    display, spawnMs, execMs, detail);
            return "Command failed (" + p.exitValue() + "): " + display
                    + (detail.isEmpty() ? "" : " — " + detail);
        } catch (Exception e) {
            long totalMs = (System.nanoTime() - spawnStart) / 1_000_000;
            SnapViewClient.LOGGER.warn("Clipboard command exception: {} ({}ms): {}", display, totalMs, e.getMessage());
            return "Command failed: " + display + ": " + e.getMessage();
        }
    }

    private static String runCommand(String[] cmd, String display) {
        return runCommand(cmd, display, null);
    }
}
