package com.bugbytz.prolink;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class DeviceStatus {
    private int deviceNumber;
    private boolean isPlaying;
    private int beat;
    private int beatWithinBar;
    private double tempo;
    private double pitch;
    private String ip;
    private String macAddress;
    private int rekordboxId;
    private String deviceName;
}
