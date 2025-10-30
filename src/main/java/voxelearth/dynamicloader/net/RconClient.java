package voxelearth.dynamicloader.net;

import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicInteger;

/** Minimal RCON client for localhost control of per-party servers. */
public class RconClient implements Closeable {
    private final String host;
    private final int port;
    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private static final int SERVERDATA_AUTH = 3;
    private static final int SERVERDATA_EXECCOMMAND = 2;
    private static final AtomicInteger IDS = new AtomicInteger(1);
    private final String password;

    public RconClient(String host, int port, String password) {
        this.host = host;
        this.port = port;
        this.password = password;
    }

    public void connect() throws IOException {
        socket = new Socket(host, port);
        socket.setSoTimeout(5000);
        in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        if (!auth(password)) throw new IOException("RCON auth failed");
    }

    private boolean auth(String pass) throws IOException {
        int id = IDS.getAndIncrement();
        sendPacket(id, SERVERDATA_AUTH, pass);
        var resp = readPacket();
        return resp.id == id;
    }

    public String command(String cmd) throws IOException {
        int id = IDS.getAndIncrement();
        sendPacket(id, SERVERDATA_EXECCOMMAND, cmd);
        var resp = readPacket();
        return resp.body;
    }

    /** Fire-and-forget command variant that skips reading a reply to avoid blocking the caller. */
    public void commandNoReply(String cmd) throws IOException {
        int id = IDS.getAndIncrement();
        sendPacket(id, SERVERDATA_EXECCOMMAND, cmd);
        // Vanilla RCON delimiters: send an empty packet so the backend flushes the reply if any.
        sendPacket(id, SERVERDATA_EXECCOMMAND, "");
        // Intentionally do not call readPacket(); caller treats this as write-only.
    }

    private static class Packet { int id; String body; }

    private void sendPacket(int id, int type, String body) throws IOException {
        byte[] payload = (body + "\0\0").getBytes();
        ByteBuffer buf = ByteBuffer.allocate(4 + 4 + 4 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(4 + 4 + payload.length);
        buf.putInt(id);
        buf.putInt(type);
        buf.put(payload);
        out.write(buf.array());
        out.flush();
    }

    private Packet readPacket() throws IOException {
        int size = Integer.reverseBytes(in.readInt());
        byte[] data = in.readNBytes(size);
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        Packet p = new Packet();
    p.id = buf.getInt();
    buf.getInt(); // type (unused)
        int end = data.length - 2; // message \0 + empty \0
        int start = 8;
        p.body = new String(data, start, Math.max(0, end - start));
        return p;
    }

    @Override public void close() throws IOException {
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
    }
}
