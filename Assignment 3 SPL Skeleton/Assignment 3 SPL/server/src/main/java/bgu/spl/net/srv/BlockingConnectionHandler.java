package bgu.spl.net.srv;

import bgu.spl.net.api.MessageEncoderDecoder;
import bgu.spl.net.api.StompMessagingProtocol;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.Socket;

public class BlockingConnectionHandler<T> implements Runnable, ConnectionHandler<T> {

    private final StompMessagingProtocol<T> protocol;
    private final MessageEncoderDecoder<T> encdec;
    private final Socket sock;
    private final Connections<T> connections;
    private final int connectionId;

    private BufferedInputStream in;
    private BufferedOutputStream out;

    private final Object writeLock = new Object();
    private volatile boolean connected = true;

    public BlockingConnectionHandler(Socket sock,
                                    MessageEncoderDecoder<T> reader,
                                    StompMessagingProtocol<T> protocol,
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
        try (Socket ignored = this.sock) {
            System.out.println("[HANDLER] Connection " + connectionId + " started");
            in = new BufferedInputStream(sock.getInputStream());
            out = new BufferedOutputStream(sock.getOutputStream());

            protocol.start(connectionId, connections);

            int read;
            while (connected && !protocol.shouldTerminate() && (read = in.read()) >= 0) {
                T nextMessage = encdec.decodeNextByte((byte) read);
                if (nextMessage != null) {
                    protocol.process(nextMessage);
                }
            }
        } catch (IOException ex) {
        } finally {
            connected = false;
            connections.disconnect(connectionId);
        }
    }
    
    @Override
    public void close() throws IOException {
        connected = false;
        sock.close();
    }
    
    @Override
    public void send(T msg) {
        if (msg == null || !connected) return;

        synchronized (writeLock) {
            if (!connected || out == null) return;
            
            try {
                byte[] bytes = encdec.encode(msg);
                out.write(bytes);
                out.flush();
            } catch (IOException e) {
                connected = false;
                try {
                    sock.close();
                } catch (IOException ignored) {
                }
                connections.disconnect(connectionId);
            }
        }
    }
}