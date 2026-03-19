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

    // Small pool: renders are one-shot per track-load, not 30/60 Hz.
    private static final ExecutorService renderExecutor = Executors.newFixedThreadPool(4);

    // Static full-track waveform servers: port 8001-8004 (one per player).
    // Sends a single 800×200 RGBA frame when a track loads; client caches it
    // and animates a local playhead from beat+tempo in DeviceStatus.
    private static final Map<Integer, ProLinkWebSocketServer> staticWaveServers = new ConcurrentHashMap<>();
    private static final Map<Integer, byte[]>                 rgbaBuffers        = new ConcurrentHashMap<>();

    // Album art servers: port 6001-6004 (unchanged from minisforum branch).
    private static final Map<Integer, ProLinkWebSocketServer> artServers = new ConcurrentHashMap<>();

    private static final ProLinkWebSocketServer trackWebSocketServer  = new ProLinkWebSocketServer(2000);
    private static final ProLinkWebSocketServer deviceWebSocketServer = new ProLinkWebSocketServer(3000);
    private static final ProLinkWebSocketServer loadWebSocketServer   = new ProLinkWebSocketServer(4000);

    private static final Map<Integer, AtomicBoolean> deviceSending = new ConcurrentHashMap<>();

    public static ProLinkWebSocketServer getTrackWebSocketServer() { return trackWebSocketServer; }
    public static ProLinkWebSocketServer getLoadWebSocketServer()  { return loadWebSocketServer; }

    public static String byteArrayToMacString(byte[] macBytes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < macBytes.length; i++)
            sb.append(String.format("%02X%s", macBytes[i], (i < macBytes.length - 1) ? ":" : ""));
        return sb.toString();
    }

    // BufferedImage.TYPE_4BYTE_ABGR stores [A, B, G, R]; we need [R, G, B, A] for the client.
    private static void convertABGRtoRGBA(byte[] abgr, byte[] rgba) {
        for (int i = 0; i < abgr.length; i += 4) {
            rgba[i]     = abgr[i + 3]; // R
            rgba[i + 1] = abgr[i + 2]; // G
            rgba[i + 2] = abgr[i + 1]; // B
            rgba[i + 3] = abgr[i];     // A
        }
    }

    /**
     * Renders a full-track waveform preview (STATIC_WIDTH × STATIC_HEIGHT, RGBA) for
     * {@code player} and broadcasts it to all connected clients on port {@code 8000 + player}.
     *
     * Renders directly from WaveformPreview segment data — no Swing component needed, works
     * correctly in any headless environment. Triggered once per track load; the client caches
     * the texture and animates a local playhead using beat + tempo from DeviceStatus.
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

                final int segs      = preview.segmentCount;
                final int maxH      = Math.max(preview.maxHeight, 1);
                final boolean is3b  = preview.style == WaveformFinder.WaveformStyle.THREE_BAND;

                for (int col = 0; col < STATIC_WIDTH; col++) {
                    int seg = col * segs / STATIC_WIDTH;
                    if (seg >= segs) seg = segs - 1;

                    if (is3b) {
                        // Heights are cumulative: LOW ⊂ MID ⊂ HIGH
                        int lowH  = preview.segmentHeight(seg, WaveformFinder.ThreeBandLayer.LOW);
                        int midH  = preview.segmentHeight(seg, WaveformFinder.ThreeBandLayer.MID);
                        int highH = preview.segmentHeight(seg, WaveformFinder.ThreeBandLayer.HIGH);
                        if (highH > 0) {
                            int yHigh = STATIC_HEIGHT - highH * STATIC_HEIGHT / maxH;
                            int yMid  = STATIC_HEIGHT - midH  * STATIC_HEIGHT / maxH;
                            int yLow  = STATIC_HEIGHT - lowH  * STATIC_HEIGHT / maxH;
                            g.setColor(new Color(160, 222, 255)); // high — cyan/white
                            g.fillRect(col, yHigh, 1, Math.max(0, yMid  - yHigh));
                            g.setColor(new Color(0,   220,  80)); // mid  — green
                            g.fillRect(col, yMid,  1, Math.max(0, yLow  - yMid));
                            g.setColor(new Color(255, 120,   0)); // low  — orange
                            g.fillRect(col, yLow,  1, Math.max(0, STATIC_HEIGHT - yLow));
                        }
                    } else {
                        int height = preview.segmentHeight(seg, true);
                        if (height > 0) {
                            Color color  = preview.segmentColor(seg, true);
                            int   pixH   = height * STATIC_HEIGHT / maxH;
                            g.setColor(color);
                            g.fillRect(col, STATIC_HEIGHT - pixH, 1, pixH);
                        }
                    }
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
                System.out.println("Static waveform broadcast for player " + player +
                        " (" + segs + " segments, maxH=" + maxH + ", style=" + preview.style + ")");

            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }


    /**
     * Returns the track position in milliseconds for the given player.
     * Because our addUpdateListener is registered AFTER TimeFinder.start(),
     * TimeFinder's internal listener runs first on every packet — so
     * getLatestPositionFor() reflects the current packet's computed position
     * with no one-packet lag. TimeFinder handles playing (interpolated),
     * paused (beat-snapped), and CDJ-3000 precise-position modes.
     */
    // For debug: track last logged values to avoid spamming the log
    private static final Map<Integer, Long>  dbgLastPbt  = new ConcurrentHashMap<>();
    private static final Map<Integer, Integer> dbgLastBeat = new ConcurrentHashMap<>();

    private static long resolvePlaybackTime(int player) {
        try {
            TrackPositionUpdate pos = TimeFinder.getInstance().getLatestPositionFor(player);
            long result = (pos != null) ? pos.milliseconds : -1;
            // Debug: log whenever beat or playbackTime changes significantly
            return result;
        } catch (Exception ignored) {}
        return -1;
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        VirtualCdj.getInstance().setDeviceNumber((byte) 5);
        CrateDigger.getInstance().addDatabaseListener(new DBService());


        // Fire a one-shot static waveform render whenever a new waveform preview arrives.
        // This is the primary trigger: preview data becomes available shortly after a track
        // loads, making it the earliest reliable signal for full-track waveform data.
        WaveformFinder.getInstance().addWaveformListener(new WaveformListener() {
            @Override
            public void previewChanged(WaveformPreviewUpdate update) {
                if (update.preview != null) {
                    sendStaticWaveformForPlayer(update.player);
                }
            }

            @Override
            public void detailChanged(WaveformDetailUpdate update) {
                // Detail not used — playhead is computed client-side.
            }
        });

        // Secondary trigger: resend if metadata arrives after the waveform event fired.
        MetadataFinder.getInstance().addTrackMetadataListener(update -> {
            if (update.metadata != null) {
                sendStaticWaveformForPlayer(update.player);
            }
        });

        DeviceFinder.getInstance().addDeviceAnnouncementListener(new DeviceAnnouncementAdapter() {
            @Override
            public void deviceFound(DeviceAnnouncement announcement) {
                if (!VirtualCdj.getInstance().isRunning()) {
                    try {
                        VirtualCdj.getInstance().start();
                        CrateDigger.getInstance().start();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
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

        // Album art — ports 6001-6004 (unchanged).
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
                buffer.mark();
                buffer.get(bytes);
                buffer.reset();
            }
            wsServer.broadcastRawBytes(bytes);
        });

        ArtFinder.getInstance().start();
        WaveformFinder.getInstance().start();
        BeatGridFinder.getInstance().start();
        MetadataFinder.getInstance().start();
        TimeFinder.getInstance().start();

        // DeviceStatus (beat, tempo, pitch, isMaster, etc.) — consumed by the client to
        // calculate the live playhead position against the static waveform texture.
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

                    int    rawBeat   = cdjStatus.getBeatNumber();
                    long   pbt       = resolvePlaybackTime(deviceNumber);
                    // Debug: log beat + playbackTime when they change (throttled)
                    long   lastPbt   = dbgLastPbt.getOrDefault(deviceNumber, Long.MIN_VALUE);
                    int    lastBeat  = dbgLastBeat.getOrDefault(deviceNumber, Integer.MIN_VALUE);
                    if (rawBeat != lastBeat || Math.abs(pbt - lastPbt) > 500) {
                        TrackPositionUpdate dbgPos = TimeFinder.getInstance().getLatestPositionFor(deviceNumber);
                        BeatGrid dbgGrid = BeatGridFinder.getInstance().getLatestBeatGridFor(deviceNumber);
                        System.err.printf("[DBG p%d] beat=%d  pbt=%d  tf=%s  grid=%s  state=%s%n",
                                deviceNumber, rawBeat, pbt,
                                dbgPos == null ? "null" : dbgPos.milliseconds + "ms",
                                dbgGrid == null ? "null" : dbgGrid.beatCount + "beats",
                                cdjStatus.getPlayState1());
                        dbgLastPbt.put(deviceNumber, pbt);
                        dbgLastBeat.put(deviceNumber, rawBeat);
                    }
                    DeviceStatus deviceStatus = new DeviceStatus(
                            deviceNumber,
                            cdjStatus.isPlaying() || !cdjStatus.isPaused(),
                            rawBeat,
                            update.getBeatWithinBar(),
                            Double.parseDouble(df.format(update.getEffectiveTempo())),
                            Double.parseDouble(df.format(Util.pitchToPercentage(update.getPitch()))),
                            update.getAddress().getHostAddress(),
                            byteArrayToMacString(announcement.getHardwareAddress()),
                            cdjStatus.getRekordboxId(),
                            update.getDeviceName(),
                            cdjStatus.isTempoMaster(),
                            pbt
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
        // Enable JSON caching on the track server so reconnecting clients
        // receive the full library immediately without needing a new USB mount.
        trackWebSocketServer.enableJsonCache();
        trackWebSocketServer.start();
        deviceWebSocketServer.start();

        LoadCommandConsumer consumer = new LoadCommandConsumer();
        new Thread(consumer::startConsuming).start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            renderExecutor.shutdown();
            loadWebSocketServer.shutdown();
            trackWebSocketServer.shutdown();
            deviceWebSocketServer.shutdown();
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
