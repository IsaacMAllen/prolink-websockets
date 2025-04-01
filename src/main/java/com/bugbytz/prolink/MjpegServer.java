package com.bugbytz.prolink;

import fi.iki.elonen.NanoHTTPD;

import java.io.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class MjpegServer extends NanoHTTPD {

    private final BlockingQueue<byte[]> frameQueue = new LinkedBlockingQueue<>();

    public MjpegServer(int port) throws IOException {
        super(port);
        start(SOCKET_READ_TIMEOUT, false);
        System.out.println("Started MJPEG server on port " + port);
    }

    public void offerFrame(byte[] jpegBytes) {
        frameQueue.offer(jpegBytes);
    }

    @Override
    public Response serve(IHTTPSession session) {
        PipedInputStream in = new PipedInputStream();
        PipedOutputStream out;

        try {
            out = new PipedOutputStream(in);
        } catch (IOException e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Stream error");
        }

        new Thread(() -> {
            try {
                while (true) {
                    byte[] frame = frameQueue.take(); // blocking
                    writeFrameToStream(out, frame);
                }
            } catch (InterruptedException | IOException e) {
                System.out.println("MJPEG stream closed: " + e.getMessage());
            }
        }).start();

        Response response = newChunkedResponse(Response.Status.OK,
                "multipart/x-mixed-replace; boundary=--frame", in);

        response.addHeader("Connection", "close");
        response.addHeader("Cache-Control", "no-cache");
        return response;
    }

    private void writeFrameToStream(OutputStream out, byte[] frame) throws IOException {
        out.write(("--frame\r\n" +
                "Content-Type: image/jpeg\r\n" +
                "Content-Length: " + frame.length + "\r\n\r\n").getBytes());
        out.write(frame);
        out.write("\r\n".getBytes());
        out.flush();
    }
}

