package bgu.spl.net.srv;

import bgu.spl.net.api.MessageEncoderDecoder;
import bgu.spl.net.api.MessagingProtocol;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.Socket;

public class BlockingConnectionHandler<T> implements Runnable, ConnectionHandler<T> {

    private final MessagingProtocol<T> protocol;
    private final MessageEncoderDecoder<T> encdec;
    private final Socket sock;
    private final Connections<T> connections;
    private final int connectionId;

    private BufferedInputStream in;
    private BufferedOutputStream out;
    private final Object writeLock = new Object();
    private volatile boolean connected = true;

    public BlockingConnectionHandler(
            Socket sock,
            MessageEncoderDecoder<T> reader,
            MessagingProtocol<T> protocol,
            Connections<T> connections,
            int connectionId) {

        this.sock = sock;
        this.encdec = reader;
        this.protocol = protocol;
        this.connections = connections;
        this.connectionId = connectionId;
    }

    @Override
    public void run() {
        try (Socket sock = this.sock) {
            in = new BufferedInputStream(sock.getInputStream());
            out = new BufferedOutputStream(sock.getOutputStream());
            int read;
            while (!protocol.shouldTerminate() && connected && (read = in.read()) >= 0) {
                T nextMessage = encdec.decodeNextByte((byte) read);
                if (nextMessage != null) {
                    T response = protocol.process(nextMessage);
                    if (response != null) {
                        send(response);
                    }
                }
            }
        } catch (IOException ex) {
            ex.printStackTrace();
        } finally {
            connected = false;
            connections.disconnect(connectionId);
        }
    }

    @Override
    public void close() throws IOException {
        connected = false;
        connections.disconnect(connectionId);
        sock.close();
    }

    @Override
    public void send(T msg) {
        if (msg == null) return;
        byte[] bytes = encdec.encode(msg);
        synchronized (writeLock) {
            if (!connected || out == null) return;
            try {
                out.write(bytes);
                out.flush();
            } catch (IOException e) {
                connected = false;
                connections.disconnect(connectionId);
            }
        }
    }
}
