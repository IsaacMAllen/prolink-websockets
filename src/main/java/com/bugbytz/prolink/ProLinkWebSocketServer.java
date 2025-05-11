package com.bugbytz.prolink;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.java_websocket.server.WebSocketServer;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
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

    private final ConcurrentLinkedQueue<byte[]> pendingBinaryMessages = new ConcurrentLinkedQueue<>();

    public ProLinkWebSocketServer(int port) {
        super(new InetSocketAddress(port));
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        clients.put(conn, new ClientConnection(conn));
        System.out.println("Client connected: " + conn.getRemoteSocketAddress());

        // Flush pending binary messages to new client
        byte[] msg;
        while ((msg = pendingBinaryMessages.poll()) != null) {
            conn.send(msg);
        }
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        ClientConnection removed = clients.remove(conn);
        if (removed != null) removed.shutdown();
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

    private final ExecutorService websocketExecutor = Executors.newFixedThreadPool(64);

    private final AtomicReference<ByteBuffer> latestFrame = new AtomicReference<>();

    public void broadcastFrame(ByteBuffer rawRgbaBuffer) {
        synchronized (framePool) {
            framePool.clear();
            framePool.put(rawRgbaBuffer.asReadOnlyBuffer());
            framePool.flip();
            latestFrame.set(framePool.asReadOnlyBuffer()); // this is cheap
        }
    }

    {
        Thread dispatcher = new Thread(() -> {
            while (true) {
                ByteBuffer frame = latestFrame.getAndSet(null);
                if (frame != null) {
                    for (ClientConnection conn : clients.values()) {
                        conn.sendFrame(frame); // aggressively drops old frames
                    }
                }
                try {
                    Thread.sleep(1); // low CPU
                } catch (InterruptedException ignored) {}
            }
        }, "WebSocket-BroadcastDispatcher");
        dispatcher.setDaemon(true);
        dispatcher.start();
    }

    public void broadcastStatus(DeviceStatus status) {
        try {
            byte[] bytes = mapper.writeValueAsBytes(status);
            broadcastJsonBytes(bytes);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public void broadcastTrack(Track track) {
        try {
            byte[] bytes = mapper.writeValueAsBytes(track);
            broadcastJsonBytes(bytes);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private void broadcastJsonBytes(byte[] bytes) {
        for (ClientConnection conn : clients.values()) {
            websocketExecutor.submit(() -> conn.sendFrame(bytes));
        }
    }

    public void broadcastRawBytes(byte[] bytes) {
        if (clients.isEmpty()) {
            // No clients yet — queue for later
            pendingBinaryMessages.add(bytes);
        } else {
            for (ClientConnection conn : clients.values()) {
                websocketExecutor.submit(() -> conn.sendFrame(bytes));
            }
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
