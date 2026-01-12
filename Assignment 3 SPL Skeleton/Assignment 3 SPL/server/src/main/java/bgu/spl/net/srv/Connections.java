package bgu.spl.net.srv;

import java.io.IOException;

public interface Connections<T> {

    boolean send(int connectionId, T msg);

    void send(String channel, T msg);

    void connect(int connectionId, ConnectionHandler<T> handler);

    void disconnect(int connectionId);

}
