package bgu.spl.net.impl.rci;

import java.io.Serializable;

import bgu.spl.net.api.StompMessagingProtocol;
import bgu.spl.net.srv.Connections;

public class RemoteCommandInvocationProtocol<T> implements StompMessagingProtocol<Serializable> {

    private T arg;
    private int connectionId;
    private Connections<Serializable> connections;

    public RemoteCommandInvocationProtocol(T arg) {
        this.arg = arg;
    }

    @Override
    public void start(int connectionId, Connections<Serializable> connections) {
        this.connectionId = connectionId;
        this.connections = connections;
    }

    @Override
    public void process(Serializable msg) {
        Serializable result = ((Command) msg).execute(arg);
        if (connections != null && result != null) {
            connections.send(connectionId, result);
        }
    }

    @Override
    public boolean shouldTerminate() {
        return false;
    }

}