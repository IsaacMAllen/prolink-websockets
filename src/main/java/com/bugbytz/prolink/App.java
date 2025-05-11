package com.bugbytz.prolink;

import org.deepsymmetry.beatlink.*;
import org.deepsymmetry.beatlink.data.*;

import java.nio.ByteBuffer;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class App {
    private static final int FPS = 30;
    private static final int FRAME_INTERVAL_MS = 1000 / FPS;
    private static final int WIDTH = 800;
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
    private static final Map<Integer, ProLinkWebSocketServer> frameServers = new ConcurrentHashMap<>();
    private static final Map<Integer, ProLinkWebSocketServer> artServers = new ConcurrentHashMap<>();

    private static final ProLinkWebSocketServer trackWebSocketServer = new ProLinkWebSocketServer(2000);
    private static final ProLinkWebSocketServer deviceWebSocketServer = new ProLinkWebSocketServer(3000);
    private static final ProLinkWebSocketServer loadWebSocketServer = new ProLinkWebSocketServer(4000);
    private static final Map<Integer, AtomicBoolean> rendering = new ConcurrentHashMap<>();
    private static final Map<Integer, AtomicBoolean> deviceSending = new ConcurrentHashMap<>();

    private static final Map<Integer, Long> playbackTimeCache = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> playbackTimeTimestamp = new ConcurrentHashMap<>();
    private static final long CACHE_VALIDITY_NS = 1_000_000; // 1ms

    private static final Map<Integer, Long> lastGoodRenderTime = new ConcurrentHashMap<>();
    private static final Map<Integer, ByteBuffer> nativeRgbaBuffers = new ConcurrentHashMap<>();

    private static long getMonotonicRenderTime(int player, long newTime) {
        return lastGoodRenderTime.merge(player, newTime, Math::max);
    }

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

    private static long getStablePlaybackTime(int player) {
        long now = System.nanoTime();
        long lastQueryTime = playbackTimeTimestamp.getOrDefault(player, 0L);
        if (now - lastQueryTime < CACHE_VALIDITY_NS) {
            return playbackTimeCache.getOrDefault(player, 0L);
        } else {
            long newTime = TimeFinder.getInstance().getTimeFor(player);
            playbackTimeCache.put(player, newTime);
            playbackTimeTimestamp.put(player, now);
            return newTime;
        }
    }

    private static void getWaveformForPlayer(int player) {
        if (rendering.computeIfAbsent(player, p -> new AtomicBoolean(false)).getAndSet(true)) {
            return;
        }

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

                ByteBuffer buffer = nativeRgbaBuffers.computeIfAbsent(player,
                        p -> ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4)); // RGBA

                long renderStart = System.nanoTime();
                ByteBuffer waveDataRaw = detail.getData();
                ByteBuffer waveData = ByteBuffer.allocateDirect(waveDataRaw.capacity());
                waveData.put(waveDataRaw);
                waveData.rewind();
                long queriedTime = getStablePlaybackTime(player);
                long renderNow = System.nanoTime();
                long renderTime = queriedTime + ((renderNow - playbackTimeTimestamp.getOrDefault(player, renderNow)) / 1_000_000);
                int halfFrameOffset = Util.timeToHalfFrame(renderTime);
                NativeWaveformRenderer.render(
                        waveData,
                        detail.getFrameCount(),
                        detail.style.ordinal(),
                        halfFrameOffset,
                        1,
                        WIDTH,
                        HEIGHT,
                        buffer
                );

                printIfOver(renderStart, 154, player);

                // Send buffer to clients
                wsServer.broadcastFrame(buffer); // you must support ByteBuffer here now

                printIfOver(renderStart, 167, player);
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                rendering.get(player).set(false);
            }
        });
    }

    public static void printIfOver(long beforeSend, int lineNumber, int player) {
        long afterSend = System.nanoTime();
        long delta = (afterSend - beforeSend) / 1_000_000;
        if (delta >= 19)
            System.out.printf("Player %d: Time ALARM %d ms LINE %d\n", player, delta, lineNumber);
    }

    public static void main(String[] args) throws Exception {
        VirtualCdj.getInstance().setDeviceNumber((byte) 5);
        CrateDigger.getInstance().addDatabaseListener(new DBService());

        VirtualCdj.getInstance().addUpdateListener(update -> {
            if (update instanceof CdjStatus cdjStatus) {
                int deviceNumber = update.getDeviceNumber();
                AtomicBoolean sendingFlag = deviceSending.computeIfAbsent(deviceNumber, k -> new AtomicBoolean(false));

                if (!sendingFlag.compareAndSet(false, true)) {
                    return;
                }

                DecimalFormat df = new DecimalFormat("#.##");
                try {
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
                            update.getDeviceName(),
                            cdjStatus.isTempoMaster()
                    );

                    deviceWebSocketServer.broadcastStatus(deviceStatus);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    sendingFlag.set(false);
                }
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

        ArtFinder.getInstance().setRequestHighResolutionArt(true);
        ArtFinder.getInstance().addAlbumArtListener(update -> {
            ProLinkWebSocketServer wsServer = artServers.computeIfAbsent(update.player, p -> {
                ProLinkWebSocketServer server = new ProLinkWebSocketServer(6000 + p);
                server.start();
                return server;
            });
            ByteBuffer buffer = update.art.getRawBytes();
            byte[] bytes;

            if (buffer.hasArray()) {
                // Use backing array if available (faster)
                bytes = buffer.array();
            } else {
                // Copy content into a new array
                bytes = new byte[buffer.remaining()];
                buffer.mark(); // mark the current position
                buffer.get(bytes);
                buffer.reset(); // reset back to the original position if needed
            }
            wsServer.broadcastRawBytes(bytes);
        });
        ArtFinder.getInstance().start();
        BeatGridFinder.getInstance().start();
        MetadataFinder.getInstance().start();
        TimeFinder.getInstance().start();
        DeviceFinder.getInstance().start();
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
            CrateDigger.getInstance().stop();
            DeviceFinder.getInstance().stop();
            TimeFinder.getInstance().stop();
            MetadataFinder.getInstance().stop();
            BeatGridFinder.getInstance().stop();
            VirtualCdj.getInstance().stop();
            ArtFinder.getInstance().stop();
            artServers.forEach((player, server) -> server.shutdown());
            frameServers.forEach((player, server) -> server.shutdown());
        }));
    }
}