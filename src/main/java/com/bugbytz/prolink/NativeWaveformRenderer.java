package com.bugbytz.prolink;

import java.nio.ByteBuffer;

public class NativeWaveformRenderer {
    static {
        try {
            System.load("/home/lc4/prolink-websockets/src/main/resources/libwaveform.so");
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