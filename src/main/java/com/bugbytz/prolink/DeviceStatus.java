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
    private boolean isMaster;
    private long playbackTime;      // exact track position in ms; -1 if unavailable

    // ── New fields ────────────────────────────────────────────────────────────
    /** Fractional position within the current beat (0.0 = beat start, 1.0 = next beat). */
    private double beatPhase;

    /** True when the CDJ's channel is live through the mixer (fader up, xfader open). */
    private boolean isOnAir;

    /** True when the CDJ is actively looping. */
    private boolean isLooping;

    /** Loop-in point in milliseconds from track start; -1 if not looping. */
    private long loopStartMs;

    /** Loop-out point in milliseconds from track start; -1 if not looping. */
    private long loopEndMs;

    /**
     * Source media slot for the current track.
     * One of: "USB", "SD", "COLLECTION", "CD", "NO_TRACK", "UNKNOWN"
     */
    private String trackSourceSlot;
}
