package bgu.spl.net.srv;

import bgu.spl.net.api.MessageEncoderDecoder;
import bgu.spl.net.api.StompMessagingProtocol;
import bgu.spl.net.impl.stomp.ConnectionsImpl;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public class Reactor<T> implements Server<T> {

    private final int port;
    private final Supplier<StompMessagingProtocol<T>> protocolFactory;
    private final Supplier<MessageEncoderDecoder<T>> readerFactory;
    private final ActorThreadPool pool;

    private final ConnectionsImpl<T> connections = new ConnectionsImpl<>();
    private final AtomicInteger nextId = new AtomicInteger(0);

    private Selector selector;
    private Thread selectorThread;
    private final ConcurrentLinkedQueue<Runnable> selectorTasks = new ConcurrentLinkedQueue<>();

    public Reactor(int numThreads,
                   int port,
                   Supplier<StompMessagingProtocol<T>> protocolFactory,
                   Supplier<MessageEncoderDecoder<T>> readerFactory) {
        this.pool = new ActorThreadPool(numThreads);
        this.port = port;
        this.protocolFactory = protocolFactory;
        this.readerFactory = readerFactory;
    }

    @Override
    public void serve() {
        selectorThread = Thread.currentThread();

        try (Selector selector = Selector.open();
             ServerSocketChannel serverSock = ServerSocketChannel.open()) {

            this.selector = selector;

            serverSock.bind(new InetSocketAddress(port));
            serverSock.configureBlocking(false);
            serverSock.register(selector, SelectionKey.OP_ACCEPT);
            System.out.println("Server started");

            while (!Thread.currentThread().isInterrupted()) {
                selector.select();
                runSelectionThreadTasks();

                // תיקון 1: שימוש ב-Iterator עם remove מיידי
                Iterator<SelectionKey> iter = selector.selectedKeys().iterator();
                while (iter.hasNext()) {
                    SelectionKey key = iter.next();
                    iter.remove(); // הסרה מיידית - מונע לולאה אינסופית במקרה של חריגה

                    // תיקון 3: try-catch פנימי למניעת קריסת השרת
                    try {
                       if (!key.isValid()) {
                            cleanupKey(key);
                            continue;
                        }
                        if (key.isAcceptable()) {
                            handleAccept(serverSock, selector);
                        } else {
                            handleReadWrite(key);
                        }
                    } catch (Exception ex) {
                        System.err.println("Error handling client: " + ex.getMessage());
                        ex.printStackTrace();
                        cleanupKey(key);
                    }
                }
            }

        } catch (ClosedSelectorException ex) {
            // סגירה רגילה
        } catch (IOException ex) {
            ex.printStackTrace();
        }

        System.out.println("server closed!!!");
        pool.shutdown();
    }

    void updateInterestedOps(SocketChannel chan, int ops) {
        final SelectionKey key = chan.keyFor(selector);
        if (key == null || !key.isValid()) return;

        if (Thread.currentThread() == selectorThread) {
            key.interestOps(ops);
        } else {
            selectorTasks.add(() -> {
                if (key.isValid()) key.interestOps(ops);
            });
            selector.wakeup();
        }
    }

    private void handleAccept(ServerSocketChannel serverChan, Selector selector) throws IOException {
        SocketChannel clientChan = serverChan.accept();
        if (clientChan == null) return;

        clientChan.configureBlocking(false);

        int connectionId = nextId.getAndIncrement();
        StompMessagingProtocol<T> protocol = protocolFactory.get();

        NonBlockingConnectionHandler<T> handler = new NonBlockingConnectionHandler<>(
                readerFactory.get(),
                protocol,
                clientChan,
                this,
                connections,
                connectionId
        );

        connections.connect(connectionId, handler);
        clientChan.register(selector, 0, handler);

        pool.submit(handler, () -> {
            protocol.start(connectionId, connections);
            updateInterestedOps(clientChan, SelectionKey.OP_READ);
        });
    }

    private void handleReadWrite(SelectionKey key) {
        NonBlockingConnectionHandler<T> handler =
                (NonBlockingConnectionHandler<T>) key.attachment();

        if (key.isReadable()) {
            key.interestOps(key.interestOps() & ~SelectionKey.OP_READ);
            Runnable task = handler.continueRead();
            if (task != null) {
                pool.submit(handler, task);
            } else {
                cleanupConnection(key, handler);
            }
        }

        if (key.isValid() && key.isWritable()) {
            handler.continueWrite();
        }
    }
    /**
     * תיקון 2: מתודה לסגירה מסודרת של חיבור - כולל disconnect מפורש
     */
    private void cleanupConnection(SelectionKey key, NonBlockingConnectionHandler<T> handler) {
        if (key != null) {
            key.cancel();
        }
        
        // ניקוי יחיד דרך מנהל החיבורים.
        // ConnectionsImpl.disconnect כבר מבצע handler.close() שמבצע channel.close().
        if (handler != null) {
            connections.disconnect(handler.getConnectionId());
        }
    }
    /**
     * ניקוי כללי במקרה של שגיאה
     */
    private void cleanupKey(SelectionKey key) {
        if (key == null) return;
        NonBlockingConnectionHandler<T> handler =
                (NonBlockingConnectionHandler<T>) key.attachment();
        cleanupConnection(key, handler);
    }

    private void runSelectionThreadTasks() {
        while (!selectorTasks.isEmpty()) {
            Runnable task = selectorTasks.poll();
            if (task != null) {
                try {
                    task.run();
                } catch (Exception ex) {
                    System.err.println("Error in selector task: " + ex.getMessage());
                }
            }
        }
    }

    @Override
    public void close() throws IOException {
        if (selector != null) {
            selector.close();
        }
    }
}