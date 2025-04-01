package com.bugbytz.prolink;

import org.apache.kafka.clients.producer.*;
import org.deepsymmetry.beatlink.*;
import org.deepsymmetry.beatlink.data.*;
import org.libjpegturbo.turbojpeg.TJ;
import org.libjpegturbo.turbojpeg.TJCompressor;
import org.libjpegturbo.turbojpeg.TJException;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

public class App {
    private static final int FPS = 30;
    private static final int FRAME_INTERVAL_MS = 1000 / FPS;
    private static final int WIDTH = 500;
    private static final int HEIGHT = 200;

    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    private static final ExecutorService renderExecutor = Executors.newFixedThreadPool(8);

    private static final Map<Integer, ScheduledFuture<?>> schedules = new ConcurrentHashMap<>();
    private static final Set<Integer> streamingPlayers = ConcurrentHashMap.newKeySet();
    private static final Map<Integer, MjpegServer> mjpegServers = new ConcurrentHashMap<>();
    private static final Map<Integer, WaveformDetailComponent> waveformComponents = new ConcurrentHashMap<>();
    private static final Map<Integer, BufferedImage> playerImages = new ConcurrentHashMap<>();
    private static final Map<Integer, ReentrantLock> playerLocks = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> lastPlaybackTimeMap = new ConcurrentHashMap<>();
    private static final Set<TJCompressor> allCompressors = ConcurrentHashMap.newKeySet();

    private static final ThreadLocal<TJCompressor> threadLocalCompressor =
            ThreadLocal.withInitial(() -> {
                try {
                    TJCompressor compressor = new TJCompressor();
                    compressor.set(TJ.PARAM_SUBSAMP, TJ.SAMP_420);
                    compressor.set(TJ.PARAM_QUALITY, 50);
                    allCompressors.add(compressor);
                    return compressor;
                } catch (TJException e) {
                    throw new RuntimeException("Failed to create TJCompressor", e);
                }
            });

    private static final Properties props = new Properties();

    public static String byteArrayToMacString(byte[] macBytes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < macBytes.length; i++) {
            sb.append(String.format("%02X%s", macBytes[i], (i < macBytes.length - 1) ? ":" : ""));
        }
        return sb.toString();
    }

    private static void getWaveformForPlayer(int player) {
        renderExecutor.submit(() -> {
            try {
                if (!WaveformFinder.getInstance().isRunning()) {
                    WaveformFinder.getInstance().start();
                }
                WaveformDetail detail = WaveformFinder.getInstance().getLatestDetailFor(player);
                if (detail == null) return;
                long start = System.nanoTime();
                MjpegServer mjpegServer = mjpegServers.computeIfAbsent(player, k -> {
                    try {
                        return new MjpegServer(5000 + player);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
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

                BufferedImage image = playerImages.computeIfAbsent(player,
                        p -> new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_4BYTE_ABGR));
                ReentrantLock lock = playerLocks.computeIfAbsent(player, p -> new ReentrantLock());

                lock.lock();
                try {
                    Graphics2D g = image.createGraphics();
                    g.setBackground(Color.BLACK);
                    g.clearRect(0, 0, WIDTH, HEIGHT);

                    long currentTime = TimeFinder.getInstance().getTimeFor(player);
                    Long lastTime = lastPlaybackTimeMap.get(player);

                    //if (lastTime == null || Math.abs(currentTime - lastTime) > 2) {
                        component.setPlaybackState(player, currentTime, true);
                        component.paint(g);
                        lastPlaybackTimeMap.put(player, currentTime);
                        TJCompressor compressor = threadLocalCompressor.get();
                        byte[] rawPixels = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
                        compressor.setSourceImage(rawPixels, 0, 0, WIDTH, 0, HEIGHT, TJ.PF_ABGR);
                        mjpegServer.offerFrame(compressor.compress());
                    //}
                    g.dispose();
                } finally {
                    lock.unlock();
                    long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                    System.out.println("Frame took " + elapsedMs + " ms");
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    public static void main(String[] args) throws Exception {
        VirtualCdj.getInstance().setDeviceNumber((byte) 4);
        CrateDigger.getInstance().addDatabaseListener(new DBService());
        props.put("bootstrap.servers", "localhost:9092");
        props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        props.put("value.serializer", "com.bugbytz.prolink.CustomSerializer");
        props.put(ProducerConfig.MAX_REQUEST_SIZE_CONFIG, "20971520");

        VirtualCdj.getInstance().addUpdateListener(update -> {
            if (update instanceof CdjStatus cdjStatus) {
                try (Producer<String, DeviceStatus> producer = new KafkaProducer<>(props)) {
                    DecimalFormat df = new DecimalFormat("#.##");
                    DeviceAnnouncement announcement = DeviceFinder.getInstance().getLatestAnnouncementFrom(update.getDeviceNumber());
                    if (announcement == null) return;

                    DeviceStatus deviceStatus = new DeviceStatus(
                            update.getDeviceNumber(),
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

                    ProducerRecord<String, DeviceStatus> record = new ProducerRecord<>("device-status", "device-" + update.getDeviceNumber(), deviceStatus);
                    producer.send(record).get();
                    producer.flush();

                    if (!WaveformFinder.getInstance().isRunning()) {
                        WaveformFinder.getInstance().start();
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
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
            }
        });

        BeatGridFinder.getInstance().start();
        MetadataFinder.getInstance().start();
        VirtualCdj.getInstance().start();
        TimeFinder.getInstance().start();
        DeviceFinder.getInstance().start();
        CrateDigger.getInstance().start();

        LoadCommandConsumer consumer = new LoadCommandConsumer("localhost:9092", "load-command-group");
        Thread consumerThread = new Thread(consumer::startConsuming);
        consumerThread.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            consumer.shutdown();
            try {
                consumerThread.join();
                scheduler.shutdown();
                renderExecutor.shutdown();
                for (TJCompressor compressor : allCompressors) {
                    compressor.close();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (TJException e) {
                Thread.currentThread().interrupt();
            }
        }));
    }
}
