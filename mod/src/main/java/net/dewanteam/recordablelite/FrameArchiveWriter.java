package net.dewanteam.recordablelite;

import java.io.*;
import java.nio.file.*;

public class FrameArchiveWriter implements AutoCloseable {
    // 1. ADD THE FIELD HERE AT THE TOP WITH THE OTHER VARIABLES
    private final Path archivePath;
    private final DataOutputStream out;
    private final long frameCountPos;
    private int frameCount = 0;

    public FrameArchiveWriter(Path archivePath, int width, int height,
                              float captureFps, float targetFps, float tickRate) throws IOException {
        // 2. ASSIGN IT HERE AT THE START OF THE CONSTRUCTOR
        this.archivePath = archivePath;

        this.out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(archivePath)));
        out.writeInt(0x43594252);          // "CYBR"
        out.writeInt(1);
        out.writeInt(width);
        out.writeInt(height);
        out.writeFloat(captureFps);
        out.writeFloat(targetFps);
        out.writeFloat(tickRate);
        frameCountPos = out.size();
        out.writeInt(0);
    }

    public void writeFrame(byte[] rgbData, long timestampMs) throws IOException {
        out.writeLong(timestampMs);
        out.writeInt(rgbData.length);
        out.write(rgbData);
        frameCount++;
    }

    @Override
    public void close() throws IOException {
        out.flush();
        out.close();
        try (RandomAccessFile raf = new RandomAccessFile(archivePath.toFile(), "rw")) {
            raf.seek(frameCountPos);
            raf.writeInt(frameCount);
        }
    }
}

