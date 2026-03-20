package com.bugbytz.prolink;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.deepsymmetry.beatlink.CdjStatus;
import org.deepsymmetry.beatlink.DeviceUpdate;
import org.deepsymmetry.beatlink.VirtualCdj;

public class LoadCommandConsumer {
    public void startConsuming() {
        App.getLoadWebSocketServer().setMessageHandler(message -> {
            try {
                ObjectMapper mapper = new ObjectMapper();
                LoadRequest request = mapper.readValue(message, LoadRequest.class);

                // Auto-detect source slot from the live CDJ status instead of
                // hardcoding USB.  Falls back to USB_SLOT if status is unavailable.
                CdjStatus.TrackSourceSlot slot = CdjStatus.TrackSourceSlot.USB_SLOT;
                try {
                    DeviceUpdate sourceUpdate =
                            VirtualCdj.getInstance().getLatestStatusFor(request.getSourcePlayer());
                    if (sourceUpdate instanceof CdjStatus sourceCdj) {
                        slot = sourceCdj.getTrackSourceSlot();
                        // Some players report NO_TRACK or UNKNOWN when queried mid-load;
                        // fall back to USB in those cases.
                        if (slot == CdjStatus.TrackSourceSlot.NO_TRACK
                                || slot == CdjStatus.TrackSourceSlot.UNKNOWN) {
                            slot = CdjStatus.TrackSourceSlot.USB_SLOT;
                        }
                    }
                } catch (Exception slotEx) {
                    System.err.println("Could not determine source slot, defaulting to USB: "
                            + slotEx.getMessage());
                }

                VirtualCdj.getInstance().sendLoadTrackCommand(
                        request.getTargetPlayer(),
                        request.getRekordboxId(),
                        request.getSourcePlayer(),
                        slot,
                        CdjStatus.TrackType.REKORDBOX
                );
                System.out.println("Processed load command: track " + request.getRekordboxId()
                        + " → player " + request.getTargetPlayer()
                        + " (slot=" + slot + ")");
            } catch (Exception e) {
                System.err.println("Failed to process load request: " + e.getMessage());
                e.printStackTrace();
            }
        });
        App.getLoadWebSocketServer().start();
    }
}
