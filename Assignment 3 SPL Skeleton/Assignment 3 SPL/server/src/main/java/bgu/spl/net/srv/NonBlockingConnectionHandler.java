// bgu/spl/net/srv/NonBlockingConnectionHandler.java
package bgu.spl.net.srv;

import bgu.spl.net.api.MessageEncoderDecoder;
import bgu.spl.net.api.StompMessagingProtocol;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class NonBlockingConnectionHandler<T> implements ConnectionHandler<T> {

    private static final int BUFFER_ALLOCATION_SIZE = 1 << 13;
    private static final ConcurrentLinkedQueue<ByteBuffer> BUFFER_POOL = new ConcurrentLinkedQueue<>();

    private final StompMessagingProtocol<T> protocol;
    private final MessageEncoderDecoder<T> encdec;
    private final Queue<ByteBuffer> writeQueue = new ConcurrentLinkedQueue<>();
    private final SocketChannel chan;
    private final Reactor<T> reactor;

    private final Connections<T> connections;
    private final int connectionId;

    private final Object encodeLock = new Object();

    public NonBlockingConnectionHandler(MessageEncoderDecoder<T> reader,
                                        StompMessagingProtocol<T> protocol,
                                        SocketChannel chan,
                                        Reactor<T> reactor,
                                        Connections<T> connections,
                                        int connectionId) {
        this.chan = chan;
        this.encdec = reader;
        this.protocol = protocol;
        this.reactor = reactor;
        this.connections = connections;
        this.connectionId = connectionId;
    }

    public int getConnectionId() {
        return connectionId;
    }

    public Runnable continueRead() {
        ByteBuffer buf = leaseBuffer();
        boolean success;

        try {
            success = chan.read(buf) != -1;
        } catch (IOException ex) {
            success = false;
        }

        if (!success) {
            releaseBuffer(buf);
            connections.disconnect(connectionId);
            return null;
        }

        buf.flip();
        return () -> {
            try {
                while (buf.hasRemaining()) {
                    T nextMessage = encdec.decodeNextByte(buf.get());
                    if (nextMessage != null) {
                        protocol.process(nextMessage);
                        if (protocol.shouldTerminate() && writeQueue.isEmpty()) {
                            connections.disconnect(connectionId);
                            return;
                        }
                    }
                }
            } finally {
                releaseBuffer(buf);
                if (!isClosed()) {
                    int ops = SelectionKey.OP_READ;
                    if (!writeQueue.isEmpty()) ops |= SelectionKey.OP_WRITE;
                    reactor.updateInterestedOps(chan, ops);
                }
            }
        };
    }

    public void continueWrite() {
        while (!writeQueue.isEmpty()) {
            try {
                ByteBuffer top = writeQueue.peek();
                if (top == null) break;

                chan.write(top);
                if (top.hasRemaining()) return;

                writeQueue.poll();
            } catch (IOException ex) {
                connections.disconnect(connectionId);
                return;
            }
        }

        if (writeQueue.isEmpty()) {
            if (protocol.shouldTerminate()) {
                connections.disconnect(connectionId);
            } else {
                reactor.updateInterestedOps(chan, SelectionKey.OP_READ);
            }
        }
    }

    @Override
    public void send(T msg) {
        if (msg == null || isClosed()) return;

        byte[] bytes;
        synchronized (encodeLock) {
            bytes = encdec.encode(msg);
        }

        writeQueue.add(ByteBuffer.wrap(bytes));
        reactor.updateInterestedOps(chan, SelectionKey.OP_READ | SelectionKey.OP_WRITE);
    }

    @Override
    public void close() throws IOException {
        chan.close();
    }

    public boolean isClosed() {
        return !chan.isOpen();
    }

    private static ByteBuffer leaseBuffer() {
        ByteBuffer buff = BUFFER_POOL.poll();
        if (buff == null) return ByteBuffer.allocateDirect(BUFFER_ALLOCATION_SIZE);
        buff.clear();
        return buff;
    }

    private static void releaseBuffer(ByteBuffer buff) {
        BUFFER_POOL.add(buff);
    }
}
