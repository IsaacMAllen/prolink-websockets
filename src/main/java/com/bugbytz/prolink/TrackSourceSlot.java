package com.bugbytz.prolink;

public enum TrackSourceSlot {
    SLOT1(0), SLOT2(1), SLOT3(2), SLOT4(3);

    private final int value;

    TrackSourceSlot(int value) {
        this.value = value;
    }

    public static TrackSourceSlot fromValue(int value) {
        for (TrackSourceSlot slot : values()) {
            if (slot.value == value) {
                return slot;
            }
        }
        throw new IllegalArgumentException("Invalid TrackSourceSlot value: " + value);
    }
}
