package net.dewanteam.recordablelite;

import net.minecraft.client.MinecraftClient;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;
import java.nio.ByteBuffer;

public final class ScreenCapture implements AutoCloseable {
    private static final int BYTES_PER_PIXEL = 3;

    private final int outputWidth;
    private final int outputHeight;
    private final int outputByteSize;
    private final int[] pboIds = new int[2];

    private int sourceWidth;
    private int sourceHeight;
    private int sourceByteSize;
    private int pboWriteIndex;
    private boolean hasPendingPboFrame;
    private boolean pboSupported = true;
    private ByteBuffer fallbackReadBuffer;

    public ScreenCapture(int outputWidth, int outputHeight) {
        this.outputWidth = Math.max(2, outputWidth);
        this.outputHeight = Math.max(2, outputHeight);
        this.outputByteSize = this.outputWidth * this.outputHeight * BYTES_PER_PIXEL;
    }

    public synchronized CapturedFrame captureFrame() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getWindow() == null) {
            return null;
        }

        int nativeWidth = client.getWindow().getFramebufferWidth();
        int nativeHeight = client.getWindow().getFramebufferHeight();
        if (nativeWidth <= 0 || nativeHeight <= 0) {
            return null;
        }

        try {
            GL11.glGetError();
        } catch (Throwable ignored) {
            return null;
        }

        try {
            if (!pboSupported) {
                return captureSynchronously(nativeWidth, nativeHeight);
            }

            CapturedFrame readyFrame = hasPendingPboFrame ? mapPendingPboFrame() : null;

            if (pboIds[0] == 0 || nativeWidth != sourceWidth || nativeHeight != sourceHeight) {
                initializePbos(nativeWidth, nativeHeight);
            }

            submitAsyncRead();
            return readyFrame;
        } catch (Throwable t) {
            pboSupported = false;
            deletePbosSafely();
            try {
                return captureSynchronously(nativeWidth, nativeHeight);
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    private CapturedFrame captureSynchronously(int nativeWidth, int nativeHeight) {
        sourceWidth = nativeWidth;
        sourceHeight = nativeHeight;
        sourceByteSize = checkedByteSize(sourceWidth, sourceHeight);
        ensureFallbackReadBuffer();

        int previousReadFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int previousPackAlignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        try {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
            GL11.glReadBuffer(GL11.GL_BACK);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            fallbackReadBuffer.clear();
            GL11.glReadPixels(0, 0, sourceWidth, sourceHeight, GL12.GL_BGR, GL11.GL_UNSIGNED_BYTE, fallbackReadBuffer);
        } finally {
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, previousPackAlignment);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousReadFramebuffer);
        }

        byte[] rgb = new byte[outputByteSize];
        copyFlipScaleBgrToRgb(fallbackReadBuffer, sourceWidth, sourceHeight, rgb, outputWidth, outputHeight);
        return new CapturedFrame(rgb, outputWidth, outputHeight, System.nanoTime());
    }

    private void initializePbos(int newSourceWidth, int newSourceHeight) {
        deletePbosSafely();
        sourceWidth = newSourceWidth;
        sourceHeight = newSourceHeight;
        sourceByteSize = checkedByteSize(sourceWidth, sourceHeight);
        pboWriteIndex = 0;
        hasPendingPboFrame = false;

        int previousPbo = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        try {
            for (int i = 0; i < pboIds.length; i++) {
                pboIds[i] = GL15.glGenBuffers();
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pboIds[i]);
                GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, sourceByteSize, GL15.GL_STREAM_READ);
            }
        } finally {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, previousPbo);
        }
    }

    private void submitAsyncRead() {
        int previousReadFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int previousPbo = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        int previousPackAlignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);

        try {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
            GL11.glReadBuffer(GL11.GL_BACK);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);

            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pboIds[pboWriteIndex]);
            GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, sourceByteSize, GL15.GL_STREAM_READ);
            GL11.glReadPixels(0, 0, sourceWidth, sourceHeight, GL12.GL_BGR, GL11.GL_UNSIGNED_BYTE, 0L);

            pboWriteIndex = (pboWriteIndex + 1) % pboIds.length;
            hasPendingPboFrame = true;
        } finally {
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, previousPackAlignment);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, previousPbo);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousReadFramebuffer);
        }
    }

    private CapturedFrame mapPendingPboFrame() {
        int readIndex = (pboWriteIndex + 1) % pboIds.length;
        int previousPbo = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        ByteBuffer mapped = null;
        try {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pboIds[readIndex]);
            mapped = GL15.glMapBuffer(GL21.GL_PIXEL_PACK_BUFFER, GL15.GL_READ_ONLY, sourceByteSize, null);
            if (mapped == null) return null;

            byte[] rgb = new byte[outputByteSize];
            copyFlipScaleBgrToRgb(mapped, sourceWidth, sourceHeight, rgb, outputWidth, outputHeight);
            return new CapturedFrame(rgb, outputWidth, outputHeight, System.nanoTime());
        } finally {
            if (mapped != null) GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, previousPbo);
        }
    }

    private void ensureFallbackReadBuffer() {
        if (fallbackReadBuffer == null || fallbackReadBuffer.capacity() < sourceByteSize) {
            if (fallbackReadBuffer != null) MemoryUtil.memFree(fallbackReadBuffer);
            fallbackReadBuffer = MemoryUtil.memAlloc(sourceByteSize);
        }
    }

    private static int checkedByteSize(int width, int height) {
        long bytes = (long) width * height * BYTES_PER_PIXEL;
        if (bytes > Integer.MAX_VALUE) throw new IllegalArgumentException("Too large");
        return (int) bytes;
    }

    private static void copyFlipScaleBgrToRgb(ByteBuffer srcBgrBottomUp, int srcW, int srcH,
                                              byte[] dstRgbTopDown, int dstW, int dstH) {
        for (int dstY = 0; dstY < dstH; dstY++) {
            int srcY = (int) ((dstY / (double) dstH) * srcH);
            srcY = srcH - 1 - Math.min(srcH - 1, srcY);
            for (int dstX = 0; dstX < dstW; dstX++) {
                int srcX = (int) ((dstX / (double) dstW) * srcW);
                srcX = Math.min(srcW - 1, srcX);
                int si = (srcY * srcW + srcX) * BYTES_PER_PIXEL;
                int di = (dstY * dstW + dstX) * BYTES_PER_PIXEL;
                dstRgbTopDown[di]     = srcBgrBottomUp.get(si + 2);
                dstRgbTopDown[di + 1] = srcBgrBottomUp.get(si + 1);
                dstRgbTopDown[di + 2] = srcBgrBottomUp.get(si);
            }
        }
    }

    private void deletePbosSafely() {
        try {
            for (int id : pboIds) if (id != 0) GL15.glDeleteBuffers(id);
        } catch (Throwable ignored) {}
        hasPendingPboFrame = false;
    }

    @Override
    public synchronized void close() {
        deletePbosSafely();
        if (fallbackReadBuffer != null) {
            MemoryUtil.memFree(fallbackReadBuffer);
            fallbackReadBuffer = null;
        }
    }

    public record CapturedFrame(byte[] rgbPixels, int width, int height, long capturedAtNanos) {}
}