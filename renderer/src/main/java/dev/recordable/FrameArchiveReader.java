package dev.recordable;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Reads the .cyber archive format produced by the capture mod.
 * Provides sequential access to frames with timestamps.
 */
public class FrameArchiveReader implements AutoCloseable {

    private final RandomAccessFile raf;
    private final int width, height;
    private final float captureFps, targetFps, tickRate;
    private final int frameCount;
    private final long[] timestamps;
    private final int[] sizes;
    private final long[] offsets;
    private int currentIndex = 0;

    public FrameArchiveReader(Path archivePath) throws IOException {
        this.raf = new RandomAccessFile(archivePath.toFile(), "r");

        // Read header
        int magic = raf.readInt();
        if (magic != 0x43594252) throw new IOException("Invalid magic, not a CYBR file");
        int version = raf.readInt();
        if (version != 1) throw new IOException("Unsupported version: " + version);

        width = raf.readInt();
        height = raf.readInt();
        captureFps = raf.readFloat();
        targetFps = raf.readFloat();
        tickRate = raf.readFloat();
        frameCount = raf.readInt();

        timestamps = new long[frameCount];
        sizes = new int[frameCount];
        offsets = new long[frameCount];

        // Read index: each frame: timestamp (long), size (int), then data follows
        for (int i = 0; i < frameCount; i++) {
            timestamps[i] = raf.readLong();
            sizes[i] = raf.readInt();
            offsets[i] = raf.getFilePointer(); // position just before data
            raf.skipBytes(sizes[i]); // skip data for now
        }
    }

    public int getFrameCount() { return frameCount; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public float getCaptureFps() { return captureFps; }
    public float getTargetFps() { return targetFps; }
    public float getTickRate() { return tickRate; }

    /**
     * Read the next frame's data into the provided byte array.
     * @return true if a frame was read, false if end reached.
     */
    public boolean readNextFrame(byte[] buffer) throws IOException {
        if (currentIndex >= frameCount) return false;
        raf.seek(offsets[currentIndex]);
        raf.readFully(buffer, 0, sizes[currentIndex]);
        currentIndex++;
        return true;
    }

    public long getCurrentTimestampMs() {
        return currentIndex > 0 ? timestamps[currentIndex - 1] : -1;
    }

    @Override
    public void close() throws IOException {
        raf.close();
    }
}