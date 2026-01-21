package bgu.spl.net.impl.echo;

import java.time.LocalDateTime;

import bgu.spl.net.api.StompMessagingProtocol;
import bgu.spl.net.srv.Connections;

public class EchoProtocol implements StompMessagingProtocol<String> {

    private boolean shouldTerminate = false;
    private int connectionId;
    private Connections<String> connections;

    @Override
    public void start(int connectionId, Connections<String> connections) {
        this.connectionId = connectionId;
        this.connections = connections;
    }

    @Override
    public void process(String msg) {
        shouldTerminate = "bye".equals(msg);
        System.out.println("[" + LocalDateTime.now() + "]: " + msg);
        String response = createEcho(msg);
        if (connections != null) {
            connections.send(connectionId, response);
        }
    }

    private String createEcho(String message) {
        String echoPart = message.substring(Math.max(message.length() - 2, 0), message.length());
        return message + " .. " + echoPart + " .. " + echoPart + " ..";
    }

    @Override
    public boolean shouldTerminate() {
        return shouldTerminate;
    }
}