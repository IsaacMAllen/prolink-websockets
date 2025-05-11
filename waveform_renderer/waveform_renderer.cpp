#include <jni.h>
#include <cstdint>
#include <cstring>
#include <cstdio>

extern "C" JNIEXPORT void JNICALL Java_com_bugbytz_prolink_NativeWaveformRenderer_render(
    JNIEnv* env,
    jclass,
    jobject waveDataBuffer,
    jint frameCount,
    jint styleOrdinal,
    jint halfFrameOffset,
    jint scale,
    jint width,
    jint height,
    jobject outputRgbaBuffer
) {
    styleOrdinal = 0;
    uint8_t* waveData = static_cast<uint8_t*>(env->GetDirectBufferAddress(waveDataBuffer));
    uint8_t* output = static_cast<uint8_t*>(env->GetDirectBufferAddress(outputRgbaBuffer));
    if (!waveData || !output) {
        return;
    }


    const int axis = height / 2;
    const int verticalMargin = 15;
    const int maxHeight = axis - verticalMargin;

    std::memset(output, 0, width * height * 4); // Clear buffer

    int debugPrints = 0;

    for (int x = 0; x < width; ++x) {
        int segment = halfFrameOffset / scale + (x - width / 2) * scale;
        if (segment < 0 || segment >= frameCount) continue;

        int sum = 0, r = 0, g = 0, b = 0;
        int samplesUsed = 0;

        for (int i = segment; i < segment + scale && i < frameCount; ++i) {
            if (styleOrdinal == 0) { // RGB style
                int base = i * 2;
                uint16_t bits = (waveData[base] << 8) | waveData[base + 1];
                sum += (bits >> 2) & 0x1F;
                r += (bits >> 13) & 0x7;
                g += (bits >> 10) & 0x7;
                b += (bits >> 7) & 0x7;

            } else if (styleOrdinal == 1) { // BLUE style
                uint8_t byte = waveData[i];
                sum += byte & 0x1F;
                r += 0;
                g += 0;
                b += 128;
            }
            samplesUsed++;
        }

        if (samplesUsed == 0) continue;
        int heightNorm = (sum / samplesUsed) * maxHeight / 31;
        if (heightNorm == 0) continue;

        int colorR, colorG, colorB;

        if (styleOrdinal == 0) {
            colorR = (r * 255) / (samplesUsed * 7);
            colorG = (g * 255) / (samplesUsed * 7);
            colorB = (b * 255) / (samplesUsed * 7);
        } else {
            colorR = 0;
            colorG = 0;
            colorB = 255;
        }

        for (int y = axis - heightNorm; y <= axis + heightNorm; ++y) {
            if (y < 0 || y >= height) continue;
            int index = (y * width + x) * 4;
            output[index] = static_cast<uint8_t>(colorR);
            output[index + 1] = static_cast<uint8_t>(colorG);
            output[index + 2] = static_cast<uint8_t>(colorB);
            output[index + 3] = 255;
        }
    }

    // Draw red center playhead marker
    int markerColor[4] = {255, 0, 0, 255};
    for (int y = 0; y < height; ++y) {
        for (int i = 0; i < 2; ++i) {
            int x = width / 2 - 1 + i;
            if (x < 0 || x >= width) continue;
            int index = (y * width + x) * 4;
            std::memcpy(&output[index], markerColor, 4);
        }
    }
}
