package com.bugbytz.prolink;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class Track {
    private long id;
    private String title;
    private double tempo;
    private int rating;
    private long artworkId;
    private String key;
    private String artist;
    private long artistId;
    private int minutes;
    private int seconds;
    private int deviceSource;
}
