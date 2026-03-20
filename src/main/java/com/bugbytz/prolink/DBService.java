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
        sendTracks(database);
        sendPlaylists(database);
    }

    // ── Track library ─────────────────────────────────────────────────────────

    private void sendTracks(Database database) {
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

    private void sendPlaylists(Database database) {
        ProLinkWebSocketServer playlistServer = App.getPlaylistWebSocketServer();
        try {
            // Build track-ID sets per playlist from playlistIndex entries.
            // PlaylistEntryRow has playlistId() and trackId(); entries are ordered
            // by (playlistId, entryIndex) so we accumulate in insertion order.
            Map<Long, List<Long>> playlistTracks = new LinkedHashMap<>();
            try {
                database.playlistIndex.forEach((key, entry) -> {
                    long plId = entry.playlistId();
                    long trId = entry.trackId();
                    playlistTracks.computeIfAbsent(plId, k -> new ArrayList<>()).add(trId);
                });
            } catch (Exception ignored) {
                // playlistIndex may not be populated on all firmware versions; fall through.
            }

            // Walk the tree: folders first, then leaf playlists.
            database.playlistTreeIndex.forEach((id, treeRow) -> {
                try {
                    String name     = extractText(treeRow.name());
                    boolean isFolder = treeRow.isFolder();
                    long    parentId = treeRow.parentId();
                    int     sort     = (int) treeRow.sortOrder();

                    List<Long> trackIds = isFolder
                            ? Collections.emptyList()
                            : playlistTracks.getOrDefault(id, Collections.emptyList());

                    PlaylistNode node = new PlaylistNode(id, name, isFolder, parentId, sort, trackIds);
                    try {
                        playlistServer.broadcastRawJson(mapper.writeValueAsBytes(node));
                    } catch (Exception ex) {
                        System.err.println("Failed to serialise PlaylistNode " + id + ": " + ex.getMessage());
                    }
                } catch (Exception rowEx) {
                    System.err.println("Skipping playlist tree row: " + rowEx.getMessage());
                }
            });

            System.out.println("Playlist tree sent (" + database.playlistTreeIndex.size() + " nodes)");
        } catch (Exception e) {
            System.err.println("Playlist tree send failed: " + e.getMessage());
            e.printStackTrace();
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
