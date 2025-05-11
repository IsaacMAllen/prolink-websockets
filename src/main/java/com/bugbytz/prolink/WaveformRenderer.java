package com.bugbytz.prolink;

import org.deepsymmetry.beatlink.Util;
import org.deepsymmetry.beatlink.data.BeatGrid;
import org.deepsymmetry.beatlink.data.CueList;
import org.deepsymmetry.beatlink.data.WaveformDetail;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.ByteBuffer;

public class WaveformRenderer {

    private static final int VERTICAL_MARGIN = 15;
    private static final int BEAT_MARKER_HEIGHT = 4;
    private static final int CUE_MARKER_HEIGHT = 4;
    private static final int PLAYBACK_MARKER_WIDTH = 2;

    public static void render(
            BufferedImage image,
            WaveformDetail detail,
            CueList cueList,
            BeatGrid beatGrid,
            long playbackPositionMs,
            int scale
    ) {
        final int width = image.getWidth();
        final int height = image.getHeight();
        final int axis = height / 2;
        final int maxHeight = axis - VERTICAL_MARGIN;

        int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();

        final ByteBuffer waveData = detail.getData();
        final int frameCount = detail.getFrameCount();
        final int halfFrameOffset = Util.timeToHalfFrame(playbackPositionMs);

        // Fill background black
        java.util.Arrays.fill(pixels, 0xFF000000);

        // Draw waveform
        for (int x = 0; x < width; x++) {
            int segment = halfFrameOffset / scale + (x - width / 2) * scale;
            if (segment < 0 || segment >= frameCount) continue;

            int heightNorm = (segmentHeight(detail, waveData, segment, scale) * maxHeight) / 31;
            if (heightNorm == 0) continue;

            int color = segmentColor(detail, waveData, segment, scale);
            for (int y = axis - heightNorm; y <= axis + heightNorm; y++) {
                if (y >= 0 && y < height) {
                    pixels[y * width + x] = color;
                }
            }
        }

        // Draw beat markers
        if (beatGrid != null) {
            long timeStart = playbackPositionMs - Util.halfFrameToTime((width / 2L) * scale);
            long timeEnd = playbackPositionMs + Util.halfFrameToTime((width / 2L) * scale);

            for (int beat = 1; beat <= beatGrid.beatCount; beat++) {
                long beatTime = beatGrid.getTimeWithinTrack(beat);
                if (beatTime < timeStart) continue;
                if (beatTime > timeEnd) break;

                int x = (width / 2) + (Util.timeToHalfFrame(beatTime - playbackPositionMs) / scale);
                if (x < 0 || x >= width) continue;

                int color = beatGrid.getBeatWithinBar(beat) == 1 ? 0xFFFFFFFF : 0xFF808080;
                for (int i = 0; i < BEAT_MARKER_HEIGHT; i++) {
                    int topY = axis - maxHeight - 2 - i;
                    int botY = axis + maxHeight + 2 + i;
                    if (topY >= 0) pixels[topY * width + x] = color;
                    if (botY < height) pixels[botY * width + x] = color;
                }
            }
        }

        // Draw cue markers
        if (cueList != null) {
            for (CueList.Entry cue : cueList.entries) {
                long cueTime = cue.cueTime;
                if (cueTime < 0) continue;

                int x = (width / 2) + (Util.timeToHalfFrame(cueTime - playbackPositionMs) / scale);
                if (x < 0 || x >= width) continue;

                int color = cue.getColor().getRGB();
                for (int i = 0; i < 4; i++) {
                    int y = axis - maxHeight - BEAT_MARKER_HEIGHT - CUE_MARKER_HEIGHT + i;
                    for (int dx = -3 + i; dx <= 3 - i; dx++) {
                        int px = x + dx;
                        if (px >= 0 && px < width && y >= 0 && y < height) {
                            pixels[y * width + px] = color;
                        }
                    }
                }
            }
        }

        // Draw playback marker
        int markerColor = 0xFFFF0000;
        for (int y = 0; y < height; y++) {
            for (int i = 0; i < PLAYBACK_MARKER_WIDTH; i++) {
                int x = width / 2 - (PLAYBACK_MARKER_WIDTH / 2) + i;
                if (x >= 0 && x < width) {
                    pixels[y * width + x] = markerColor;
                }
            }
        }
    }

    private static int segmentHeight(WaveformDetail detail, ByteBuffer waveData, int segment, int scale) {
        int limit = detail.getFrameCount();
        int sum = 0;
        for (int i = segment; (i < segment + scale) && (i < limit); i++) {
            switch (detail.style) {
                case RGB -> sum += (getColorWaveformBits(waveData, i) >> 2) & 0x1f;
                case BLUE -> sum += waveData.get(i) & 0x1f;
                default -> {}
            }
        }
        return sum / scale;
    }

    private static int segmentColor(WaveformDetail detail, ByteBuffer waveData, int segment, int scale) {
        int limit = detail.getFrameCount();
        switch (detail.style) {
            case RGB -> {
                int r = 0, g = 0, b = 0;
                for (int i = segment; (i < segment + scale) && (i < limit); i++) {
                    int bits = getColorWaveformBits(waveData, i);
                    r += (bits >> 13) & 7;
                    g += (bits >> 10) & 7;
                    b += (bits >> 7) & 7;
                }
                r = (r * 255) / (scale * 7);
                g = (g * 255) / (scale * 7);
                b = (b * 255) / (scale * 7);
                return 0xFF000000 | (r << 16) | (g << 8) | b;
            }
            case BLUE -> {
                int sum = 0;
                for (int i = segment; (i < segment + scale) && (i < limit); i++) {
                    sum += (waveData.get(i) & 0xe0) >> 5;
                }
                Color color = WaveformDetail.COLOR_MAP[sum / scale];
                return color.getRGB();
            }
            default -> {
                return 0xFF000000;
            }
        }
    }

    private static int getColorWaveformBits(ByteBuffer waveBytes, int segment) {
        final int base = segment * 2;
        int big = waveBytes.get(base) & 0xff;
        int small = waveBytes.get(base + 1) & 0xff;
        return (big << 8) | small;
    }
}