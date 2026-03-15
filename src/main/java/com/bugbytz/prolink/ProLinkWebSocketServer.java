package com.bugbytz.prolink;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.java_websocket.server.WebSocketServer;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.Consumer;

public class ProLinkWebSocketServer extends WebSocketServer {

    private Consumer<String> messageHandler = null;
    private final ObjectMapper mapper = new ObjectMapper();

    // Cached so late-connecting clients (e.g. after a reconnect) immediately receive
    // the current track's waveform without waiting for the next track-load event.
    private volatile byte[] lastBinaryFrame = null;

    private final Set<WebSocket> clients = Collections.synchronizedSet(new HashSet<>());
    private final ExecutorService sendExecutor = new ThreadPoolExecutor(
            4, 4,
            0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(128),
            new ThreadPoolExecutor.DiscardPolicy()
    );

    public ProLinkWebSocketServer(int port) {
        super(new InetSocketAddress(port));
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        clients.add(conn);
        System.out.println("Client connected: " + conn.getRemoteSocketAddress());
        // Immediately replay the last static waveform so the client doesn't have to
        // wait for the next track-load event to populate its texture.
        byte[] last = lastBinaryFrame;
        if (last != null) {
            try { conn.send(last); } catch (Exception ignored) {}
        }
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        clients.remove(conn);
        System.out.println("Client disconnected");
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        if (messageHandler != null) {
            messageHandler.accept(message);
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        ex.printStackTrace();
    }

    @Override
    public void onStart() {
        System.out.println("WebSocket server started");
    }

    /**
     * Broadcasts the given RGBA frame to all connected clients and caches it so
     * future clients receive it immediately on connect.
     *
     * The caller must pass a stable buffer (use {@code .clone()} if the underlying
     * array may be reused).
     */
    public void broadcastFrame(byte[] rawRgbaFrame) {
        lastBinaryFrame = rawRgbaFrame;
        sendExecutor.submit(() -> {
            synchronized (clients) {
                for (WebSocket client : clients) {
                    if (client.isOpen()) {
                        try {
                            client.send(rawRgbaFrame);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        });
    }

    public void broadcastStatus(DeviceStatus status) {
        try {
            broadcastJsonBytes(mapper.writeValueAsString(status));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public void broadcastTrack(Track track) {
        try {
            broadcastJsonBytes(mapper.writeValueAsString(track));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private void broadcastJsonBytes(String s) {
        try {
            String json = s;
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

            sendExecutor.submit(() -> {
                synchronized (clients) {
                    for (WebSocket client : clients) {
                        if (client.isOpen()) {
                            client.send(bytes);
                        }
                    }
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void setMessageHandler(Consumer<String> handler) {
        this.messageHandler = handler;
    }
    public void shutdown() {
        sendExecutor.shutdown();
    }
}