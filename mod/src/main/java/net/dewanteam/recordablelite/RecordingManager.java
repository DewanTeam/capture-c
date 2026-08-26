package net.dewanteam.recordablelite;

import net.minecraft.client.MinecraftClient;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

public final class RecordingManager {
    private static final RecordingManager INSTANCE = new RecordingManager();
    public enum State { IDLE, RECORDING, STOPPING }

    private State state = State.IDLE;
    private ScreenCapture screenCapture;
    private FrameArchiveWriter archiveWriter;
    private Path currentOutputFile;
    private int recordingFps, recordingWidth, recordingHeight;
    private long frameIntervalNanos, lastCaptureNanos;
    private final AtomicLong capturedFrames = new AtomicLong();

    public static RecordingManager getInstance() { return INSTANCE; }

    public void toggleRecording(MinecraftClient client) {
        if (state == State.IDLE) startRecording(client);
        else if (state == State.RECORDING) stopRecording(client);
    }

    public void startRecording(MinecraftClient client) {
        if (state != State.IDLE) return;
        RecordableConfig config = RecordableConfig.get();
        if (!config.enabled) return;

        int nativeWidth = client.getWindow().getFramebufferWidth();
        int nativeHeight = client.getWindow().getFramebufferHeight();
        recordingWidth = nativeWidth;
        recordingHeight = nativeHeight;
        recordingFps = config.fps;
        frameIntervalNanos = 1_000_000_000L / recordingFps;

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path outputDir = Path.of(config.outputDirectory);
        try {
            Files.createDirectories(outputDir);
            currentOutputFile = outputDir.resolve("capture-" + timestamp + ".cyber");
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }

        try {
            screenCapture = new ScreenCapture(recordingWidth, recordingHeight);
            archiveWriter = new FrameArchiveWriter(
                currentOutputFile, recordingWidth, recordingHeight,
                recordingFps, 60.0f, 1.0f
            );
            state = State.RECORDING;
            lastCaptureNanos = 0;
            capturedFrames.set(0);
            RecordableMod.sendClientMessage(client, "§aRecording started (" + recordingFps + " FPS)", false);
        } catch (Exception e) {
            e.printStackTrace();
            state = State.IDLE;
        }
    }

    public void stopRecording(MinecraftClient client) {
        if (state != State.RECORDING) return;
        state = State.STOPPING;
        try {
            if (archiveWriter != null) archiveWriter.close();
            if (screenCapture != null) screenCapture.close();
            RecordableMod.sendClientMessage(client, "§aSaved: " + currentOutputFile.getFileName(), false);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            state = State.IDLE;
            archiveWriter = null;
            screenCapture = null;
        }
    }

    public void onFrame() {
        if (state != State.RECORDING) return;
        long now = System.nanoTime();
        if (lastCaptureNanos != 0 && (now - lastCaptureNanos) < frameIntervalNanos) return;
        lastCaptureNanos = now;

        try {
            ScreenCapture.CapturedFrame frame = screenCapture.captureFrame();
            if (frame != null) {
                archiveWriter.writeFrame(frame.rgbPixels(), System.currentTimeMillis());
                capturedFrames.incrementAndGet();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public boolean isRecording() { return state == State.RECORDING; }
}