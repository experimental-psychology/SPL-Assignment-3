package bgu.spl.net.srv;

import bgu.spl.net.api.MessageEncoderDecoder;
import bgu.spl.net.api.MessagingProtocol;
import bgu.spl.net.impl.stomp.ConnectionsImpl;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public abstract class BaseServer<T> implements Server<T> {

    private final int port;
    private final Supplier<MessagingProtocol<T>> protocolFactory;
    private final Supplier<MessageEncoderDecoder<T>> encdecFactory;

    private ServerSocket sock;

    private final ConnectionsImpl<T> connections=new ConnectionsImpl<>();
    private final AtomicInteger nextId=new AtomicInteger(1);

    public BaseServer(
            int port,
            Supplier<MessagingProtocol<T>> protocolFactory,
            Supplier<MessageEncoderDecoder<T>> encdecFactory) {

        this.port = port;
        this.protocolFactory = protocolFactory;
        this.encdecFactory = encdecFactory;
        this.sock = null;
    }

    @Override
    public void serve() {
        try(ServerSocket serverSock = new ServerSocket(port)){
            System.out.println("Server started");
            this.sock=serverSock;
            while(!Thread.currentThread().isInterrupted()){
                Socket clientSock=serverSock.accept();
                int id=nextId.getAndIncrement();
                MessagingProtocol<T> protocol = protocolFactory.get();
                BlockingConnectionHandler<T> handler=new BlockingConnectionHandler<>(clientSock,
                        encdecFactory.get(),protocol,connections,id);
                connections.connect(id, handler);
                protocol.start(id, connections);
                execute(handler);
            }
        }catch(IOException ex){
            throw new RuntimeException(ex);
        }finally{
            System.out.println("server closed!!!");
        }
    }

    @Override
    public void close() throws IOException {
        if (sock != null)
            sock.close();
    }
    protected abstract void execute(BlockingConnectionHandler<T> handler);
}
