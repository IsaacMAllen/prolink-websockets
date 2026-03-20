package com.bugbytz.prolink;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.deepsymmetry.beatlink.data.DatabaseListener;
import org.deepsymmetry.beatlink.data.SlotReference;
import org.deepsymmetry.cratedigger.Database;
import org.deepsymmetry.cratedigger.pdb.RekordboxPdb;

import java.util.*;

public class DBService implements DatabaseListener {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void databaseMounted(SlotReference slot, Database database) {
        sendTracks(slot, database);
        sendPlaylists(database);
    }

    // ── Track library ─────────────────────────────────────────────────────────

    private void sendTracks(SlotReference slot, Database database) {
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

    // ── Playlist tree ─────────────────────────────────────────────────────────
    //
    // CrateDigger API:
    //   database.playlistIndex       Map<Long, List<Long>>   playlist-id → ordered track IDs
    //   database.playlistFolderIndex Map<Long, List<Database.PlaylistFolderEntry>>
    //                                parent-id → children (0L = root)
    //   PlaylistFolderEntry: .name (String), .isFolder (boolean), .id (long)

    private void sendPlaylists(Database database) {
        ProLinkWebSocketServer playlistServer = App.getPlaylistWebSocketServer();
        try {
            sendFolderEntries(database, 0L, playlistServer);
            System.out.println("Playlist tree sent.");
        } catch (Exception e) {
            System.err.println("Playlist tree send failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /** Recursively walk the playlist tree, broadcasting one PlaylistNode per entry. */
    private void sendFolderEntries(Database database, long parentId,
                                   ProLinkWebSocketServer server) {
        List<Database.PlaylistFolderEntry> children =
                database.playlistFolderIndex.get(parentId);
        if (children == null) return;

        for (Database.PlaylistFolderEntry entry : children) {
            if (entry == null) continue; // sparse list can contain nulls

            List<Long> trackIds = entry.isFolder
                    ? Collections.emptyList()
                    : database.playlistIndex.getOrDefault(entry.id, Collections.emptyList());

            PlaylistNode node = new PlaylistNode(
                    entry.id,
                    entry.name,
                    entry.isFolder,
                    parentId,
                    0,        // sortOrder not exposed via PlaylistFolderEntry; position in list is order
                    new ArrayList<>(trackIds)
            );

            try {
                server.broadcastRawJson(mapper.writeValueAsBytes(node));
            } catch (Exception ex) {
                System.err.println("Failed to serialise PlaylistNode " + entry.id
                        + ": " + ex.getMessage());
            }

            // Recurse into folders
            if (entry.isFolder) {
                sendFolderEntries(database, entry.id, server);
            }
        }
    }

    // ── Database unmounted ────────────────────────────────────────────────────

    @Override
    public void databaseUnmounted(SlotReference slot, Database database) {
        App.getTrackWebSocketServer().clearJsonCache();
        App.getPlaylistWebSocketServer().clearJsonCache();
        System.out.println("Database unmounted for slot " + slot + " — caches cleared");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String extractText(RekordboxPdb.DeviceSqlString sqlString) {
        if (sqlString.body() instanceof RekordboxPdb.DeviceSqlShortAscii ascii) {
            return ascii.text();
        } else if (sqlString.body() instanceof RekordboxPdb.DeviceSqlLongUtf16le utf16) {
            return utf16.text();
        }
        return "Unknown SQL string type";
    }
}
