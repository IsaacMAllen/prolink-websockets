package com.bugbytz.prolink;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class WaveformPixels {
    private int player;
    private int width;
    private int height;
    private byte[] image;
}
