package com.bugbytz.prolink;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * Represents one node in the Rekordbox playlist tree.
 * Folders have isFolder=true and no trackIds.
 * Leaf playlists have isFolder=false and a list of rekordbox track IDs (in order).
 * parentId=0 means the node lives at the root level.
 */
@Data
@AllArgsConstructor
public class PlaylistNode {
    private long   id;
    private String name;
    private boolean isFolder;
    private long   parentId;       // 0 = root
    private int    sortOrder;
    private List<Long> trackIds;   // ordered track IDs; empty for folders
}
