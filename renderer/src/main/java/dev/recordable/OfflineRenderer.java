package dev.recordable;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Offline renderer: reads a .cyber archive, speeds up by 20× (or tickRate factor),
 * and encodes to MP4 using FFmpeg via stdin.
 *
 * Usage: java -jar cyber-renderer.jar <capture.cyber>
 */
public class OfflineRenderer {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: java -jar cyber-renderer.jar <capture.cyber>");
            System.exit(1);
        }

        Path archivePath = Path.of(args[0]);
        if (!Files.exists(archivePath)) {
            System.err.println("File not found: " + archivePath);
            System.exit(1);
        }

        // 1. Read archive metadata
        FrameArchiveReader reader = new FrameArchiveReader(archivePath);
        int width = reader.getWidth();
        int height = reader.getHeight();
        float captureFps = reader.getCaptureFps();
        float targetFps = reader.getTargetFps();
        float tickRate = reader.getTickRate();
        int frameCount = reader.getFrameCount();

        System.out.printf("Archive: %s | %dx%d | %d frames at %.1f FPS, tick rate %.1f\n",
                archivePath.getFileName(), width, height, frameCount, captureFps, tickRate);

        if (frameCount < 2) {
            System.err.println("Not enough frames.");
            System.exit(1);
        }

        // 2. Compute speed factor: normal tick rate (20) / recorded tick rate
        float speedFactor = 20.0f / tickRate;
        System.out.printf("Speed factor: %.2fx\n", speedFactor);

        // 3. Build FFmpeg command: read raw RGB from stdin, speed up with setpts, encode.
        Path outputFile = archivePath.resolveSibling(archivePath.getFileName().toString().replace(".cyber", ".mp4"));
        String fpsStr = String.format(Locale.US, "%.2f", captureFps);
        String speedStr = String.format(Locale.US, "%.3f", speedFactor);

        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg",
                "-y",
                "-f", "rawvideo",
                "-pixel_format", "rgb24",
                "-video_size", width + "x" + height,
                "-framerate", fpsStr,
                "-i", "pipe:0",
                "-filter_complex",
                "setpts=" + speedStr + "*PTS",
                "-r", "60",  // force output 60 FPS
                "-c:v", "libx264",
                "-preset", "medium",
                "-crf", "23",
                "-pix_fmt", "yuv420p",
                "-movflags", "+faststart",
                outputFile.toString()
        );
        pb.redirectErrorStream(true);

        System.out.println("Launching FFmpeg: " + String.join(" ", pb.command()));
        Process process = pb.start();

        // 4. Stream frames to FFmpeg stdin
        try (OutputStream stdin = process.getOutputStream()) {
            byte[] frameBuffer = new byte[width * height * 3];
            int frameCountWritten = 0;
            while (reader.readNextFrame(frameBuffer)) {
                stdin.write(frameBuffer);
                frameCountWritten++;
                if (frameCountWritten % 100 == 0) {
                    System.out.printf("  %d/%d frames written\n", frameCountWritten, frameCount);
                }
            }
            stdin.flush();
        }

        // 5. Wait for FFmpeg to finish
        int exitCode = process.waitFor();
        if (exitCode == 0) {
            System.out.println("✅ Video generated: " + outputFile);
        } else {
            System.err.println("❌ FFmpeg failed with exit code " + exitCode);
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) System.err.println("FFmpeg: " + line);
            }
            System.exit(1);
        }

        reader.close();
    }
}