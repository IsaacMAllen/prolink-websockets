package com.bugbytz.prolink;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.java_websocket.server.WebSocketServer;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class ProLinkWebSocketServer extends WebSocketServer {
    private Consumer<String> messageHandler = null;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<WebSocket, ClientConnection> clients = new ConcurrentHashMap<>();

    private final ByteBuffer framePool = ByteBuffer.allocateDirect(FRAME_SIZE);
    private static final int FRAME_SIZE = 800 * 200 * 4;

    // Persistent JSON cache — replayed to every new client that connects.
    // Enabled on servers that broadcast library / metadata (port 2000).
    // Remains populated across compositor restarts so reconnecting clients
    // immediately receive the full library without waiting for a new USB mount.
    private final List<byte[]> jsonCache = new CopyOnWriteArrayList<>();
    private volatile boolean cacheJson = false;

    // Last binary frame (waveform / art) — replayed to reconnecting clients.
    private volatile byte[] lastBinaryFrame = null;

    public ProLinkWebSocketServer(int port) {
        super(new InetSocketAddress(port));
    }

    /** Enable persistent JSON caching (call before start() for track server). */
    public void enableJsonCache() {
        this.cacheJson = true;
    }

    /** Clear the JSON cache (call when the database unmounts). */
    public void clearJsonCache() {
        jsonCache.clear();
        System.out.println("[port " + getPort() + "] JSON cache cleared");
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        clients.put(conn, new ClientConnection(conn));
        System.out.println("Client connected: " + conn.getRemoteSocketAddress()
                + "  (cache=" + jsonCache.size() + " msgs)");

        // Replay entire JSON cache so the client gets the full library immediately,
        // even if the database mounted long before this connection was opened.
        if (!jsonCache.isEmpty()) {
            for (byte[] msg : jsonCache) {
                try { conn.send(msg); } catch (Exception ignored) {}
            }
        }

        // Replay the last binary frame (waveform / album art).
        byte[] last = lastBinaryFrame;
        if (last != null) {
            try { conn.send(last); } catch (Exception ignored) {}
        }
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        ClientConnection removed = clients.remove(conn);
        if (removed != null) removed.shutdown();
        System.out.println("Client disconnected (code=" + code + ")");
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        if (messageHandler != null) {
            messageHandler.accept(message);
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        // Log but do NOT rethrow — keeps the server alive on transient errors.
        System.err.println("[WebSocket error] " + ex.getMessage());
    }

    @Override
    public void onStart() {
        System.out.println("WebSocket server started on port " + getPort());
    }

    // ── Frame broadcasting ────────────────────────────────────────────────────

    private final ExecutorService websocketExecutor = Executors.newFixedThreadPool(64);
    private final AtomicReference<ByteBuffer> latestFrame = new AtomicReference<>();

    {
        Thread dispatcher = new Thread(() -> {
            while (true) {
                ByteBuffer frame = latestFrame.getAndSet(null);
                if (frame != null) {
                    for (ClientConnection conn : clients.values()) {
                        conn.sendFrame(frame);
                    }
                }
                try {
                    Thread.sleep(1);
                } catch (InterruptedException ignored) {}
            }
        }, "WebSocket-BroadcastDispatcher");
        dispatcher.setDaemon(true);
        dispatcher.start();
    }

    public void broadcastFrame(ByteBuffer rawRgbaBuffer) {
        synchronized (framePool) {
            framePool.clear();
            framePool.put(rawRgbaBuffer.asReadOnlyBuffer());
            framePool.flip();
            latestFrame.set(framePool.asReadOnlyBuffer());
        }
    }

    public void broadcastStatus(DeviceStatus status) {
        try {
            broadcastJsonBytes(mapper.writeValueAsBytes(status));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public void broadcastTrack(Track track) {
        try {
            broadcastJsonBytes(mapper.writeValueAsBytes(track));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private void broadcastJsonBytes(byte[] bytes) {
        // Cache for future clients (if enabled).
        if (cacheJson) jsonCache.add(bytes);

        // Send to all currently connected clients.
        for (ClientConnection conn : clients.values()) {
            websocketExecutor.submit(() -> conn.sendFrame(bytes));
        }
    }

    /** Public variant used by DBService for pre-serialised JSON bytes (playlist nodes). */
    public void broadcastRawJson(byte[] bytes) {
        broadcastJsonBytes(bytes);
    }

    public void broadcastRawBytes(byte[] bytes) {
        lastBinaryFrame = bytes;   // cache for reconnecting clients
        for (ClientConnection conn : clients.values()) {
            websocketExecutor.submit(() -> conn.sendFrame(bytes));
        }
    }

    public void setMessageHandler(Consumer<String> handler) {
        this.messageHandler = handler;
    }

    public void shutdown() {
        for (ClientConnection conn : clients.values()) {
            conn.shutdown();
        }
    }
}
