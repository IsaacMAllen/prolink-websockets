package com.bugbytz.prolink;

import java.nio.ByteBuffer;

public class NativeWaveformRenderer {
    static {
        try {
            System.getenv("WAVEFORM_LIB_PATH");
            System.err.println("✅ Native waveform library loaded successfully");
        } catch (UnsatisfiedLinkError e) {
            System.err.println("❌ Failed to load native waveform library: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static native void render(
            ByteBuffer waveData,
            int frameCount,
            int styleOrdinal,
            int halfFrameOffset,
            int scale,
            int width,
            int height,
            ByteBuffer outputRgbaBuffer
    );
}