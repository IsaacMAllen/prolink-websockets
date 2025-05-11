package com.bugbytz.prolink;

import org.java_websocket.WebSocket;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class ClientConnection {
    private static final int MAX_QUEUE_SIZE = 2;

    private final WebSocket socket;
    private final BlockingQueue<ByteBuffer> queue = new ArrayBlockingQueue<>(MAX_QUEUE_SIZE);
    private final Thread writerThread;

    public ClientConnection(WebSocket socket) {
        this.socket = socket;
        this.writerThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    ByteBuffer frame = queue.take();
                    socket.send(frame);
                } catch (InterruptedException ignored) {
                    break;
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }, "ClientWriter-" + socket.getRemoteSocketAddress());
        this.writerThread.setDaemon(true);
        this.writerThread.start();
    }

    public void sendFrame(ByteBuffer frame) {
        // Drop if queue is full
        if (!queue.offer(frame)) {
            // drop frame silently
            queue.poll();
            queue.offer(frame);
        }
    }

    public void sendFrame(byte[] bytes) {
        try {
            socket.send(bytes);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void shutdown() {
        writerThread.interrupt();
        try {
            socket.close();
        } catch (Exception ignored) {}
    }
}
