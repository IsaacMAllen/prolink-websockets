package com.bugbytz.prolink;

import org.deepsymmetry.beatlink.*;
import org.deepsymmetry.beatlink.data.*;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class App {
    // Must match WAVE_W × WAVE_H in camera-prolink.cpp
    private static final int STATIC_WIDTH  = 800;
    private static final int STATIC_HEIGHT = 200;

    private static final ExecutorService renderExecutor = Executors.newFixedThreadPool(4);

    // Static full-track waveform servers: port 8001-8004
    private static final Map<Integer, ProLinkWebSocketServer> staticWaveServers = new ConcurrentHashMap<>();
    private static final Map<Integer, byte[]>                 rgbaBuffers        = new ConcurrentHashMap<>();

    // Album art servers: port 6001-6004
    private static final Map<Integer, ProLinkWebSocketServer> artServers = new ConcurrentHashMap<>();

    private static final ProLinkWebSocketServer trackWebSocketServer    = new ProLinkWebSocketServer(2000);
    private static final ProLinkWebSocketServer deviceWebSocketServer   = new ProLinkWebSocketServer(3000);
    private static final ProLinkWebSocketServer loadWebSocketServer     = new ProLinkWebSocketServer(4000);
    // Playlist tree: one JSON PlaylistNode per entry, cached for reconnects
    private static final ProLinkWebSocketServer playlistWebSocketServer = new ProLinkWebSocketServer(2001);

    private static final Map<Integer, AtomicBoolean> deviceSending = new ConcurrentHashMap<>();

    public static ProLinkWebSocketServer getTrackWebSocketServer()    { return trackWebSocketServer; }
    public static ProLinkWebSocketServer getLoadWebSocketServer()     { return loadWebSocketServer; }
    public static ProLinkWebSocketServer getPlaylistWebSocketServer() { return playlistWebSocketServer; }

    // ── Colour conversion ────────────────────────────────────────────────────

    public static String byteArrayToMacString(byte[] macBytes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < macBytes.length; i++)
            sb.append(String.format("%02X%s", macBytes[i], (i < macBytes.length - 1) ? ":" : ""));
        return sb.toString();
    }

    // BufferedImage.TYPE_4BYTE_ABGR → [R,G,B,A]
    private static void convertABGRtoRGBA(byte[] abgr, byte[] rgba) {
        for (int i = 0; i < abgr.length; i += 4) {
            rgba[i]     = abgr[i + 3]; // R
            rgba[i + 1] = abgr[i + 2]; // G
            rgba[i + 2] = abgr[i + 1]; // B
            rgba[i + 3] = abgr[i];     // A
        }
    }

    // ── Static waveform + cue overlay ────────────────────────────────────────

    /**
     * Renders a full-track waveform preview (STATIC_WIDTH × STATIC_HEIGHT RGBA) for
     * {@code player} with hot-cue and memory-cue markers overlaid, then broadcasts it.
     * Triggered once per track load; client caches and animates a local playhead.
     */
    private static void sendStaticWaveformForPlayer(int player) {
        renderExecutor.submit(() -> {
            try {
                WaveformPreview preview = WaveformFinder.getInstance().getLatestPreviewFor(player);
                if (preview == null) {
                    System.out.println("No waveform preview yet for player " + player + "; skipping.");
                    return;
                }

                BufferedImage img = new BufferedImage(STATIC_WIDTH, STATIC_HEIGHT,
                                                      BufferedImage.TYPE_4BYTE_ABGR);
                Graphics2D g = img.createGraphics();
                g.setBackground(Color.BLACK);
                g.clearRect(0, 0, STATIC_WIDTH, STATIC_HEIGHT);

                final int segs  = preview.segmentCount;
                final int maxH  = Math.max(preview.maxHeight, 1);
                final boolean is3b = preview.style == WaveformFinder.WaveformStyle.THREE_BAND;

                // ── Waveform bars ─────────────────────────────────────────────
                for (int col = 0; col < STATIC_WIDTH; col++) {
                    int seg = col * segs / STATIC_WIDTH;
                    if (seg >= segs) seg = segs - 1;

                    if (is3b) {
                        int lowH  = preview.segmentHeight(seg, WaveformFinder.ThreeBandLayer.LOW);
                        int midH  = preview.segmentHeight(seg, WaveformFinder.ThreeBandLayer.MID);
                        int highH = preview.segmentHeight(seg, WaveformFinder.ThreeBandLayer.HIGH);
                        if (highH > 0) {
                            int yHigh = STATIC_HEIGHT - highH * STATIC_HEIGHT / maxH;
                            int yMid  = STATIC_HEIGHT - midH  * STATIC_HEIGHT / maxH;
                            int yLow  = STATIC_HEIGHT - lowH  * STATIC_HEIGHT / maxH;
                            g.setColor(new Color(160, 222, 255));
                            g.fillRect(col, yHigh, 1, Math.max(0, yMid  - yHigh));
                            g.setColor(new Color(0, 220, 80));
                            g.fillRect(col, yMid,  1, Math.max(0, yLow  - yMid));
                            g.setColor(new Color(255, 120, 0));
                            g.fillRect(col, yLow,  1, Math.max(0, STATIC_HEIGHT - yLow));
                        }
                    } else {
                        int height = preview.segmentHeight(seg, true);
                        if (height > 0) {
                            Color color = preview.segmentColor(seg, true);
                            int pixH = height * STATIC_HEIGHT / maxH;
                            g.setColor(color);
                            g.fillRect(col, STATIC_HEIGHT - pixH, 1, pixH);
                        }
                    }
                }

                // ── Cue / memory / loop markers ───────────────────────────────
                // Fetch metadata for this player; getDuration() returns seconds.
                try {
                    TrackMetadata meta = MetadataFinder.getInstance().getLatestMetadataFor(player);
                    if (meta != null) {
                        CueList cueList = meta.getCueList();
                        int durationSec = meta.getDuration();
                        if (cueList != null && durationSec > 0) {
                            long durationMs = (long) durationSec * 1000L;
                            for (CueList.Entry cue : cueList.entries) {
                                if (cue.cueTime < 0) continue;
                                int x = (int) ((double) cue.cueTime / durationMs * STATIC_WIDTH);
                                if (x < 0 || x >= STATIC_WIDTH) continue;

                                Color cueColor = cue.getColor();
                                // Memory cues (hotCueNumber == 0) get a softer grey tint
                                boolean isHotCue = (cue.hotCueNumber > 0);
                                if (!isHotCue) {
                                    cueColor = new Color(180, 180, 180);
                                }

                                // Full-height vertical line
                                g.setColor(cueColor);
                                g.drawLine(x, 0, x, STATIC_HEIGHT - 1);

                                // For loop cues: draw the loop-end marker + a translucent fill
                                if (cue.loopTime > 0) {
                                    int xEnd = (int) ((double) cue.loopTime / durationMs * STATIC_WIDTH);
                                    if (xEnd >= 0 && xEnd < STATIC_WIDTH) {
                                        g.setColor(new Color(cueColor.getRed(),
                                                             cueColor.getGreen(),
                                                             cueColor.getBlue(), 160));
                                        g.drawLine(xEnd, 0, xEnd, STATIC_HEIGHT - 1);

                                        // Translucent region between loop in/out
                                        int lx = Math.min(x, xEnd);
                                        int lw = Math.abs(xEnd - x);
                                        g.setColor(new Color(cueColor.getRed(),
                                                             cueColor.getGreen(),
                                                             cueColor.getBlue(), 35));
                                        g.fillRect(lx, 0, lw, STATIC_HEIGHT);
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception cueEx) {
                    System.err.println("Cue overlay failed for player " + player
                            + ": " + cueEx.getMessage());
                }

                g.dispose();

                byte[] rawPixels = ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
                byte[] rgba = rgbaBuffers.computeIfAbsent(player,
                        p -> new byte[STATIC_WIDTH * STATIC_HEIGHT * 4]);
                convertABGRtoRGBA(rawPixels, rgba);

                ProLinkWebSocketServer srv = staticWaveServers.computeIfAbsent(player, p -> {
                    ProLinkWebSocketServer s = new ProLinkWebSocketServer(8000 + p);
                    s.start();
                    System.out.println("Static waveform server started on port " + (8000 + p));
                    return s;
                });

                srv.broadcastRawBytes(rgba.clone());
                System.out.println("Static waveform broadcast for player " + player
                        + " (" + segs + " segs, maxH=" + maxH + ", style=" + preview.style + ")");

            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    // ── Beat-phase helper ────────────────────────────────────────────────────

    /**
     * Returns 0.0–1.0 fractional position within the current beat using the
     * TimeFinder position and BeatGrid.  Returns 0.0 when data is unavailable.
     */
    private static double computeBeatPhase(int deviceNumber, double effectiveTempo) {
        try {
            TrackPositionUpdate pos =
                    TimeFinder.getInstance().getLatestPositionFor(deviceNumber);
            BeatGrid grid =
                    BeatGridFinder.getInstance().getLatestBeatGridFor(deviceNumber);
            if (pos == null || grid == null || pos.beatNumber <= 0) return 0.0;

            long beatStart = grid.getTimeWithinTrack(pos.beatNumber);
            long beatEnd   = (pos.beatNumber < grid.beatCount)
                    ? grid.getTimeWithinTrack(pos.beatNumber + 1)
                    : beatStart + Math.round(60_000.0 / Math.max(effectiveTempo, 1.0));
            long beatDur   = beatEnd - beatStart;
            if (beatDur <= 0) return 0.0;

            double phase = (double)(pos.milliseconds - beatStart) / beatDur;
            return Math.max(0.0, Math.min(1.0, phase));
        } catch (Exception ignored) { return 0.0; }
    }

    // ── Track source slot → string ────────────────────────────────────────────

    private static String slotName(CdjStatus cdj) {
        try {
            return switch (cdj.getTrackSourceSlot()) {
                case USB_SLOT        -> "USB";
                case SD_SLOT         -> "SD";
                case CD_SLOT         -> "CD";
                case COLLECTION      -> "COLLECTION";
                case NO_TRACK        -> "NO_TRACK";
                default              -> "UNKNOWN";
            };
        } catch (Exception ignored) { return "UNKNOWN"; }
    }

    // ── Active-loop bounds ────────────────────────────────────────────────────

    /** Returns {loopStartMs, loopEndMs} or {-1, -1} when not looping. */
    private static long[] getLoopBounds(int deviceNumber, long playbackTimeMs) {
        try {
            TrackMetadata meta = MetadataFinder.getInstance().getLatestMetadataFor(deviceNumber);
            if (meta == null) return new long[]{-1L, -1L};
            CueList cueList = meta.getCueList();
            if (cueList == null) return new long[]{-1L, -1L};
            for (CueList.Entry cue : cueList.entries) {
                // A loop entry has loopTime > 0 and its range contains the playhead
                if (cue.loopTime > 0
                        && cue.cueTime <= playbackTimeMs
                        && cue.loopTime >= playbackTimeMs) {
                    return new long[]{cue.cueTime, cue.loopTime};
                }
            }
        } catch (Exception ignored) {}
        return new long[]{-1L, -1L};
    }

    // ── Playback time ─────────────────────────────────────────────────────────

    private static final Map<Integer, Long>    dbgLastPbt  = new ConcurrentHashMap<>();
    private static final Map<Integer, Integer> dbgLastBeat = new ConcurrentHashMap<>();

    private static long resolvePlaybackTime(int player) {
        try {
            TrackPositionUpdate pos = TimeFinder.getInstance().getLatestPositionFor(player);
            return (pos != null) ? pos.milliseconds : -1;
        } catch (Exception ignored) {}
        return -1;
    }

    // ── main ─────────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        VirtualCdj.getInstance().setDeviceNumber((byte) 5);
        CrateDigger.getInstance().addDatabaseListener(new DBService());

        // Waveform preview → static waveform + cue overlay
        WaveformFinder.getInstance().addWaveformListener(new WaveformListener() {
            @Override
            public void previewChanged(WaveformPreviewUpdate update) {
                if (update.preview != null) sendStaticWaveformForPlayer(update.player);
            }
            @Override
            public void detailChanged(WaveformDetailUpdate update) {}
        });

        // Secondary trigger: metadata arrives after waveform event
        MetadataFinder.getInstance().addTrackMetadataListener(update -> {
            if (update.metadata != null) sendStaticWaveformForPlayer(update.player);
        });

        DeviceFinder.getInstance().addDeviceAnnouncementListener(new DeviceAnnouncementAdapter() {
            @Override
            public void deviceFound(DeviceAnnouncement announcement) {
                if (!VirtualCdj.getInstance().isRunning()) {
                    try {
                        VirtualCdj.getInstance().start();
                        CrateDigger.getInstance().start();
                    } catch (Exception e) { throw new RuntimeException(e); }
                }
            }
            @Override
            public void deviceLost(DeviceAnnouncement announcement) {
                int player = announcement.getDeviceNumber();
                ProLinkWebSocketServer srv = staticWaveServers.remove(player);
                if (srv != null) {
                    srv.shutdown();
                    System.out.println("Static waveform server shut down for player " + player);
                }
                rgbaBuffers.remove(player);
                deviceSending.remove(player);
            }
        });

        // Album art
        ArtFinder.getInstance().setRequestHighResolutionArt(true);
        ArtFinder.getInstance().addAlbumArtListener(update -> {
            ProLinkWebSocketServer wsServer = artServers.computeIfAbsent(update.player, p -> {
                ProLinkWebSocketServer server = new ProLinkWebSocketServer(6000 + p);
                server.start();
                return server;
            });
            java.nio.ByteBuffer buffer = update.art.getRawBytes();
            byte[] bytes;
            if (buffer.hasArray()) {
                bytes = buffer.array();
            } else {
                bytes = new byte[buffer.remaining()];
                buffer.mark(); buffer.get(bytes); buffer.reset();
            }
            wsServer.broadcastRawBytes(bytes);
        });

        ArtFinder.getInstance().start();
        WaveformFinder.getInstance().start();
        BeatGridFinder.getInstance().start();
        MetadataFinder.getInstance().start();
        TimeFinder.getInstance().start();

        // ── Device status: beat, tempo, pitch + new fields ────────────────────
        VirtualCdj.getInstance().addUpdateListener(update -> {
            if (update instanceof CdjStatus cdjStatus) {
                int deviceNumber = update.getDeviceNumber();
                AtomicBoolean sendingFlag =
                        deviceSending.computeIfAbsent(deviceNumber, k -> new AtomicBoolean(false));
                if (!sendingFlag.compareAndSet(false, true)) return;

                DecimalFormat df = new DecimalFormat("#.##");
                try {
                    DeviceAnnouncement announcement =
                            DeviceFinder.getInstance().getLatestAnnouncementFrom(deviceNumber);
                    if (announcement == null) return;

                    int  rawBeat = cdjStatus.getBeatNumber();
                    long pbt     = resolvePlaybackTime(deviceNumber);

                    // Debug throttle
                    long   lastPbt  = dbgLastPbt.getOrDefault(deviceNumber, Long.MIN_VALUE);
                    int    lastBeat = dbgLastBeat.getOrDefault(deviceNumber, Integer.MIN_VALUE);
                    if (rawBeat != lastBeat || Math.abs(pbt - lastPbt) > 500) {
                        TrackPositionUpdate dbgPos =
                                TimeFinder.getInstance().getLatestPositionFor(deviceNumber);
                        BeatGrid dbgGrid =
                                BeatGridFinder.getInstance().getLatestBeatGridFor(deviceNumber);
                        System.err.printf("[DBG p%d] beat=%d  pbt=%d  tf=%s  grid=%s  state=%s%n",
                                deviceNumber, rawBeat, pbt,
                                dbgPos  == null ? "null" : dbgPos.milliseconds + "ms",
                                dbgGrid == null ? "null" : dbgGrid.beatCount + "beats",
                                cdjStatus.getPlayState1());
                        dbgLastPbt.put(deviceNumber, pbt);
                        dbgLastBeat.put(deviceNumber, rawBeat);
                    }

                    double effectiveTempo = update.getEffectiveTempo();
                    double beatPhase      = computeBeatPhase(deviceNumber, effectiveTempo);

                    boolean isLooping  = cdjStatus.isLooping();
                    long[]  loopBounds = isLooping ? getLoopBounds(deviceNumber, pbt)
                                                   : new long[]{-1L, -1L};

                    DeviceStatus deviceStatus = new DeviceStatus(
                            deviceNumber,
                            cdjStatus.isPlaying() || !cdjStatus.isPaused(),
                            rawBeat,
                            update.getBeatWithinBar(),
                            Double.parseDouble(df.format(effectiveTempo)),
                            Double.parseDouble(df.format(Util.pitchToPercentage(update.getPitch()))),
                            update.getAddress().getHostAddress(),
                            byteArrayToMacString(announcement.getHardwareAddress()),
                            cdjStatus.getRekordboxId(),
                            update.getDeviceName(),
                            cdjStatus.isTempoMaster(),
                            pbt,
                            // ── new fields ──────────────────────────────────
                            beatPhase,
                            cdjStatus.isOnAir(),
                            isLooping,
                            loopBounds[0],
                            loopBounds[1],
                            slotName(cdjStatus)
                    );
                    deviceWebSocketServer.broadcastStatus(deviceStatus);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    sendingFlag.set(false);
                }
            }
        });

        DeviceFinder.getInstance().start();

        // Enable JSON caching so reconnecting clients get the full library immediately.
        trackWebSocketServer.enableJsonCache();
        trackWebSocketServer.start();
        deviceWebSocketServer.start();

        // Playlist tree server — cached so reconnecting clients get the full tree.
        playlistWebSocketServer.enableJsonCache();
        playlistWebSocketServer.start();

        LoadCommandConsumer consumer = new LoadCommandConsumer();
        new Thread(consumer::startConsuming).start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            renderExecutor.shutdown();
            loadWebSocketServer.shutdown();
            trackWebSocketServer.shutdown();
            deviceWebSocketServer.shutdown();
            playlistWebSocketServer.shutdown();
            CrateDigger.getInstance().stop();
            DeviceFinder.getInstance().stop();
            TimeFinder.getInstance().stop();
            MetadataFinder.getInstance().stop();
            BeatGridFinder.getInstance().stop();
            WaveformFinder.getInstance().stop();
            VirtualCdj.getInstance().stop();
            ArtFinder.getInstance().stop();
            artServers.forEach((p, s) -> s.shutdown());
            staticWaveServers.forEach((p, s) -> s.shutdown());
        }));
    }
}
