package com.bugbytz.prolink;

import org.deepsymmetry.beatlink.*;
import org.deepsymmetry.beatlink.data.*;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.*;

public class App {
    // Must match WAVE_W × WAVE_H in camera-prolink.cpp
    private static final int STATIC_WIDTH  = 800;
    private static final int STATIC_HEIGHT = 200;

    // Async renderer — small pool; renders are triggered by track-load events, not a 60 Hz timer.
    private static final ExecutorService renderExecutor = Executors.newFixedThreadPool(4);

    // Static full-track waveform servers: port 8001–8004 (player 1–4).
    // Each server sends a single 800×200 RGBA frame per track load. The client
    // caches it and animates a local playhead using beat+tempo from DeviceStatus.
    private static final Map<Integer, ProLinkWebSocketServer> staticWaveServers = new ConcurrentHashMap<>();
    private static final Map<Integer, byte[]>                 rgbaBuffers        = new ConcurrentHashMap<>();

    private static final ProLinkWebSocketServer trackWebSocketServer  = new ProLinkWebSocketServer(2000);
    private static final ProLinkWebSocketServer deviceWebSocketServer = new ProLinkWebSocketServer(3000);
    private static final ProLinkWebSocketServer loadWebSocketServer   = new ProLinkWebSocketServer(4000);

    public static ProLinkWebSocketServer getTrackWebSocketServer() { return trackWebSocketServer; }
    public static ProLinkWebSocketServer getLoadWebSocketServer()  { return loadWebSocketServer; }

    public static String byteArrayToMacString(byte[] macBytes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < macBytes.length; i++)
            sb.append(String.format("%02X%s", macBytes[i], (i < macBytes.length - 1) ? ":" : ""));
        return sb.toString();
    }

    // BufferedImage.TYPE_4BYTE_ABGR stores pixels as [A, B, G, R]; we need [R, G, B, A] for the client.
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
     * Triggered once when the waveform preview data becomes available (i.e. a new track loads).
     * The client caches this texture and animates a local playhead; no further frames are sent
     * until the next track change.
     */
    private static void sendStaticWaveformForPlayer(int player) {
        renderExecutor.submit(() -> {
            try {
                WaveformPreview preview = WaveformFinder.getInstance().getLatestPreviewFor(player);
                if (preview == null) {
                    System.out.println("No waveform preview yet for player " + player + "; skipping.");
                    return;
                }

                TrackMetadata meta     = MetadataFinder.getInstance().getLatestMetadataFor(player);
                BeatGrid      beatGrid = BeatGridFinder.getInstance().getLatestBeatGridFor(player);

                // WaveformPreviewComponent renders the full track as a compact overview strip.
                WaveformPreviewComponent comp =
                        (WaveformPreviewComponent) preview.createViewComponent(meta, beatGrid);
                comp.setPreferredSize(new Dimension(STATIC_WIDTH, STATIC_HEIGHT));
                comp.setSize(STATIC_WIDTH, STATIC_HEIGHT);

                BufferedImage img = new BufferedImage(STATIC_WIDTH, STATIC_HEIGHT,
                                                      BufferedImage.TYPE_4BYTE_ABGR);
                Graphics2D g = img.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,  RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_RENDERING,     RenderingHints.VALUE_RENDER_QUALITY);
                g.setBackground(Color.BLACK);
                g.clearRect(0, 0, STATIC_WIDTH, STATIC_HEIGHT);
                comp.paint(g);
                g.dispose();

                byte[] rawPixels = ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
                byte[] rgba = rgbaBuffers.computeIfAbsent(player, p -> new byte[STATIC_WIDTH * STATIC_HEIGHT * 4]);
                convertABGRtoRGBA(rawPixels, rgba);

                ProLinkWebSocketServer srv = staticWaveServers.computeIfAbsent(player, p -> {
                    ProLinkWebSocketServer s = new ProLinkWebSocketServer(8000 + p);
                    s.start();
                    System.out.println("Static waveform server started on port " + (8000 + p));
                    return s;
                });

                // broadcastFrame also caches the frame so late-connecting clients receive it immediately.
                srv.broadcastFrame(rgba.clone());
                System.out.println("Static waveform broadcast for player " + player);

            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    public static void main(String[] args) throws Exception {
        VirtualCdj.getInstance().setDeviceNumber((byte) 1);
        CrateDigger.getInstance().addDatabaseListener(new DBService());

        // DeviceStatus (beat, tempo, pitch, etc.) → used by the client to compute the live playhead
        // position against the static waveform texture.
        VirtualCdj.getInstance().addUpdateListener(update -> {
            if (update instanceof CdjStatus cdjStatus) {
                int deviceNumber = update.getDeviceNumber();
                DecimalFormat df = new DecimalFormat("#.##");
                DeviceAnnouncement announcement =
                        DeviceFinder.getInstance().getLatestAnnouncementFrom(deviceNumber);
                if (announcement == null) return;

                DeviceStatus deviceStatus = new DeviceStatus(
                        deviceNumber,
                        cdjStatus.isPlaying() || !cdjStatus.isPaused(),
                        cdjStatus.getBeatNumber(),
                        update.getBeatWithinBar(),
                        Double.parseDouble(df.format(update.getEffectiveTempo())),
                        Double.parseDouble(df.format(Util.pitchToPercentage(update.getPitch()))),
                        update.getAddress().getHostAddress(),
                        byteArrayToMacString(announcement.getHardwareAddress()),
                        cdjStatus.getRekordboxId(),
                        update.getDeviceName()
                );
                deviceWebSocketServer.broadcastStatus(deviceStatus);
            }
        });

        // Fire a one-shot static waveform render whenever a new waveform preview arrives.
        // This is the primary trigger: the preview data becomes available shortly after a
        // track loads, making it the earliest reliable signal for full-track waveform data.
        WaveformFinder.getInstance().addWaveformListener(new WaveformListener() {
            @Override
            public void waveformPreviewChanged(WaveformPreviewUpdate update) {
                if (update.preview != null) {
                    sendStaticWaveformForPlayer(update.player);
                }
            }

            @Override
            public void waveformDetailChanged(WaveformDetailUpdate update) {
                // Detail used by beat-link internally; we don't need it here.
            }
        });

        // Secondary trigger: send/resend when metadata arrives, in case the waveform
        // preview beat-link event fires before metadata is populated.
        MetadataFinder.getInstance().addTrackMetadataListener(update -> {
            if (update.metadata != null) {
                sendStaticWaveformForPlayer(update.player);
            }
        });

        // Device lost: tear down the static wave server and free the pixel buffer.
        DeviceFinder.getInstance().addDeviceAnnouncementListener(new DeviceAnnouncementAdapter() {
            @Override
            public void deviceLost(DeviceAnnouncement announcement) {
                int player = announcement.getDeviceNumber();
                ProLinkWebSocketServer srv = staticWaveServers.remove(player);
                if (srv != null) {
                    srv.shutdown();
                    System.out.println("Static waveform server shut down for player " + player);
                }
                rgbaBuffers.remove(player);
            }
        });

        WaveformFinder.getInstance().start();
        BeatGridFinder.getInstance().start();
        MetadataFinder.getInstance().start();
        VirtualCdj.getInstance().start();
        TimeFinder.getInstance().start();
        DeviceFinder.getInstance().start();
        CrateDigger.getInstance().start();
        trackWebSocketServer.start();
        deviceWebSocketServer.start();

        LoadCommandConsumer consumer = new LoadCommandConsumer();
        new Thread(consumer::startConsuming).start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            renderExecutor.shutdown();
            loadWebSocketServer.shutdown();
            trackWebSocketServer.shutdown();
            deviceWebSocketServer.shutdown();
            staticWaveServers.values().forEach(ProLinkWebSocketServer::shutdown);
        }));
    }
}
