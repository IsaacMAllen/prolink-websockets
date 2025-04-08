package com.bugbytz.prolink;

import org.deepsymmetry.beatlink.data.DatabaseListener;
import org.deepsymmetry.beatlink.data.SlotReference;
import org.deepsymmetry.cratedigger.Database;
import org.deepsymmetry.cratedigger.pdb.RekordboxPdb;

import java.util.HashMap;
import java.util.Map;

public class DBService implements DatabaseListener {
    @Override
    public void databaseMounted(SlotReference slot, Database database) {
        ProLinkWebSocketServer wsServer = App.getTrackWebSocketServer();

        try {
            Map<Long, String> artists = new HashMap<>();
            database.artistIndex.forEach((id, artistRow) ->
                    artists.put(id, extractText(artistRow.name())));

            Map<Long, String> keys = new HashMap<>();
            database.musicalKeyIndex.forEach((id, keyRow) ->
                    keys.put(id, extractText(keyRow.name())));

            database.trackIndex.forEach((id, trackRow) -> {
                Track track = new Track(
                        id,
                        extractText(trackRow.title()),
                        trackRow.tempo() / 100.0,
                        trackRow.rating(),
                        trackRow.artworkId(),
                        keys.get(trackRow.keyId()),
                        artists.get(trackRow.artistId()),
                        trackRow.artistId(),
                        trackRow.duration() / 60,
                        trackRow.duration() % 60,
                        slot.player
                );
                wsServer.broadcastTrack(track);
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    private String extractText(RekordboxPdb.DeviceSqlString sqlString) {
        if (sqlString.body() instanceof RekordboxPdb.DeviceSqlShortAscii) {
            return ((RekordboxPdb.DeviceSqlShortAscii) sqlString.body()).text();
        } else if (sqlString.body() instanceof RekordboxPdb.DeviceSqlLongUtf16le) {
            return ((RekordboxPdb.DeviceSqlLongUtf16le) sqlString.body()).text();
        } else {
            return "Unknown SQL string type";
        }
    }

    @Override
    public void databaseUnmounted(SlotReference slot, Database database) {
        System.out.println("");
    }
}
