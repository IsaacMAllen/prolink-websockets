package com.bugbytz.prolink;

import org.deepsymmetry.beatlink.*;
import org.deepsymmetry.beatlink.data.*;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

public class App {
    private static final int FPS = 60;
    private static final int FRAME_INTERVAL_MS = 1000 / FPS;
    private static final int WIDTH = 1200;
    private static final int HEIGHT = 200;

    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    private static final ExecutorService renderExecutor = new ThreadPoolExecutor(
            8, 8,
            0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(16),
            new ThreadFactory() {
                private final ThreadFactory defaultFactory = Executors.defaultThreadFactory();
                private int threadCount = 1;

                @Override
                public Thread newThread(Runnable r) {
                    Thread t = defaultFactory.newThread(r);
                    t.setName("RenderThread-" + threadCount++);
                    t.setPriority(Thread.MAX_PRIORITY);
                    return t;
                }
            },
            new ThreadPoolExecutor.DiscardPolicy()
    );

    private static final Map<Integer, ScheduledFuture<?>> schedules = new ConcurrentHashMap<>();
    private static final Set<Integer> streamingPlayers = ConcurrentHashMap.newKeySet();
    private static final Map<Integer, WaveformDetailComponent> waveformComponents = new ConcurrentHashMap<>();
    private static final Map<Integer, ReentrantLock> playerLocks = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> lastPlaybackTimeMap = new ConcurrentHashMap<>();
    private static final Map<Integer, ProLinkWebSocketServer> frameServers = new ConcurrentHashMap<>();
    private static final Map<Integer, byte[]> rgbaBuffers = new ConcurrentHashMap<>();

    private static final ProLinkWebSocketServer trackWebSocketServer = new ProLinkWebSocketServer(2000);
    private static final ProLinkWebSocketServer deviceWebSocketServer = new ProLinkWebSocketServer(3000);
    private static final ProLinkWebSocketServer loadWebSocketServer = new ProLinkWebSocketServer(4000);

    public static ProLinkWebSocketServer getTrackWebSocketServer() {
        return trackWebSocketServer;
    }

    public static ProLinkWebSocketServer getLoadWebSocketServer() {
        return loadWebSocketServer;
    }

    public static String byteArrayToMacString(byte[] macBytes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < macBytes.length; i++) {
            sb.append(String.format("%02X%s", macBytes[i], (i < macBytes.length - 1) ? ":" : ""));
        }
        return sb.toString();
    }

    private static byte[] convertABGRtoRGBA(byte[] abgr, byte[] rgba) {
        for (int i = 0; i < abgr.length; i += 4) {
            byte a = abgr[i];
            byte b = abgr[i + 1];
            byte g = abgr[i + 2];
            byte r = abgr[i + 3];

            rgba[i] = r;
            rgba[i + 1] = g;
            rgba[i + 2] = b;
            rgba[i + 3] = a;
        }
        return rgba;
    }

    private static void getWaveformForPlayer(int player) {
        renderExecutor.submit(() -> {
            try {
                if (!WaveformFinder.getInstance().isRunning()) {
                    WaveformFinder.getInstance().start();
                }
                WaveformDetail detail = WaveformFinder.getInstance().getLatestDetailFor(player);
                if (detail == null) return;

                ProLinkWebSocketServer wsServer = frameServers.computeIfAbsent(player, p -> {
                    ProLinkWebSocketServer server = new ProLinkWebSocketServer(5000 + p);
                    server.start();
                    return server;
                });

                WaveformDetailComponent component = waveformComponents.computeIfAbsent(player, p -> {
                    WaveformDetailComponent c = (WaveformDetailComponent) detail.createViewComponent(
                            MetadataFinder.getInstance().getLatestMetadataFor(player),
                            BeatGridFinder.getInstance().getLatestBeatGridFor(player));
                    c.setPreferredSize(new Dimension(WIDTH, HEIGHT));
                    c.setSize(WIDTH, HEIGHT);
                    c.setScale(1);
                    c.setMonitoredPlayer(player);
                    c.setAutoScroll(true);
                    return c;
                });

                // Create a copy of the image to render into
                BufferedImage frameCopy = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_4BYTE_ABGR);

                ReentrantLock lock = playerLocks.computeIfAbsent(player, p -> new ReentrantLock());

                long currentTime;
                lock.lock();
                try {
                    currentTime = TimeFinder.getInstance().getTimeFor(player);
                    component.setPlaybackState(player, currentTime, true);

                    Graphics2D g = frameCopy.createGraphics();
                    g.setBackground(Color.BLACK);
                    g.clearRect(0, 0, WIDTH, HEIGHT);
                    component.paint(g);
                    g.dispose();

                    lastPlaybackTimeMap.put(player, currentTime);
                } finally {
                    lock.unlock();
                }

                byte[] rawPixels = ((DataBufferByte) frameCopy.getRaster().getDataBuffer()).getData();
                byte[] rgbaBuffer = rgbaBuffers.computeIfAbsent(player, p -> new byte[WIDTH * HEIGHT * 4]);
                wsServer.broadcastFrame(convertABGRtoRGBA(rawPixels, rgbaBuffer));

            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    public static void main(String[] args) throws Exception {
        VirtualCdj.getInstance().setDeviceNumber((byte) 1);
        CrateDigger.getInstance().addDatabaseListener(new DBService());

        VirtualCdj.getInstance().addUpdateListener(update -> {
            if (update instanceof CdjStatus cdjStatus) {
                int deviceNumber = update.getDeviceNumber();
                DecimalFormat df = new DecimalFormat("#.##");
                DeviceAnnouncement announcement = DeviceFinder.getInstance().getLatestAnnouncementFrom(deviceNumber);
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

        DeviceFinder.getInstance().addDeviceAnnouncementListener(new DeviceAnnouncementAdapter() {
            @Override
            public void deviceFound(DeviceAnnouncement announcement) {
                int player = announcement.getDeviceNumber();
                if (streamingPlayers.add(player)) {
                    ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                            () -> getWaveformForPlayer(player), 0, FRAME_INTERVAL_MS, TimeUnit.MILLISECONDS);
                    schedules.put(player, future);
                }
            }

            @Override
            public void deviceLost(DeviceAnnouncement announcement) {
                int player = announcement.getDeviceNumber();
                ScheduledFuture<?> future = schedules.remove(player);
                if (future != null) future.cancel(true);
                streamingPlayers.remove(player);
                ProLinkWebSocketServer wsServer = frameServers.get(announcement.getDeviceNumber());
                if (wsServer != null) {
                    wsServer.shutdown();
                    frameServers.remove(announcement.getDeviceNumber());
                }
            }
        });
        BeatGridFinder.getInstance().start();
        MetadataFinder.getInstance().start();
        VirtualCdj.getInstance().start();
        TimeFinder.getInstance().start();
        DeviceFinder.getInstance().start();
        CrateDigger.getInstance().start();
        trackWebSocketServer.start();
        deviceWebSocketServer.start();

        LoadCommandConsumer consumer = new LoadCommandConsumer();
        Thread consumerThread = new Thread(consumer::startConsuming);
        consumerThread.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            scheduler.shutdown();
            renderExecutor.shutdown();
            loadWebSocketServer.shutdown();
            trackWebSocketServer.shutdown();
            deviceWebSocketServer.shutdown();
        }));
    }
}