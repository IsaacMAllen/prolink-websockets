package com.bugbytz.prolink;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.deepsymmetry.beatlink.CdjStatus;
import org.deepsymmetry.beatlink.VirtualCdj;

public class LoadCommandConsumer {
    public void startConsuming() {
        App.getLoadWebSocketServer().setMessageHandler(message -> {
            try {
                ObjectMapper mapper = new ObjectMapper();
                LoadRequest request = mapper.readValue(message, LoadRequest.class);
                VirtualCdj.getInstance().sendLoadTrackCommand(
                        request.getTargetPlayer(),
                        request.getRekordboxId(),
                        request.getSourcePlayer(),
                        CdjStatus.TrackSourceSlot.USB_SLOT,
                        CdjStatus.TrackType.REKORDBOX
                );
                System.out.println("Processed load command for player " + request.getTargetPlayer());
            } catch (Exception e) {
                System.err.println("Failed to process load request: " + e.getMessage());
                e.printStackTrace();
            }
        });
        App.getTrackWebSocketServer().start();
    }
}
